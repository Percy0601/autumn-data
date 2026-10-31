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
4. 关联查询：嵌套 `PropertyPath` 触发 `JOIN`，join 类型由实体关联注解（`@ManyToOne`/`@OneToMany` 等）推导，默认 `INNER JOIN`，`Optional`/集合关联用 `LEFT JOIN`；需在生成的 SQL 侧避免 `Or` 跨 join 重复行（首期可先 `DISTINCT`）。

## 9. 与 Micronaut Data 的差异备忘

| 维度 | Spring Data（本文） | Micronaut Data |
|---|---|---|
| 解析时机 | 启动/运行时（反射 + 代理） | 编译期 APT 生成实现类 |
| 方法名前缀 | 6 个查询动词 + count/exists/delete | 类似，但 `Predicate` 返回 `Criteria`，命名子集略有差异 |
| 空值关键词 | `Null` / `NotNull` 与 `IsEmpty` 区分 | 相近 |
| 关联查询 | 隐式 join，规则见第 5、8 节 | `@Join`/`joinType` 显式声明为主 |

autumn 目标：以 Spring Data 的**方法名约定**为接口体验，以 Micronaut Data 的**编译期生成**为实现方式，动态 SQL 交给 `autumn-data-meta`（FreeMarker）。
