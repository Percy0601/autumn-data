# autumn-data

Autumn 数据访问层，参考 `autumn-gateway` 的多模块 Maven 工程结构（最外层为 `pom` 聚合工程，兼容 Maven 4.x）。

## 模块规划

| 模块 | 定位 | 关键特性 |
| --- | --- | --- |
| `autumn-data-jdbc` | 零反射 JDBC 仓储 | 采用 **Spring Data 注解风格**（`Repository` / `CrudRepository` / `@Query` / `@Param`），并参考 **Micronaut Data** 在**编译期**通过注解处理器生成实现类，运行时零反射。核心**不依赖 Spring**，数据源连接池使用 **HikariCP**。 |
| `autumn-data-jdbc-spring-boot-starter` | Spring Boot 集成层（唯一使用方入口） | 约定大于配置：只引这一个依赖 + `spring.datasource.*` 即可用。仿 MyBatis 分层（引擎零 Spring / 集成层依赖 Boot），自动配置 `Dialect` Bean，支持 `autumn.data.jdbc.dialect` 显式指定或按 `DataSource` 元数据自动识别。 |
| `autumn-data-jdbc-compiler` | JSR-269 注解处理器 | 编译期为 `@AutumnRepository` 接口生成 `<名称>Impl`；仅 `annotationProcessorPaths` 使用，**运行时不依赖**。当前为管道版（约定校验 + 骨架生成），实体元数据/SQL/RowMapper 生成为下一步。 |
| `autumn-data-jdbc-spring-boot-starter-using` | 使用方示例（不发布） | **使用驱动开发的主战场**：只引 starter + 挂 compiler 处理器，在这里写后续所有用法（单表、一对多、多对一、派生查询），以用法倒逼核心实现。 |
| `autumn-data-meta` | 轻量动态 SQL 引擎 | 对标 **MyBatis** 但更轻量，支持**运行时动态加载**；用 **FreeMarker（.ftl）** 语法替代 MyBatis 的 XML SQL。数据源连接池同样使用 **HikariCP**。 |

两个模块均内置多数据库方言（`Dialect`）抽象，至少支持 **MySQL** 与 **PostgreSQL**，并可通过 `DialectResolver` 根据 `DataSource` 的元数据自动识别。

## 当前进度（初始化阶段）

- [x] 根 POM / `.gitignore` / `.gitattributes` / Maven Wrapper
- [x] `autumn-data-jdbc` 模块骨架 + 多数据库 `Dialect`
- [x] `autumn-data-meta` 模块骨架 + FreeMarker 配置 + 多数据库 `Dialect`
- [x] Spring Data 派生查询（方法名约定）规范文档：[docs/spring-data-derived-query.md](docs/spring-data-derived-query.md)
- [x] 依赖分层实证对照（MyBatis / Spring Data / starter-jdbc）：[docs/dependency-layering.md](docs/dependency-layering.md)
- [x] 依赖精简：`autumn-data-jdbc` 去掉 `spring-boot-starter-jdbc`，只留 `HikariCP` + `slf4j-api` + `jakarta.persistence-api`
- [x] 新增 `autumn-data-jdbc-spring-boot-starter`（`Dialect` 自动配置 + `autumn.data.jdbc.*` 配置项 + 冒烟测试）
- [x] 新增 `autumn-data-jdbc-spring-boot-starter-using`（使用方示例：只引 starter，H2 集成测试验证开箱体验；不发布）
- [x] 新增 `autumn-data-jdbc-compiler`（JSR-269 管道版：`@AutumnRepository` 约定校验 + `CrudRepository` 实现骨架生成，端到端已验证）
- [ ] compiler 下一版：实体元数据提取（`@Entity`/`@Id`/`@Column`）→ INSERT/SELECT/DELETE SQL + RowMapper 生成
- [ ] 派生查询解析器（`findByNameAndAge`，按 docs/spring-data-derived-query.md 第 1~6 节）
- [ ] `autumn-data-meta` 同步去 Spring 依赖（同样挪到各自 starter）
- [ ] `autumn-data-jdbc`：编译期注解处理器（零反射实现生成）
- [ ] `autumn-data-meta`：FreeMarker 动态 Mapper 执行引擎

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
| `autumn-data-jdbc` | `com.zaxxer:HikariCP`、`org.slf4j:slf4j-api`、`javax.sql.DataSource`（JDK） | `jakarta.persistence:jakarta.persistence-api`（实体注解）、APT（待加） | H2 |
| `autumn-data-jdbc-spring-boot-starter` | `autumn-data-jdbc`、`spring-boot-starter-jdbc`、`spring-boot-autoconfigure` | `spring-boot-configuration-processor`（构建期，不入依赖树） | H2、spring-boot-test |
| `autumn-data-meta` | `com.zaxxer:HikariCP`、`org.freemarker:freemarker` | — | H2 |

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
curl http://localhost:8080/training/user          # 当前返回 500 = 骨架占位（awaiting M2）
```

配置约定（全项目只用 `.properties`，不用 yaml）：
- `application.properties`：连 `jdbc:h2:tcp://localhost:9092/autumn_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`，密码 `${H2_PASSWORD:}`，脚本 `main-schema.sql`（`INSERT IGNORE` + 固定主键，重启不重复插数据）。
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
