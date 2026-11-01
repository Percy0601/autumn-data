# Spring Data 派生查询（方法名约定查询）规范

> 本文是 `autumn-data-jdbc` 编译期查询解析器（`findByNameAndAge` 一类方法名 → SQL）的实现依据。
> 内容核对自本地 `spring-data-commons:4.1.1` 源码：
> `org.springframework.data.repository.query.parser.PartTree` / `Part`，以及 `CrudRepository` / `PagingAndSortingRepository`。

## 1. 方法名总体结构

```
<Subject><PropertyParts><Sorting>
```

- **Subject（主语）**：`find|read|get|query|search|stream`、`count`、`exists`、`delete|remove`，可跟 `By`。
  `PartTree` 的前缀正则（spring-data-commons 源码 `org/springframework/data/repository/query/parser/PartTree.java` 的 `PREFIX_TEMPLATE`）：
  `^(find|read|get|query|search|stream|count|exists|delete|remove)((\p{Lu}.*?))??By`
- **PropertyParts（谓词）**：`PropertyKeyword [And | Or] PropertyKeyword ...`
- **Sorting**：尾部可选 `OrderBy<Property><Asc|Desc>[<Property><Asc|Desc>...]`
- 方法名里**没有** `By` 时，整段被当作属性路径处理（等价于按该属性做 SIMPLE_PROPERTY 匹配，或直接走自定义 `@Query`）。

## 2. Subject 前缀（动词）

| 前缀 | 语义 | 返回类型约定 |
|---|---|---|
| `find…By` / `read…By` / `get…By` / `query…By` / `search…By` / `stream…By` | 查询 | `List<T>` / `Optional<T>` / 单实体 / 投影 / `Stream<T>` |
| `findDistinct…By` / `findDistinctName…By` | 去重（可对部分属性去重） | `List<…>` |
| `findFirst…By` / `findTop…By`（可带数字，缺省为 1） | 限制行数 | `Optional<T>` / `List<T>` |
| `count…By` | 计数 | `long` |
| `countDistinct…By` | 去重计数（部分存储支持） | `long` |
| `exists…By` | 是否存在 | `boolean` |
| `delete…By` / `remove…By` | 删除 | `long`（删除条数）/ `List<T>`（需开启返回删除实体） |

实现要点（建议 autumn 保持一致）：
- 行数限制也可以用参数表达：`findFirst(List<PageRequest> …)` / `Limit` / `Pageable`。
- `OrderBy` 配合 `findFirst` 才有确定语义，否则结果不确定。

## 3. 关键词（PropertyKeyword）

来自 `Part.Type` 枚举，按匹配优先级排列（**顺序敏感**，长词必须优先匹配，如 `IsNotNull` 先于 `IsNull`）：

| Type | 关键词（含 `Is` 前缀变体） | 参数个数 | SQL 语义 |
|---|---|---|---|
| BETWEEN | `Between` | 2 | `x BETWEEN ?1 AND ?2` |
| IS_NOT_NULL | `IsNotNull` / `NotNull` | 0 | `x IS NOT NULL` |
| IS_NULL | `IsNull` / `Null` | 0 | `x IS NULL` |
| LESS_THAN | `LessThan` | 1 | `x < ?` |
| LESS_THAN_EQUAL | `LessThanEqual` | 1 | `x <= ?` |
| GREATER_THAN | `GreaterThan` | 1 | `x > ?` |
| GREATER_THAN_EQUAL | `GreaterThanEqual` | 1 | `x >= ?` |
| BEFORE | `Before` | 1 | `x < ?`（时间语义别名） |
| AFTER | `After` | 1 | `x > ?`（时间语义别名） |
| NOT_LIKE | `NotLike` | 1 | `x NOT LIKE ?` |
| LIKE | `Like` | 1 | `x LIKE ?` |
| STARTING_WITH | `StartingWith` / `StartsWith` | 1 | `x LIKE '?%'` |
| ENDING_WITH | `EndingWith` / `EndsWith` | 1 | `x LIKE '%?'` |
| IS_NOT_EMPTY | `IsNotEmpty` / `NotEmpty` | 0 | `x <> ''` |
| IS_EMPTY | `IsEmpty` / `Empty` | 0 | `x = ''` |
| NOT_CONTAINING | `NotContaining` / `NotContains` | 1 | 集合/字符串取反包含 |
| CONTAINING | `Containing` / `Contains` | 1 | 集合/字符串包含 |
| NOT_IN | `NotIn` | 1（集合/数组） | `x NOT IN (…)` |
| IN | `In` | 1（集合/数组） | `x IN (…)` |
| NEAR | `Near` | 2 | 地理近邻 |
| WITHIN | `Within` | 2~3 | 地理范围 |
| REGEX | `MatchesRegex` / `Matches` / `Regex` | 1 | 正则匹配（关系库需方言支持） |
| EXISTS | `Exists` | 0 | 实体级存在判断 |
| TRUE | `IsTrue` / `True` | 0 | `x = true` |
| FALSE | `IsFalse` / `False` | 0 | `x = false` |
| NEGATING_SIMPLE_PROPERTY | `IsNot` / `Not` | 1 | `x <> ?` |
| SIMPLE_PROPERTY | `Is` / `Equals`（及无关键词） | 1 | `x = ?` |

> `autumn-data-jdbc` 首期（关系库 CRUD + 关联查询）建议实现到 `REGEX` 之前的 26 个；`NEAR` / `WITHIN` / `EXISTS` 属于存储特化，可标注暂不支持并在编译期报错。

## 4. 逻辑组合与否定

- `And` / `Or` 连接多个谓词，**`And` 优先级高于 `Or`**，`Or` 出现即形成 `OrPart` 分组：
  `findByNameOrAge` → `WHERE name = ? OR age = ?`
  `findByNameAndAgeOrEmail` → `WHERE (name = ? AND age = ?) OR email = ?`
- 属性名以 `Not` 结尾属于否定 SIMPLE_PROPERTY：`findByAgeNot` → `age <> ?`（注意与 `NotIn`、`NotNull` 等关键词区分——扫描时关键词整体匹配，先命中长关键词）。
- 括号表达式（Spring Data 3.x+ 支持在 `By` 后使用 `(...)` 显式分组）首期可不支持，但方法名解析器要把 `(` `)` 预留为非法字符并给出清晰报错。

## 5. 属性路径（PropertyPath）

- 谓词属性名按**大写字母边界**切分，驼峰转下划线交由命名策略决定；解析结果必须能匹配到实体上的属性（含嵌入式/关联实体的点号路径）。
- 支持嵌套路径：`findByAddressZipCode` → `address.zipCode`（先整体尝试，再逐级回退切分）。
- 支持**忽略连接词的捷径（skip）**：当实体存在 `addressZipCode` 这类扁平属性时，优先整体匹配，再尝试切分。
- 关键字**出现在属性内部**是允许的（如 `NameRegex`、`SpecialOffers`）；实现必须用「关键词后紧跟大写字母或非 ASCII」的 lookahead（源码里的 `KEYWORD_TEMPLATE`），不能简单 `contains` 判断。

## 6. 排序（Sorting）

- `OrderBy<Prop>Asc|Desc` 后缀，多属性依次追加：`findByAgeOrderByLastNameAscFirstNameDesc`
- `Sort` / `Pageable` 参数参与运行时排序；`OrderBy` 与方法参数同时存在时，约定为**参数追加在方法名之后**（不同 spring-data 模块历史行为略有差异，autumn 需在文档里锁死一种）。

## 7. 保留的标准 CRUD 方法（`CrudRepository`）

这些方法名**不得**被派生查询解析器抢走：

```java
Optional<T> findById(ID id);
Iterable<T> findAll();
Iterable<T> findAllById(Iterable<ID> ids);
long count();
void deleteById(ID id);
void delete(T entity);
void deleteAllById(Iterable<? extends ID> ids);
void deleteAll(Iterable<? extends T> entities);
void deleteAll();
<S extends T> S save(S entity);
<S extends T> Iterable<S> saveAll(Iterable<S> entities);
boolean existsById(ID id);
```

分页/排序（`PagingAndSortingRepository`）：`Page<T> findAll(Pageable)`、`Stream<T> findAllBy(Sort)`。
派生查询的「多结果 → 单结果」返回类型不匹配应在**编译期**报错（这正是 autumn 相对 Spring 运行时解析的优势）。

## 8. autumn-data-jdbc 的解析优先级（编译期实现约定）

1. 显式注解（`@Query` / FreeMarker 模板，见 `autumn-data-meta`）优先于方法名派生。
2. 方法名完全匹配第 7 节标准 CRUD 签名 → 走内置实现。
3. 剩余方法按第 1~6 节解析；解析失败（未知关键词、属性不存在、参数个数不符、返回类型不符）必须在 APT 阶段输出定位到方法声明的错误，不允许静默生成。
4. 关联查询：嵌套 `PropertyPath` 的落地形态按关联基数分两种。
   - **一对多**（`@OneToMany`，含 `mappedBy`）编译期展开成相关子查询
     `EXISTS (SELECT 1 FROM 子表 WHERE 子表.外键 = 父表.主键 AND 子表.列 …)`，即半连接。
     父行天然不重复，父侧列清单与 `RowMapper` 原样复用，因此不需要 `DISTINCT`，也不影响已有的一对多装配；
     代价是关联表只用于过滤，不能从它取列进 `SELECT`。
   - **多对一**（`@ManyToOne`）同样展开成相关子查询，只是连接方向相反
     `EXISTS (SELECT 1 FROM 子表 WHERE 子表.主键 = 父表.外键 AND 子表.列 …)`；
     外键列取自 `@JoinColumn(name)`，缺省为字段名转下划线加 `_id`。
     多对一的**加载**也是两条语句（非 `JOIN`）：父查询后用父表外键值 `IN` 子表主键一次取回子实体，
     按子表主键建索引后挂回单值字段 —— 因此父表上必须有一个映射该外键列的标量字段（供第二条语句读外键）。
   - 关联路径可以**多段**：`findByOrdersItemsSku` 会按途经的每层关联包一层嵌套 `EXISTS`。
     下钻深度取决于子实体自身是否声明了关联（编译期最多再展开一层），不认识的路径仍在方法声明处报错。

## 9. 当前实现状态（`DerivedQueryParser`）

已生成：

| 构造 | 示例 | 生成结果 |
|---|---|---|
| 6 个查询动词 + `All` | `getByName` / `findAllByName` | `SELECT <父列> FROM <表> WHERE …` |
| `count` / `exists` | `countByName` / `existsByAgeGreaterThan` | `SELECT COUNT(*) …`，且剥掉 `ORDER BY`（PostgreSQL 会因非聚合列拒绝） |
| 关键词 | `Between`、`GreaterThan(Equal)`、`LessThan(Equal)`、`Like`、`In`、`NotIn`、`IsNull`/`IsNotNull`、`True`/`False`、`After`/`Before`、`NotEqual`/`NotLike`/`NotGreaterThan`/`NotLessThan` | 各自 SQL 片段，参数个数在编译期与方法名锁定 |
| `And` / `Or` | `findByNameAndAgeOrCreatedAtIsNull` | `(name = ? AND age = ?) OR created_at IS NULL` |
| `OrderBy…Asc/Desc` | `findByNameOrderByNameAscAgeDesc` | 映射列 + 声明顺序 |
| 一层关联路径 | `findByOrdersOrderNo`、`findByByOwnerMemoLike`、`countByOrdersOrderNoIsNotNull` | `EXISTS` 相关子查询，外键列取自 `@JoinColumn` 或 `mappedBy` 指向的子侧字段 |
| 多段关联路径 | `findByOrdersItemsSku`、`findByAddressCity` | 按每层关联嵌套 `EXISTS`；多对一的连接方向是 `子表.主键 = 父表.外键` |
| `@Query` 自定义 SQL | `findAdults` / `purgeByName` | 直接采用注解里的 SQL，不解析方法名；参数按声明顺序绑定到 `?`，且不触发关联装配 |
| `Distinct` | `findDistinctByName` | `SELECT DISTINCT <列> …`（投影里加 `DISTINCT`，不是运行期去重） |
| `First` / `Top N` | `findFirstByName` / `findTop3ByName` | 行数上限在编译期解析成字面量，运行期由方言拼 `LIMIT` |
| `deleteBy` / `removeBy` | `deleteByName(long)`、`deleteByNameAndAge(List)` | 返回 `long` 走 `update` 拿受影响行数；返回 `List<T>` 先 `SELECT` 取回再 `DELETE` |
| `IgnoreCase` / `IgnoringCase` | `findByNameIgnoreCase` | 列与参数一起包 `LOWER()` |
| `Like` 家族 | `Containing` / `StartingWith` / `EndingWith` | 运行期把实参包成 `%?%` / `?%` / `%?`，不依赖方言的字符串拼接 |
| 返回形态 | `List<T>` / `Optional<T>` | 命中关联时结果仍走装配（两条语句，无 N+1） |

编译期显式报错（不静默生成半成品）：裸实体返回值、多于一处的 `IN`、
参数个数或类型与谓词不符、未知关键词、关联路径下钻到未声明关联的实体。

标识符引号（保留字安全）由编译期选项开启：`-Aautumn.data.jdbc.dialect=mysql|postgresql|h2|default`；
默认不加引号（裸标识符），与既有 schema 一致。开启后生成 SQL 里的表名/列名按方言加引号，
结果集标签不受影响（行映射仍用裸列名）。`@Query` 里手写的 SQL 不会被改写。

## 10. 与 Micronaut Data 的差异备忘

| 维度 | Spring Data（本文） | Micronaut Data |
|---|---|---|
| 解析时机 | 启动/运行时（反射 + 代理） | 编译期 APT 生成实现类 |
| 方法名前缀 | 6 个查询动词 + count/exists/delete | 类似，但 `Predicate` 返回 `Criteria`，命名子集略有差异 |
| 空值关键词 | `Null` / `NotNull` 与 `IsEmpty` 区分 | 相近 |
| 关联查询 | 隐式 join，规则见第 5、8 节 | `@Join`/`joinType` 显式声明为主 |

autumn 目标：以 Spring Data 的**方法名约定**为接口体验，以 Micronaut Data 的**编译期生成**为实现方式，动态 SQL 交给 `autumn-data-meta`（FreeMarker）。
