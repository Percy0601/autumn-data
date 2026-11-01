# autumn-data 项目长期笔记

## 定位
`autumn-data`：零反射 JDBC 仓储（编译期 APT 生成实现）+ FreeMarker 动态 SQL 引擎（`autumn-data-meta`）。
对标 Spring Data 的接口写法 + Micronaut Data 的编译期生成。核心模块零 Spring 运行期依赖，Spring 只在 starter。

## 架构要点
- 编译期：`AutoRepositoryProcessor`（JSR-269）→ 生成 `<Repo>Impl.java` + `META-INF/autumn-data/sql/<Repo>.properties`。
- 运行时：生成类 `@Service` + 构造注入 `AutumnJdbc`（唯一协作者：JdbcClient/方言/FetchPolicy）；SQL 用 JDK Properties 加载，零反射。
- 关联：一对多=「父查询 + 按外键 IN 子查询 + 分组写回」，非 JOIN，受 `FetchPolicy.associationLimit` 截断。
- 方言：MySQL/PostgreSQL/H2/Default，`DialectResolver` 按 DataSource 元数据识别。

## 关键缺口（截至 2026-11-01）
- 派生查询（`findByNameAndAge` 等方法名→SQL）未实现，规范见 docs/spring-data-derived-query.md。
- 生成 SQL 未用 `Dialect.quoteIdentifier()`，保留字标识符有隐患。
- record 实体、多对多装配、@Query 自定义 SQL 待做。

## 协作归属（用户陈述）
方向/创意=用户；实现落地部分功劳=阿里千问。
