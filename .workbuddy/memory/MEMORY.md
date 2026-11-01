# autumn-data 项目长期笔记

## 定位
`autumn-data`：零反射 JDBC 仓储（编译期 APT 生成实现）+ FreeMarker 动态 SQL 引擎（`autumn-data-meta`）。
对标 Spring Data 的接口写法 + Micronaut Data 的编译期生成。核心模块零 Spring 运行期依赖，Spring 只在 starter。

## 架构要点（方案 B：完全去 Spring，MyBatis 式事务分层）
- 编译期：`AutoRepositoryProcessor`（JSR-269）→ 生成 `<Repo>Impl.java`（SQL 内联为 `private static final String` 常量）+ 写入 `META-INF/autumn-data/repositories`（每行一个仓储接口 FQN）。
- 生成类**不再标 `@Service`**，只有一个接收核心 `AutumnJdbc` 的构造器。
- 核心层（`autumn-data-jdbc`，零 Spring 运行期依赖）自有 `ConnectionSource` 事务 SPI：
  - `JdbcConnectionSource`：原生 JDBC 自管连接/提交/回滚，无 Spring 时由 `Autumn.create(Repo.class, dataSource)` 引导（对标 MyBatis SqlSessionFactory）。
  - `AutumnJdbc`（运行时门面，只用 `java.sql`）：`query/queryOptional/queryLimited/page/count/update/insertReturningKey/batchUpdate/batchInsertReturningKeys/partition`；内部异常 `SqlException`。
  - 自有 `RowMapper<T>`（签名对齐 spring-jdbc，零依赖）。
- Starter（`autumn-data-jdbc-spring-boot-starter`）适配器：
  - `SpringConnectionSource`：`ConnectionSource` 实现，委托 `DataSourceUtils`，自动加入 `@Transactional`（对标 MyBatis `SpringManagedTransaction`）；容器关闭经 `DisposableBean.destroy()` 还连接。
  - `RepositoryRegistrar`（`BeanDefinitionRegistryPostProcessor`）：读 `META-INF/autumn-data/repositories` 把 `<Repo>Impl` 注册为 Spring Bean，构造器自动装配核心 `AutumnJdbc`。
  - `AutumnDataJdbcAutoConfiguration`：`@AutoConfiguration(after = DataSourceAutoConfiguration)` 提供 `Dialect`/`FetchPolicy`/`ConnectionSource`/`AutumnJdbc`/`RepositoryRegistrar`；只依赖 `spring-boot-starter-jdbc`（无需 JdbcClient/JdbcTemplate）。
- 关联（均为「两条语句」、非 JOIN）：
  一对多=「父查询 + 按父主键 IN 子表外键 + 分组写回集合」；
  多对一=「父查询 + 按父表外键 IN 子表主键 + 按子表主键挂回单值字段」。
  一对多受 `FetchPolicy.associationLimit` 截断（走 `queryLimited`），多对一走 `query`。
- 方言：MySQL/PostgreSQL/H2/Default，`DialectResolver` 按 DataSource 元数据识别。

## 已补齐的能力（2026-11-01 完成：A 关键词收口 / B 形态修饰 / C 三件）
- **派生查询形态修饰**：`Distinct`（投影加 DISTINCT）、`First`/`Top N`（编译期解析数字 → 运行期方言拼 LIMIT，
  `AutumnJdbc` 新增 `query(long, sql, mapper, args)` / `queryOptional(long, …)`，limit 放首位避免 varargs 歧义）、
  `deleteBy`/`removeBy`（返回 `long` 走 update 计数；返回 `List<T>` 先 SELECT 取回再 DELETE，共用同一 WHERE）。
- **`@Query` 自定义 SQL**（新注解 `xyz.wewin.autumn.data.jdbc.annotation.Query`，`@Retention(CLASS)`）：
  方法上写了它就不解析方法名，SQL 原样采用，参数按声明顺序绑定，且不触发关联装配。
- **`@ManyToOne` 加载**（两条语句、非 JOIN）：父查询后用父表外键值 `IN` 子表主键取回子实体，按子表主键建索引挂回单值字段。
  契约：父表上必须有一个映射该外键列的标量字段（供读外键，与一对多「子表暴露外键列」对称）；
  仅根实体（depth 0）强求该字段 —— 子实体的多对一只用于派生查询路径，不生成装配。
- **多层关联路径**：`resolvePath` 递归下钻，`Resolved.wrap` 按每层关联嵌套 `EXISTS`；
  连接方向按形态分：一对多 `child.fk = parent.id`，多对一 `child.id = parent.fk`。
  下钻深度取决于子实体是否声明关联（`MAX_ASSOCIATION_DEPTH = 2`），`introspectChild` 用 visiting 集合防环。
- **标识符引号（C3）**：`EntityMetadata.quoted(meta, dialect)` 造「引号视图」喂给 SQL 生成器，
  实现类生成仍用原元数据（结果集标签不受引号影响）。由编译期选项开启
  `-Aautumn.data.jdbc.dialect=mysql|postgresql|h2|default`，**默认不加引号**（保既有 schema/断言）。

## 关键缺口（截至 2026-11-01）
- 派生查询主体已实现：`Distinct`、`First`/`Top`、`deleteBy`、关键词表、`IgnoreCase`、`Like` 家族、
  `@Query`、一层与多层关联条件、多对一加载均已落地；解析失败仍在接口方法声明处报错。
- 仍未做：record 实体、多对多装配（`@ManyToMany` 目前只给 WARNING）。
- 注意：child 实体的 `@ManyToOne` 回指（如 `Order.owner`）原先只在 WARNING 就被跳过，
  现在因为要支持多层路径会真的解析它 —— 已放宽为「depth>0 不强求外键标量字段」，改动这块要留意。

## 设计原则（用户 2026-11-01 明确指导）
**尽量把逻辑下沉到编译期（代码生成），让运行时尽可能精简。** 具体应用：SQL 不再落盘为 `.properties` 运行时加载，而是编译期直接内联成生成类里的 `private static final String` 常量；运行时只保留一个极小的 `Sql.inClause`（把 `IN (:ids)` 按运行期参数个数展开成 `?`）。后续凡是能编译期决定的都优先编译期做（方言分页若要更瘦可改为编译期指定方言）。

**代码生成方式建议（用户 2026-11-01 建议，非强制）**：compiler 当前用 `StringBuilder` 拼装生成源码；若拼装变复杂、难维护，可引入 **FreeMarker 模板引擎**做代码生成（项目 `autumn-data-meta` 已用 FreeMarker 做动态 SQL，属既有的同族能力）。当前为保持 compiler 零额外依赖仍用 StringBuilder，待生成体膨胀再评估切模板。

## 协作归属（用户陈述）
方向/创意=用户；实现落地部分功劳=阿里千问。
