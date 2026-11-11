# 动态表 CRUD（autumn-data-meta-jdbc）

面向“运行期设计出来的单据/基础资料”：表单设计器建出一张物理表之后，服务不重启、不重新编译，
就能按表名 + 列名单做查询、保存和更新。`autumn-data-jdbc` 的编译期仓储保持不变，本层是它的
运行期补充，不是替代。

## 分层

```text
MetaMapper（静态门面，只持有 volatile engine）
  └─ MetaMapperEngine（实例，按 pool 懒建/缓存 client）
       └─ MetaMapperClient（绑定一个 pool）
            ├─ MetaTableRegistry（表名 → MetaTable 快照，refresh 原子换新）
            ├─ MetaSqlBuilder（按 MetaTable 生成 SQL，标识符只来自快照）
            ├─ MetaStatementStore（表名 → Mapper 文件；已有则读、缺失则按表结构生成一次）
            └─ AutumnJdbc（复用现有执行层：query / page / count / update / 生成键）
```

核心模块不依赖 Spring；Spring Boot 集成在 `autumn-data-jdbc-spring-boot-starter` 的
`AutumnDataMetaMapperAutoConfiguration` 里，只负责把引擎装进静态门面。

## 为什么复用 MetaClass / MetaProperty

`autumn-data-common` 已经有一套 JSON 元模型：`MetaClass`（name / namespace / metaType /
properties）、`MetaProperty`（name / type / typeName / value）。动态表的物理列信息（JDBC 类型、
可空、主键、自增、默认值）直接扩到 `MetaProperty` 上，不再另建 `MetaColumn`：

- `MetaTable` 是 `MetaClass` 的物理表视图：`name()` = `MetaClass.name`，`schema()` = `namespace`；
- `MetaRow` 携带 `MetaClass` 实例，`toMap()` / `toJson()` 直接走 `MetaUtil`；
- 表名/列名/类型/主键只有一份来源，避免 JSON 元模型与 JDBC 元模型各说一套。

JSON 侧原有字段（`type` / `typeName` / `value`）保持不变，物理字段是新增，不影响
`meta-model.json` 的校验与序列化。

## API

```java
MetaMapper.install(MetaMapperEngine.forDataSource("default", dataSource));

// 查询
Optional<MetaRow> row = MetaMapper.findById("form_order", 1L);
MetaMapper.findOne("form_order", MetaQuery.by("order_no", "A001"));
MetaMapper.findList("form_order", MetaQuery.by("status", 1)
        .and("create_time", MetaQuery.Op.GE, from)
        .orderBy("create_time", MetaQuery.Direction.DESC));
MetaMapper.findList("form_order", MetaQuery.all().isNull("remark"));      // 空值判断走专门的方法
MetaMapper.findPage("form_order", MetaQuery.by("status", 1), Pageable.of(0, 20));
MetaMapper.count(...); MetaMapper.exists(...);

// 保存（只 INSERT）
Object id = MetaMapper.save("form_order", Map.of("order_no", "A001", "amount", "12.50"));
List<Object> ids = MetaMapper.saveAll("form_order", rows);

// 更新（部分更新）
MetaMapper.updateById("form_order", id, Map.of("status", 2));

// 多数据源：显式绑定池
MetaMapper.of("tenant-a").findPage("form_order", MetaQuery.all(), Pageable.of(0, 20));

// 命名语句走 client（id 就是 Mapper 文件里的语句 id）
MetaMapperClient client = MetaMapper.defaultClient();
client.selectList("form_order", "selectList", Map.of("status", 1));
client.selectOne("form_order", "byOrderNo", Map.of("order_no", "A001"));
client.selectCount("form_order", "countDraft", Map.of("status", 1));
client.execute("form_order", "voidByNo", Map.of("order_no", "A001"));
SqlBuild sql = client.sqlFor("form_order", "selectList", Map.of("status", 1)); // 只看文本，不执行
```

**没有 delete。** 应用侧不提供物理删除；作废走 `updateById(..., Map.of("status", "VOID"))`。
若表里有 `deleted_flag` / `del_flag` / `is_deleted`，查询自动追加 `= 0`（可配置关闭）。

## 元数据快照

- 首次访问某张表时从 `DatabaseMetaData` 读取列、主键、自增、可空、默认值，直接构造
  `MetaClass`（`name` = 表名、`namespace` = schema、`metaType = ENTITY`、`properties` = 列）；
- `MetaTable` 不可变，缓存键大小写不敏感；
- 建表/加列/删列**提交后**调 `client.refresh(table)` 或 `MetaMapper.refresh(table)`；
- 旧快照上正在执行的语句不受刷新影响，刷新只影响下一次解析。

## Mapper 文件与命名语句

规则只有一条：**文件存在就用文件，不存在才按表结构生成一次**。

1. 查找顺序：`<xml-directory>/<表名>.xml` → classpath `meta-mappers/<表名>.xml`；
2. 都没有且 `generate-xml=true`：按 `MetaTable` 的列结构生成一份，写到 `xml-directory` 后使用；
   目录为空串时只在内存里用、不落盘；
3. 生成文件带 `generated-by: autumn-data-meta-jdbc` 标记。**文件存在就直接使用，永不回头覆盖**：
   改表加列后 `refresh` 只是丢缓存重读文件，要按新结构重来就删文件手工重写。文件归使用方所有。

`client.refresh(table)` 与“重写用户文件”是两件事，故意不联动——否则用户改过的语句会被表结构冲掉。

### 谁读文件、谁不读

| 入口 | SQL 来源 | 说明 |
| --- | --- | --- |
| `findById` / `findList` / `findPage` / `count` / `save` / `updateById` … | `MetaSqlBuilder` 现拼 | 条件运算符是开集（`>` / `LIKE` / `IN` / `BETWEEN` / 排序…），固定文件表达不了，因此这条路**完全不读文件** |
| `sqlFor` / `selectList` / `selectOne` / `selectCount` / `execute` | Mapper 文件的命名语句 | 语句 id 就是 XML 里的 `id`；`<select>` 走查询，`<insert>`/`<update>` 走 `execute`，`<delete>` 拒绝执行 |

生成的文件本身就是一份可跑的样例语句（`selectList` / `selectCount` / `insert` / `updateByKey`），
逐列展开成 `<if>`；投影、排序、运算符想改就直接改这份文件。下面是生成内容的节选：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- generated-by: autumn-data-meta-jdbc -->
<mapper namespace="dynamic.form_order">
    <select id="selectList">
        SELECT "id", "order_no", "amount", "status", "create_time", "deleted_flag" FROM "form_order"
        <where>
            AND "deleted_flag" = 0
            <if test="id != null">AND "id" = #{id}</if>
            <if test="order_no != null">AND "order_no" = #{order_no}</if>
            <if test="status != null">AND "status" = #{status}</if>
        </where>
        ORDER BY "id"
    </select>
    <insert id="insert">
        INSERT INTO "form_order"
        <trim prefix="(" suffix=")" suffixOverrides=",">
            <if test="order_no != null">"order_no",</if>
            <if test="status != null">"status",</if>
        </trim>
        <trim prefix="VALUES (" suffix=")" suffixOverrides=",">
            <if test="order_no != null">#{order_no},</if>
            <if test="status != null">#{status},</if>
        </trim>
    </insert>
    <update id="updateByKey">
        UPDATE "form_order"
        <trim prefix="SET " suffixOverrides=",">
            <if test="status != null">"status" = #{status},</if>
        </trim>
        WHERE "id" = #{id}
    </update>
</mapper>
```

### 方言

文件内容就是静态实体用的那套 XML 方言（`autumn-data-common` 的 `TemplateParser`），同一段 XML
在编译期生成器和运行期解释器里产出同样的 SQL 文本。手写语句不受生成文件的逐列等值限制，
比较、区间、`IN` 用的还是同一套标签（标识符引号按方言，这里是双引号示例）：

```xml
<select id="draftOrders">
    SELECT ${table}."id", ${table}."order_no" FROM ${table}
    WHERE "deleted_flag" = 0
    <if test="minAmount != null">AND "amount" &gt; #{minAmount}</if>
    <if test="ids != null">AND "id" IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
    </if>
    ORDER BY "id" DESC
</select>
```

- `#{name}` → `?` + JDBC 绑定，**在解析阶段就被摘成参数节点**，值永远不进 SQL 文本；
- `${name}` → 标识符拼接（表名、列清单、排序方向），展开结果必须过标识符白名单，
  引号、分号、注释符、比较符一律拒绝；`${table}` 已由客户端按方言填好引号后的表名；
- `<if test>` / `<choose>/<when>/<otherwise>` / `<foreach collection item separator open close>` /
  `<where>` / `<trim prefix suffix prefixOverrides suffixOverrides>`；`<where>` 是 `<trim>` 的语法糖，
  整段为空时连 `WHERE` 带它的绑定一起退回；
- `test` 表达式既认 MyBatis 的 `name != null`、`name == 'active'`，也认 FreeMarker 表达式的
  `name??`、`name?has_content`、`name?size`、`!expr`、`a && b`。FreeMarker 的 `<#if>` 标签本身
  不是合法 XML（标签名不能以 `#` 开头），DOM 解析阶段就进不来，所以兼容只做在表达式层；
- 调用方传的参数键按元数据归一，`Map.of("ORDER_NO", ...)` 与 `order_no` 都能对上模板里的列名；
  值按列的 JDBC 类型收口（表单字符串 → Long/BigDecimal/LocalDateTime）；
- 名字按忽略大小写对齐：`#{}` / `test` 里写的列名、调用方传的键、Mapper 文件名三者都不要求
  和元数据的大小写一致。H2、Oracle 的元数据列名是大写，手写文件多按 MySQL 习惯写小写，
  对不上时 `#{order_no}` 会静默绑成 `null`——所以这里显式做成大小写不敏感。

```properties
autumn.data.meta.mapper.xml-directory=meta-mappers
autumn.data.meta.mapper.generate-xml=true
```

`xml-directory` 为空串时只生成到内存；`generate-xml=false` 时缺失文件直接抛
`MetaSqlException`，适合生产环境要求 Mapper 必须预置的场景。

### 上手示例

上层怎么调，看使用方模块里的两个文件，都是能直接跑的：

- `autumn-data-jdbc-spring-boot-starter-using/src/test/java/.../using/MetaMapperXmlUsingTest.java`
  按「CRUD 不碰文件 → 命名语句首次生成 → 手写文件优先 → 类型与 id 校验 → 注入边界」五段走一遍，
  Spring 只配了数据源，引擎由 starter 自动装配进 `MetaMapper`；
- `autumn-data-jdbc-spring-boot-starter-using/src/test/resources/meta-mappers/demo_order.xml`
  就是那份手写 Mapper 文件，`<if>/<where>/<trim>/<choose>/<foreach>` + `#{}` + `${}` 各有一条，
  注释里写了查找顺序和三条写法约定。

跑起来：`mvn -B test`（全 reactor）。想只跑这一类示例，先 `mvn -B install -DskipTests`
把编译期处理器装进本地仓库，再 `mvn -pl autumn-data-jdbc-spring-boot-starter-using test
-Dtest=MetaMapperXmlUsingTest`。生成的文件落在 `target/meta-mappers/`，删掉它就等于
「按新表结构重来」。

## 写入语义

| 方法 | 语义 |
| --- | --- |
| `save` | 只做 INSERT；自增主键回填；非自增主键必须由调用方提供；无主键也能写，但返回 `null` |
| `saveAll` | SQL 文本一致时走 JDBC batch；不一致（传的列不同）时退化为逐条 save |
| `updateById` / `updateByKey` | 部分更新：只 SET 传入的列；未传列保持原值；置空必须显式传 `MetaValue.NULL` |

主键列不允许出现在 UPDATE 的 SET 里；单主键用 `updateById`，复合主键用 `updateByKey`。

## 安全边界

- 表名/列名/排序字段只作为引用，拼 SQL 前必须命中 `MetaTable`，否则抛错；
- 所有值走 `?`，包括条件、分页前的谓词、批量参数；命名语句里的 `#{}` 在**解析阶段**就摘成
  参数节点，模板渲染只产出 `?`，不存在“先拼文本再补参数”的顺序；
- `${}` 只能展开成标识符片段（字母数字下划线 + 方言引用符 + 列表标点），带引号、分号、
  注释符、比较符的值直接抛错；
- 值按列 JDBC 类型显式转换（表单字符串 → Long/BigDecimal/LocalDateTime/Boolean 等），
  失败信息带表.列与期望类型；
- `findList` 默认最多 1000 行，避免动态查询误拉全表；需要更多显式传 limit；
- 动态层不执行 `<delete>`：`execute` 遇到删除语句直接拒绝。

## 多数据源：为什么不用 ThreadLocal 存 DataSource

推荐的是**显式绑定句柄**：`MetaMapper.of(pool)` 返回 `MetaMapperClient`，租户/库的选择发生在
请求边界（能拿到租户 ID 的地方），之后把 client 往下传。理由：

1. 异步/线程池/虚拟线程会丢 ThreadLocal，必须到处 `wrap`，与现有 `AutumnDataContextHolder`
   的透传问题重复一遍；
2. 嵌套切换池时容易和事务错位：事务绑在默认池，语句却跑到另一个池；
3. 出问题时堆栈里只剩 `MetaMapper.findList(...)`，看不出用的哪个库。

ThreadLocal 最多作为“请求级默认池”的便利糖，不能作为唯一机制。缓存也按 `(pool, table)` 维度
各自持有 Registry，不能共用一个全局表缓存。

## 事务现状与后续接入点

当前版本每次操作取连接、结束归还（`PerThreadConnectionSource` + `JdbcConnectionSource`），
单方法内部语句自提交；跨方法的 `@Transactional` 暂不生效。

后续接入事务只需要替换 `MetaMapperEngine` 的 client 工厂里的 `ConnectionSource`：
- Spring 默认池：`SpringConnectionSource`，交给 `DataSourceUtils`；
- 非默认池：按池取 `DataSource` 后各建一个事务管理器；
- 写入路径（save/update）本身已经共用同一连接，batch 也在同一操作内完成，不需要改 SQL 层。

## 代码与测试

- 实现：`autumn-data-meta-jdbc/src/main/java/xyz/wewin/autumn/data/meta/jdbc/dynamic`
  （`MetaStatementStore` 管文件生命周期，`SqlTemplate` 是运行期解释器，方言与编译期生成器
  共用 `autumn-data-common` 的 `TemplateParser` / `TemplateNode`）
- 用例：`MetaMapperDynamicTest`（建表 → 不重启 CRUD → 加列 refresh → 逻辑删除 → 复合主键 →
  Mapper 文件生成一次/改表不重写 → 手工文件优先 → 名字忽略大小写对齐 → 空值判断方法 →
  `#{}` 绑定与注入拒绝 → `${}` 白名单 → `<foreach>` / `<trim>` / `<delete>` 拒绝），共 18 个；
  starter 装配 2 个；using 模块端到端 1 个 + 上层用法示例 5 个。
- 全 reactor：`mvn -B test`，starter / using / compiler / common 全绿。
