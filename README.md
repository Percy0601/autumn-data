# autumn-data

Autumn 数据访问层，参考 `autumn-gateway` 的多模块 Maven 工程结构（最外层为 `pom` 聚合工程，兼容 Maven 4.x）。

## 模块规划

| 模块 | 定位 | 关键特性 |
| --- | --- | --- |
| `autumn-data-common` | JDBC 公共底座 | 数据库方言抽象（`Dialect` / `DialectResolver` / ANSI / MySQL / PostgreSQL / H2 实现）与通用 JDBC 基础设施；`MetaDataSource` 多数据源注册表（懒加载 + 空闲池轮询回收，面向多连接池场景）；`GlobalScheduler` 全局唯一定时任务线程池；核心零 Spring，被 `autumn-data-jdbc` 与 `autumn-data-meta-jdbc` 复用。 |
| `autumn-data-jdbc` | 零反射 JDBC 仓储 | 采用 **Spring Data 注解风格**（`Repository` / `CrudRepository` / `@Query` / `@Param`），并参考 **Micronaut Data** 在**编译期**通过注解处理器生成实现类，运行时零反射。核心**不依赖 Spring**，数据源连接池使用 **HikariCP**。 |
| `autumn-data-jdbc-spring-boot-starter` | Spring Boot 集成层（唯一使用方入口） | 约定大于配置：只引这一个依赖 + `spring.datasource.*` 即可用。仿 MyBatis 分层（引擎零 Spring / 集成层依赖 Boot），自动配置 `Dialect` Bean，支持 `autumn.data.jdbc.dialect` 显式指定或按 `DataSource` 元数据自动识别。 |
| `autumn-data-jdbc-compiler` | JSR-269 注解处理器 | 编译期为 `@AutumnRepository` 接口生成 `<名称>Impl`；仅 `annotationProcessorPaths` 使用，**运行时不依赖**。已实现：实体元数据提取（`@Entity`/`@Table`/`@Id`/`@Column`/`@GeneratedValue`/`@OneToMany`）、CRUD + 分页 + 一对多 + 方法名派生查询 SQL **编译期内联为生成类常量**、`RowMapper` 与批量读写/装配代码生成。解析不了的方法在**接口方法声明处**报错，不静默生成半成品。 |
| `autumn-data-jdbc-spring-boot-starter-using` | 使用方示例（不发布） | **使用驱动开发的主战场**：只引 starter + 挂 compiler 处理器，在这里写后续所有用法（单表、一对多、多对一、派生查询），以用法倒逼核心实现。 |
| `autumn-data-meta-jdbc` | 轻量动态 SQL 引擎 | 对标 **MyBatis** 但更轻量，支持**运行时动态加载**；用 **FreeMarker（.ftl）** 语法替代 MyBatis 的 XML SQL。数据源连接池同样使用 **HikariCP**；方言复用 `autumn-data-common`。 |

各数据访问模块共享 `autumn-data-common` 的多数据库方言（`Dialect`）抽象，至少支持 **MySQL** 与 **PostgreSQL**，并可通过 `DialectResolver` 根据 `DataSource` 的元数据自动识别。

## 当前进度（初始化阶段）

- [x] 根 POM / `.gitignore` / `.gitattributes` / Maven Wrapper
- [x] `autumn-data-jdbc` 模块骨架 + 多数据库 `Dialect`
- [x] 新增 `autumn-data-common`：收编 jdbc / meta 两套重复 `Dialect` 为公共底座，各模块改为依赖它
- [x] `autumn-data-meta-jdbc` 模块骨架 + FreeMarker 配置（原 `autumn-data-meta`，方言已收编至 `autumn-data-common`）
- [x] `autumn-data-common` 新增 `MetaDataSource`：多连接池注册表（`name → DataSource`，懒加载 + 多池模式 3 分钟轮询回收空闲 Hikari 池 + 默认池保护）
- [x] `autumn-data-common` 新增 `GlobalScheduler`：框架内**唯一**的定时任务线程池（懒启动的单线程守护调度器，任务抛异常只记日志、不静默停止排期）；`MetaDataSource` 的空闲池轮询改为挂在其上，组件「停止」只 `cancel` 自己的 future，不再各自 `Executors.newXxx` 建池
- [x] Spring Data 派生查询（方法名约定）规范文档：[docs/spring-data-derived-query.md](docs/spring-data-derived-query.md)
- [x] 依赖分层实证对照（MyBatis / Spring Data / starter-jdbc）：[docs/dependency-layering.md](docs/dependency-layering.md)
- [x] 依赖精简：`autumn-data-jdbc` 去掉 `spring-boot-starter-jdbc`，只留 `HikariCP` + `slf4j-api` + `jakarta.persistence-api`
- [x] 新增 `autumn-data-jdbc-spring-boot-starter`（`Dialect` 自动配置 + `autumn.data.jdbc.*` 配置项 + 冒烟测试）
- [x] 新增 `autumn-data-jdbc-spring-boot-starter-using`（使用方示例：只引 starter，H2 集成测试验证开箱体验；不发布）
- [x] 新增 `autumn-data-jdbc-compiler`（JSR-269：`@AutumnRepository` 约定校验 + 实现类生成）
- [x] compiler：实体元数据提取（`@Entity`/`@Table`/`@Id`/`@Column`/`@GeneratedValue`/`@OneToMany`）→ CRUD SQL + `RowMapper` 生成，SQL 编译期内联为生成类 `private static final String` 常量（运行期零资源加载、零解析）
- [x] compiler：分页（`PagingRepository.findAll(Pageable)` + 方言 limit/offset + 总数回查）、批量读写（`saveAll` 分块 + 生成键回填）、一对多两条语句批量装配（父查询 + 子表 `IN` 查询）
- [x] starter：`AutumnJdbc` 运行期门面 + `autumn.data.jdbc.association-limit`（默认 1000）/ `batch-size`（默认 100）配置项
- [x] 事务验证：生成实现走 `DataSourceUtils` 取连接，`@Transactional` 下提交与异常回滚均已实测
- [x] 仓储清单（`META-INF/autumn-data/repositories`）改为**合并写入**：增量编译只重编改动的文件时不再把其他仓储从清单里抹掉（该缺陷的表现是启动报 `required a bean of type 'X' that could not be found`，而实现类 .class 明明在）；`RepositoryRegistrar` 同时跳过清单里指向已删除仓储的残留行并告警
- [x] 派生查询解析器（`DerivedQueryParser`，按 docs/spring-data-derived-query.md 第 1~6 节）：`find/read/get/query/search` + `count` + `exists`、关键词表、`And`/`Or`/`OrderBy`、`List`/`Optional` 返回；SQL 同样编译期内联
- [x] 派生查询支持一层关联条件：`findByOrdersOrderNo` 编译期展开成 `EXISTS` 相关子查询（半连接，父行不重复、不影响一对多装配），外键列取自 `@JoinColumn` 或 `mappedBy`
- [x] compiler：解析不了的接口方法（未知属性/关键词、参数或返回类型不符、`Distinct`/`First`/`deleteBy`/两层以上关联）在**方法声明处**报错，不再静默跳过后由 javac 报「实现类未覆盖…」
- [x] 一次性取数上下文（`AutumnDataContextHolder`，按线程保存 + `wrap` 线程池透传）：分页关联加载三态（`DEFAULT` 阈值级联 / `CASCADE` 强制级联 / `DISABLED` 强制关闭）与**字段投影**（`selectFields` / `excludeFields` 运行期真裁剪 `SELECT` 列清单，主键与多对一外键编译期判定为不可省略，级联不被裁剪打断）；被最近一次取实体行的查询消费后自动清除，无需手动 `clear`
- [ ] `OrderBy` 之外的排序入参（`findAllBy(Sort)`）与 `Pageable` 排序
- [ ] `autumn-data-meta-jdbc` 同步去 Spring 依赖（同样挪到各自 starter）
- [ ] `autumn-data-meta-jdbc`：FreeMarker 动态 Mapper 执行引擎

## 依赖策略

核心模块只依赖 JDK + HikariCP（+ 编译期注解），不透传 Spring 运行时库；Spring Boot 集成放在 `autumn-data-jdbc-spring-boot-starter` 里，**使用方只依赖这一个 starter 即可（约定大于配置）**：

```xml
<dependency>
    <groupId>xyz.wewin.autumn</groupId>
    <artifactId>autumn-data-jdbc-spring-boot-starter</artifactId>
</dependency>
```

只配 `spring.datasource.*` 开箱得到：HikariCP `DataSource`、`JdbcTemplate`/`JdbcClient`、`PlatformTransactionManager`（Boot 自带自动配置）+ autumn 的 `Dialect` Bean（按数据库元数据自动识别；`autumn.data.jdbc.dialect=mysql|postgresql|h2|default|className:<fqcn>` 可覆盖，自定义 `Dialect` Bean 可顶替，`autumn.data.jdbc.enabled=false` 整体关闭）。后续仓储实现（APT 生成）的注册同样收进这个 starter，核心模块保持零 Spring。

| 模块 | 运行时依赖 | 编译期依赖 | test |
| --- | --- | --- | --- |
| `autumn-data-common` | 纯 JDK（`java.sql` / `javax.sql`）+ `com.zaxxer:HikariCP`（optional，仅 `MetaDataSource` 回收检测用，不传递） | — | H2、JUnit 5、Mockito |
| `autumn-data-jdbc` | `autumn-data-common`、`com.zaxxer:HikariCP`、`org.slf4j:slf4j-api`、`javax.sql.DataSource`（JDK） | `jakarta.persistence:jakarta.persistence-api`（实体注解）、APT（待加） | H2 |
| `autumn-data-jdbc-spring-boot-starter` | `autumn-data-jdbc`、`autumn-data-common`、`spring-boot-starter-jdbc`、`spring-boot-autoconfigure` | `spring-boot-configuration-processor`（构建期，不入依赖树） | H2、spring-boot-test |
| `autumn-data-meta-jdbc` | `autumn-data-common`、`com.zaxxer:HikariCP`、`org.freemarker:freemarker` | — | H2 |

说明：
- `spring-boot-starter-jdbc` 传递引入 `spring-boot-starter` → `spring-boot` + `snakeyaml` + `logback` + `jsp` 等，以及 `spring-jdbc`/`spring-tx`，对「编译期生成 SQL、运行时零反射」的核心库属于额外负担。
- MyBatis 对照（本地 `~/.m2` 真实 POM 核对，见 [docs/dependency-layering.md](docs/dependency-layering.md)）：引擎本体 `org.mybatis:mybatis` 的 POM **没有任何 Spring 依赖**，只有 `ognl`/`javassist`/`cglib` 三个 `<optional>`（字节码已重定位进 JAR）；Spring 依赖只出现在桥接层 `mybatis-spring`（`spring-context`/`spring-jdbc` 且为 `provided`，不透传）与 autoconfigure 层。`mybatis-*` 全家族**零 Spring Data 依赖**——反而是 `spring-data-jdbc` 把 `mybatis`/`mybatis-spring` 标为 optional。autumn 采用同样分层。
- 根 POM 的 `spring-boot-starter-parent` 仅作 BOM/构建用途，不进入发布产物的依赖树。

## 运行示例（using 模块）

```bash
./mvnw -B install -DskipTests                     # reactor 快照进本地仓库
export H2_PASSWORD='<不写入仓库的本地库密码>'       # 凭据只走环境变量
./mvnw spring-boot:run -pl autumn-data-jdbc-spring-boot-starter-using \
    -Dspring-boot.run.profiles=demo               # demo profile → H2 TCP 服务库
curl http://localhost:8080/training/user          # 单表 + 一对多装配
curl "http://localhost:8080/training/user/page?page=0&size=1"   # 分页（方言 limit/offset + 总行数）
curl -X POST "http://localhost:8080/training/order/batch?count=3&userId=1"  # 批量插入 + 生成键回填
```

配置约定（全项目只用 `.properties`，不用 yaml）：
- `application.properties`：连 `jdbc:h2:tcp://localhost:9092/autumn_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`。该库是安全项目在用的真实库，`basic_user` 是它的既有表（17 列），演示只读它、不往里插数据；脚本 `main-schema.sql` 只创建自有表 `demo_order` 并用 `INSERT IGNORE` + 固定主键补种子，重启不重复插数据。
- 注意：demo 实体表名 `basic_user` 与该库的真实表同名，列名恰好重合才读得通；一旦要写用户数据，请改用自有库或改表名，否则会落到安全项目的表里。
- `src/test/resources/application.properties`：自动化测试用内存库 `jdbc:h2:mem:autumn-using-test`，与 TCP 服务库完全隔离。
- 改动资源或处理器产物后请用 `./mvnw -B clean test`（旧 `target/classes` 残留会让改动看起来无效）。

## 构建

```bash
# 使用项目自带的 Maven Wrapper
./mvnw -B clean install -DskipTests

# 或使用本机 Maven（需 JDK 25）
mvn -B clean install -DskipTests
```

## 技术栈

- Java 25
- Spring Boot 4.1.2 父 POM（仅作 BOM 与构建约定）
- HikariCP（数据源连接池，核心模块直接声明，不经由 `spring-boot-starter-jdbc`）
- FreeMarker（替代 MyBatis XML）
- 目标数据库：MySQL、PostgreSQL（方言可扩展）
