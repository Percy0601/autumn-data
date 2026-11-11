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
            ├─ MetaMapperXmlStore（表名 → Mapper XML；已有则读、缺失则用 FreeMarker 生成）
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
MetaMapper.findPage("form_order", MetaQuery.by("status", 1), Pageable.of(0, 20));
MetaMapper.count(...); MetaMapper.exists(...);

// 保存（只 INSERT）
Object id = MetaMapper.save("form_order", Map.of("order_no", "A001", "amount", "12.50"));
List<Object> ids = MetaMapper.saveAll("form_order", rows);

// 更新（部分更新）
MetaMapper.updateById("form_order", id, Map.of("status", 2));

// 多数据源：显式绑定池
MetaMapper.of("tenant-a").findPage("form_order", MetaQuery.all(), Pageable.of(0, 20));
```

**没有 delete。** 应用侧不提供物理删除；作废走 `updateById(..., Map.of("status", "VOID"))`。
若表里有 `deleted_flag` / `del_flag` / `is_deleted`，查询自动追加 `= 0`（可配置关闭）。

## 元数据快照

- 首次访问某张表时从 `DatabaseMetaData` 读取列、主键、自增、可空、默认值，直接构造
  `MetaClass`（`name` = 表名、`namespace` = schema、`metaType = ENTITY`、`properties` = 列）；
- `MetaTable` 不可变，缓存键大小写不敏感；
- 建表/加列/删列**提交后**调 `client.refresh(table)` 或 `MetaMapper.refresh(table)`；
- 旧快照上正在执行的语句不受刷新影响，刷新只影响下一次解析。

## Mapper XML（FreeMarker）

规则只有一条：**XML 存在就不生成，不存在才生成**。

1. 查找顺序：`<xml-directory>/<表名>.xml` → classpath `meta-mappers/<表名>.xml`；
2. 都没有且 `generate-xml=true`：用 `meta-mapper.xml.ftl` 按 `MetaTable` 生成，写到
   `xml-directory` 后使用；目录不可写时退化为只放内存；
3. 生成文件带 `generated-by: autumn-data-meta-jdbc` 和表结构 `fingerprint`（仅用于识别）；
   **文件存在就直接使用，不再生成**。加列后 `refresh` 依然可用，因为列清单/SET/WHERE 都是
   运行期按新元数据重算的片段；要按新结构重写生成文件，删除文件即可。手工文件（无该标记）
   不会被覆盖。

生成的文件是 SQL 骨架，语句 id 固定：

```xml
<mapper namespace="dynamic.form_order">
    <select id="findList"><![CDATA[
SELECT ${columnsSql} FROM ${table}${whereSql}${orderSql}
]]></select>
    <select id="count"><![CDATA[
SELECT COUNT(*) FROM ${table}${whereSql}
]]></select>
    <insert id="insert"><![CDATA[
INSERT INTO ${table} (${insertColumnsSql}) VALUES (${insertValuesSql})
]]></insert>
    <update id="update"><![CDATA[
UPDATE ${table} SET ${setSql} WHERE ${keyWhereSql}
]]></update>
</mapper>
```

运行期由 FreeMarker 渲染 `${...}`；变量值由 `MetaSqlBuilder` 的片段方法提供：
`table`、`columnsSql` 已按方言加引号，`whereSql` / `orderSql` / `setSql` / `keyWhereSql`
只含元数据里的真实列名和 `?`，值不在 SQL 文本里。这样 XML 可以改 SQL 外形（如加
`RETURNING`、改投影），但列/条件/值仍由元数据与参数绑定兜底。

XML 体就是 FreeMarker 模板，需要条件拼装时可直接用 FreeMarker 指令（`<#if>` / `<#list>`），
对应 MyBatis 的动态 SQL 标签：

```xml
<select id="findList"><![CDATA[
SELECT ${columnsSql} FROM ${table}
<#if whereSql?has_content>${whereSql}</#if>${orderSql}
]]></select>
```

自定义 XML 只允许引用上述片段变量；不要在这里拼用户输入，值一律走 `?`。

```properties
autumn.data.meta.mapper.xml-directory=meta-mappers
autumn.data.meta.mapper.generate-xml=true
```

`xml-directory` 为空串时只生成到内存；`generate-xml=false` 时缺失 XML 直接抛
`MetaSqlException`，适合生产环境要求 Mapper 必须预置的场景。

## 写入语义

| 方法 | 语义 |
| --- | --- |
| `save` | 只做 INSERT；自增主键回填；非自增主键必须由调用方提供；无主键也能写，但返回 `null` |
| `saveAll` | 列集合一致时走 JDBC batch；不一致时退化为逐条 save |
| `updateById` / `updateByKey` | 部分更新：只 SET 传入的列；未传列保持原值；置空必须显式传 `MetaValue.NULL` |

主键列不允许出现在 UPDATE 的 SET 里；单主键用 `updateById`，复合主键用 `updateByKey`。

## 安全边界

- 表名/列名/排序字段只作为引用，拼 SQL 前必须命中 `MetaTable`，否则抛错；
- 所有值走 `?`，包括条件、分页前的谓词、批量参数；
- 值按列 JDBC 类型显式转换（表单字符串 → Long/BigDecimal/LocalDateTime/Boolean 等），
  失败信息带表.列与期望类型；
- `findList` 默认最多 1000 行，避免动态查询误拉全表；需要更多显式传 limit。

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
- FreeMarker 生成模板：`autumn-data-meta-jdbc/src/main/resources/xyz/wewin/autumn/data/meta/jdbc/dynamic/meta-mapper.xml.ftl`
- 用例：`MetaMapperDynamicTest`（建表 → 不重启 CRUD → 加列 refresh → 逻辑删除 → 复合主键 →
  XML 首次生成/复用 → 手工 XML 不被覆盖），共 12 个；starter 装配 2 个；using 模块端到端 1 个。
- 全 reactor：`mvn -B test`，starter / using / compiler / common 全绿。
