# autumn-data 项目长期笔记

## 定位
`autumn-data`：零反射 JDBC 仓储（编译期 APT 生成实现）+ FreeMarker 动态 SQL 引擎（`autumn-data-meta`）。
对标 Spring Data 的接口写法 + Micronaut Data 的编译期生成。核心模块零 Spring 运行期依赖，Spring 只在 starter。

## 架构要点（方案 B：完全去 Spring，MyBatis 式事务分层）
- 编译期：`AutoRepositoryProcessor`（JSR-269）→ 生成 `<Repo>Impl.java` + `META-INF/autumn-data/sql/<Repo>.properties` + 写入 `META-INF/autumn-data/repositories`（每行一个仓储接口 FQN）。
- 生成类**不再标 `@Service`**，只有一个接收核心 `AutumnJdbc` 的构造器。
- 核心层（`autumn-data-jdbc`，零 Spring 运行期依赖）自有 `ConnectionSource` 事务 SPI：
  - `JdbcConnectionSource`：原生 JDBC 自管连接/提交/回滚，无 Spring 时由 `Autumn.create(Repo.class, dataSource)` 引导（对标 MyBatis SqlSessionFactory）。
  - `AutumnJdbc`（运行时门面，只用 `java.sql`）：`query/queryOptional/queryLimited/page/count/update/insertReturningKey/batchUpdate/batchInsertReturningKeys/partition`；内部异常 `SqlException`。
  - 自有 `RowMapper<T>`（签名对齐 spring-jdbc，零依赖）。
- Starter（`autumn-data-jdbc-spring-boot-starter`）适配器：
  - `SpringConnectionSource`：`ConnectionSource` 实现，委托 `DataSourceUtils`，自动加入 `@Transactional`（对标 MyBatis `SpringManagedTransaction`）；容器关闭经 `DisposableBean.destroy()` 还连接。
  - `RepositoryRegistrar`（`BeanDefinitionRegistryPostProcessor`）：读 `META-INF/autumn-data/repositories` 把 `<Repo>Impl` 注册为 Spring Bean，构造器自动装配核心 `AutumnJdbc`。
  - `AutumnDataJdbcAutoConfiguration`：`@AutoConfiguration(after = DataSourceAutoConfiguration)` 提供 `Dialect`/`FetchPolicy`/`ConnectionSource`/`AutumnJdbc`/`RepositoryRegistrar`；只依赖 `spring-boot-starter-jdbc`（无需 JdbcClient/JdbcTemplate）。
- 关联：一对多=「父查询 + 按外键 IN 子查询 + 分组写回」，非 JOIN，受 `FetchPolicy.associationLimit` 截断。
- 方言：MySQL/PostgreSQL/H2/Default，`DialectResolver` 按 DataSource 元数据识别。

## 关键缺口（截至 2026-11-01）
- 派生查询已实现（`DerivedQueryParser`，编译期解析 + 内联 SQL），覆盖 6 个查询动词、`count`/`exists`、关键词表、`And`/`Or`/`OrderBy`、一层关联条件（`findByOrdersOrderNo` → `EXISTS` 相关子查询）；解析失败在接口方法声明处报错。仍未做：`Distinct`、`First`/`Top`、`deleteBy`、`@ManyToOne` 取子表列进 SELECT（需 JOIN）、两层以上关联路径。
- 生成 SQL 未用 `Dialect.quoteIdentifier()`，保留字标识符有隐患。
- record 实体、多对多装配、@Query 自定义 SQL 待做。

## 设计原则（用户 2026-11-01 明确指导）
**尽量把逻辑下沉到编译期（代码生成），让运行时尽可能精简。** 具体应用：SQL 不再落盘为 `.properties` 运行时加载，而是编译期直接内联成生成类里的 `private static final String` 常量；运行时只保留一个极小的 `Sql.inClause`（把 `IN (:ids)` 按运行期参数个数展开成 `?`）。后续凡是能编译期决定的都优先编译期做（方言分页若要更瘦可改为编译期指定方言）。

**代码生成方式建议（用户 2026-11-01 建议，非强制）**：compiler 当前用 `StringBuilder` 拼装生成源码；若拼装变复杂、难维护，可引入 **FreeMarker 模板引擎**做代码生成（项目 `autumn-data-meta` 已用 FreeMarker 做动态 SQL，属既有的同族能力）。当前为保持 compiler 零额外依赖仍用 StringBuilder，待生成体膨胀再评估切模板。

## 协作归属（用户陈述）
方向/创意=用户；实现落地部分功劳=阿里千问。
