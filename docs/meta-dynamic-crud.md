# 动态表 CRUD（autumn-data-meta-jdbc）

面向运行期设计出来的单据 / 基础资料：**低代码平台的底层基座**。表结构在运行期变化，服务不重启、
工程不重新编译，就能按表名做 CRUD。实体统一用 `autumn-data-common` 的
[`MetaClass`](../autumn-data-common/src/main/java/xyz/wewin/autumn/data/common/meta/MetaClass.java)：
每行一份属性列表，每个属性带 Java 类型 / JDBC 类型 / 主键 / 自增信息，读写都不用反射、不生成类。

两条线职责分明：`autumn-data-jdbc` + `autumn-data-jdbc-compiler` 是**编译期生成实现、完全静态**的仓储；
本模块是**完全动态、轻量、弹性懒加载**的另一条路径，只共用 common 的方言 / XML AST / `SqlBuild`。
硬约束：**重活推迟到首次访问**、**所有按表 / 按池增长的容器有界且 LRU**、**表结构变化可热更新**。

## 目录约定

```
{root}/
  {数据源}-default/{命名空间}-default/
    spec/
      {表名}.json        实体类定义（使用方资产；框架只读，缺失时可按库结构生成模板）
      xml/{表名}.xml      手写 SQL（使用方资产；框架只读、绝不覆盖）
      vo/                 使用方自建 VO 的约定位置（框架不读写）
    auto/
      xml/{表名}.xml      七条默认 SQL（框架资产；结构指纹变化整份重写）
      entity/             保留目录，统一 MetaClass 后不再生成实体源码
```

- 默认 `root = meta-data`，也是 classpath 资源根名；磁盘没有就找
  `classpath:meta-data/{数据源}-default/{命名空间}-default/spec/xml/{表名}.xml`。
- 数据源 / 命名空间目录固定带 `-default` 后缀（`default` → `default-default`），已带的不会重复加。
- `spec` 归使用方，`auto` 归框架；**SQL 同 id 时 spec 覆盖 auto**，因此可以换掉任何一条默认语句。
- `MetaMapper.of(池, 命名空间)` 把两层边界固定在客户端句柄上；`of("tenant-a", "erp")` 对应
  `{root}/tenant-a-default/erp-default/`。

## 实体：一份 JSON 就是一个类定义

定义文件放在 `spec/{表名}.json`。完整可复制示例：
[`docs/examples/meta-data/example-default/example-default/spec/DEMO_ORDER.json`](examples/meta-data/example-default/example-default/spec/DEMO_ORDER.json)。

```json
{
  "name" : "DEMO_ORDER",
  "namespace" : "PUBLIC",
  "metaType" : "ENTITY",
  "logicDeleteColumn" : null,
  "comment" : "按表结构生成一次；定义优先，只写你想改的键，其余以数据库为准",
  "properties" : [
    { "name" : "ID", "type" : "java.lang.Long", "jdbcType" : -5, "sqlTypeName" : "BIGINT", "nullable" : false, "primaryKey" : true, "autoIncrement" : true, "defaultValue" : null },
    { "name" : "AMOUNT", "type" : "java.math.BigDecimal", "jdbcType" : 3, "sqlTypeName" : "DECIMAL", "nullable" : true, "primaryKey" : false, "autoIncrement" : false, "defaultValue" : null }
  ]
}
```

语义：

| 键 | 作用 |
| --- | --- |
| `name` | 类名 = 物理表名；与表名不一致时告警并以库里的表名为准，避免 SQL 指错表 |
| `namespace` | 落到 `MetaClass.namespace`（schema），参与结构指纹 |
| `metaType` | `ENTITY` / `VO` / `DTO`，默认 `ENTITY` |
| `logicDeleteColumn` | 指定逻辑删除列；为空时沿用自动探测 / 全局配置 |
| `comment` | 给人看的说明，框架忽略 |
| `properties[].type` | 字段 Java 类型全限定名；**读行时按它收口值**，所以 `property("AMOUNT").getType()` 与实际值一致 |
| `properties[].jdbcType` | JDBC 类型码（数字），绑定 / 校验用 |
| 其余属性键 | `typeName` / `sqlTypeName` / `nullable` / `primaryKey` / `autoIncrement` / `defaultValue` |

合并规则：**定义里写了的键以定义为准，没写的键从 `DatabaseMetaData` 补齐**；定义里声明但库里没有的列
会被忽略并告警。文件按时长 / 大小缓存，改完下一次访问自动生效。定义文件按「数据源池的 default 命名空间」
读取，同一池的其它命名空间视图共享同一份实体定义（SQL 则按各自命名空间独立寻址）。

读出来的行是普通 `MetaClass`，但类型描述完整：

```java
MetaClass row = MetaMapper.findById("demo_order", 1L).orElseThrow();
row.getBigDecimal("amount");                       // 类型化读取
row.property("amount").getType();                  // java.math.BigDecimal
row.getProperties().stream().map(MetaProperty::getName).toList();
```

`MetaMapper.entityType(table)` 恒返回 `MetaClass.class`；没有 javac、没有 `defineClass`、没有反射代理，
Native Image / JRE 环境同样可跑。

## SQL：spec 手写 + auto 生成

完整可复制示例（覆盖 `<sql>/<include>`、`<resultMap>`、`<if>/<where>/<trim>/<foreach>/<choose>`、
`${table}`、`#{}`、`changed('col')`、默认语句覆盖）：
[`docs/examples/meta-data/.../spec/xml/DEMO_ORDER.xml`](examples/meta-data/example-default/example-default/spec/xml/DEMO_ORDER.xml)。

`auto/xml/{表名}.xml` 是框架生成的完整文档，文件头带指纹：

```xml
<!-- AUTUMN-DATA GENERATED fingerprint=4-xxxx -->
<mapper namespace="default">
    <sql id="__autumn_columns">...</sql>
    <sql id="__autumn_where">...<where>...</where></sql>
    <select id="findList">...</select>
    <select id="findPage">...</select>
    <select id="findOne">...</select>
    <select id="findById">...</select>
    <insert id="save">...<if test="changed('col')">...</if>...</insert>
    <update id="update">...</update>
    <update id="delete">...</update>   <!-- 有逻辑删除列时是 UPDATE，否则 DELETE -->
</mapper>
```

- 七条默认语句固定为 `findList / findPage / findOne / findById / save / update / delete`，
  核心 CRUD 也读这七条语句，不在 Java 侧另拼 SQL。
- 指纹 = 生成器版本 + 表结构哈希（列顺序、名字、类型、主键、自增、可空）。指纹变了 **auto 整份重写**，
  没有生成块合并逻辑；使用方不要改 auto 文件。
- spec 文件框架只读。同 id 时 spec 覆盖 auto，因此可以在 spec 里写一条 `<select id="findOne">`
  换掉默认语义，或写 `<delete id="delete">` 决定逻辑删 / 物理删。
- 只读 classpath / 纯内存模式（`generate-files=false`）下 auto 文件不落盘，默认语句在内存兜底，
  默认语句照样可用。

SQL 方言与静态线完全共用 `autumn-data-common` 的 XML AST：`#{}` 解析期摘成 JDBC 绑定（值不进 SQL 文本），
`${}` 只做标识符拼接且过白名单，`${table}` 由框架按方言填好表名。`test` 只认 MyBatis 写法
（`a != null` / `a == 'x'` / `!a` / `a && b`），另有内置谓词 `changed('col')` 读实体变更集，
区分「这一列没动」和「显式置 NULL」。`<resultMap>` 在解析期展开成「结果列 → 属性名」映射。

## API

```java
// 启动 / 绑定
MetaMapper.install(MetaMapperEngine.forDataSource("default", dataSource));
MetaMapperClient client = MetaMapper.of("tenant-a", "erp");   // 池 + 命名空间

// 查询：返回通用 MetaClass
MetaClass row = MetaMapper.findById("form_order", 1L).orElseThrow();
MetaClass query = new MetaClass();
query.set("status", 1);
MetaMapper.findOne("form_order", query);
MetaMapper.findList("form_order", query);
Page<MetaClass> page = MetaMapper.findPage("form_order", query, Pageable.of(0, 20));
long total = page.total();                                   // 总数就是分页的 total

// 写入：只写赋过值 / 改过的列
MetaClass order = MetaMapper.newEntity("form_order");
order.set("order_no", "A001");
order.set("amount", "12.50");
MetaMapper.save(order);                                      // 自增主键回填并清脏
MetaMapper.saveAll(List.of(order, MetaMapper.newEntity("form_order")));
order.set("status", 2);
MetaMapper.update(order);                                    // 部分更新，写 null 才是置空
MetaMapper.delete(order);                                    // 执行 XML 里的 delete 语句

// 自定义语句
client.selectList("form_order", "search", query);
client.selectOne("form_order", "byOrderNo", query);
client.selectCount("form_order", "countMatching", query);
client.selectPage("form_order", "search", query, Pageable.of(0, 20));
client.execute("form_order", "patch", query);
MarkedSql sql = client.sqlFor("form_order", "search", query); // 只看文本与绑定值
client.statementIds("form_order");                            // 默认 + spec 自定义
client.ensureStatementFile("form_order");                     // 显式生成 / 刷新 auto 文件

// 缓存
MetaMapper.refresh("form_order");                    // 表结构 + 模型 + 语句 + 计划一起清
MetaMapper.cacheStats();
client.clearTableCache("form_order");                // 只清表结构快照
client.clearEntityCache("form_order");               // 只清实体模型读取器
client.clearStatementCache("form_order");            // 只清语句 AST 与执行计划
MetaClass rebuilt = client.reload("form_order");     // 清完立即回读元数据
```

静态门面只保留常用路径：`findById / findOne / findList / findPage`、`save / saveAll / update / delete`、
`selectList / selectOne / selectCount / selectPage / execute / sqlFor`，加上
`install / of / engine / newEntity / entityType / statementIds / ensureStatementFile / refresh / cacheStats`。
限行查询 `findList(table, params, limit)` 只在 client 上。

## 有界缓存

统一走 `MetaCache` 接口（API 对齐 Caffeine），实现 `LruMetaCache`，工厂 `MetaCaches`，JDK-only：

| 缓存 | key | 上限配置 |
| --- | --- | --- |
| `MetaTableRegistry` 表快照 / 手工覆盖 | 表名 | `table-cache-size`（10000） |
| `MetaEntityRegistry` 实体模型 | 表名 + 结构指纹 | `table-cache-size` |
| `MetaModelStore` JSON 定义 | 文件 + 时间戳 | `table-cache-size` |
| `MetaSqlFileStore` auto / spec / 默认模板 | 数据源 / 命名空间 / 表、结构指纹 | `statement-cache-size`（1000） |
| `MetaPlanCache` 执行计划 | 形状指纹 + SQL 文本 | `table-cache-size` |
| `MetaMapperEngine` 池客户端 | 池名 | `table-cache-size` |

`refresh` 与在途加载并发时，失效会一并摘掉该 key 的加载占位，慢加载结束后不会把旧结构写回缓存。

## 配置

```properties
autumn.data.meta.mapper.meta-directory=meta-data          # 空串 = 纯内存，不落盘
autumn.data.meta.mapper.generate-files=true               # 是否落盘（默认语句在内存始终可用）
autumn.data.meta.mapper.table-cache-size=10000
autumn.data.meta.mapper.statement-cache-size=1000
autumn.data.meta.mapper.default-list-limit=1000
autumn.data.meta.mapper.logic-delete-columns=deleted_flag,del_flag,is_deleted
```

Spring Boot 集成在 `autumn-data-jdbc-spring-boot-starter`：`SpringConnectionSource` 让动态层加入
`@Transactional`，连接池由 Boot 的 `DataSource` 提供。

## 示例与验证

- 完整 JSON / XML 示例：`docs/examples/meta-data/`，同内容复制到
  `autumn-data-meta-jdbc/src/test/resources/meta-data/`，由 `MetaExampleDefinitionTest` 用真实 H2 表跑通；
- 上层用法：`autumn-data-jdbc-spring-boot-starter-using` 的 `MetaMapperXmlUsingTest`（spec / auto 分工、
  手写文件不改写、结构变化只重写 auto、classpath 手写语句、删除语义、注入边界）与
  `MetaDataControllerTest`（HTTP 端到端）；
- 工作目录产物：`target/autumn-meta/{数据源}-default/{命名空间}-default/{spec,auto}/`。

跑法：`mvn -B clean test`。
