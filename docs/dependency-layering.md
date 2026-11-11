# MyBatis / Spring Data / Spring Boot 依赖分层对照

> 全部结论取自本机 `~/.m2` 已下载的真实 POM 与 JAR（非二手资料），核对时间 2026-10-31。
> 用途：确定 `autumn-data-*` 核心模块的最小依赖集，以及 Spring 集成层应该放在哪里。

## 1. 版本坐标

| 构件 | 版本 |
|---|---|
| mybatis-spring-boot-starter / autoconfigure | 4.1.0 |
| mybatis-spring | 4.1.0 |
| mybatis（引擎本体） | 3.5.19 |
| spring-boot-starter-jdbc | 4.1.1 |
| spring-data-jdbc / spring-data-commons | 4.1.1 |

## 2. `mybatis-spring-boot-starter` 的直接依赖（POM 逐条）

```
org.springframework.boot : spring-boot-starter
org.springframework.boot : spring-boot-starter-jdbc
org.mybatis.spring.boot  : mybatis-spring-boot-autoconfigure
org.mybatis              : mybatis
org.mybatis              : mybatis-spring
```

传递展开（同版本 POM 逐层核对）：

```
spring-boot-starter            -> spring-boot-starter-logging(logback-classic, log4j-to-slf4j, jul-to-slf4j)
                                 spring-boot-autoconfigure -> spring-boot
                                 jakarta.annotation-api, snakeyaml
spring-boot-starter-jdbc       -> spring-boot-starter, spring-boot-jdbc, HikariCP
spring-boot-jdbc               -> spring-boot, spring-boot-sql, spring-boot-transaction, spring-jdbc
spring-boot-transaction        -> spring-boot, spring-boot-persistence, spring-tx
spring-jdbc                    -> spring-beans, spring-core, spring-tx
spring-boot                    -> spring-core, spring-context
mybatis-spring-boot-autoconfigure -> spring-boot-autoconfigure, spring-boot-jdbc
                                   (mybatis / mybatis-spring / slf4j-api / mybatis-freemarker|velocity|thymeleaf 均为 <optional>)
```

**净传递闭包（去掉 optional 后）**：

`spring-core`、`spring-beans`、`spring-context`、`spring-jdbc`、`spring-tx`、`spring-boot`、`spring-boot-autoconfigure`、`spring-boot-jdbc`、`spring-boot-sql`、`spring-boot-transaction`、`spring-boot-persistence`、`jakarta.annotation-api`、`snakeyaml`、`logback-classic`、`log4j-to-slf4j`、`jul-to-slf4j`、`HikariCP`、`mybatis`。

## 3. 回答关键问题：MyBatis 依赖了 Spring 的哪些组件？

| 层级 | 依赖的 Spring 组件 | 说明 |
|---|---|---|
| `org.mybatis:mybatis`（引擎） | **零** | POM 里没有任何 `org.springframework.*`，也没有 slf4j —— 仅 `ognl:3.4.4`、`org.javassist:javassist:3.30.2-GA`、`cglib:3.3.0` 三个 `<optional>`，且字节码已重定位打进 JAR（实测存在 `org/apache/ibatis/ognl/OgnlRuntime.class`、`org/apache/ibatis/javassist/ClassPool.class`），运行时无需外部提供 |
| `org.mybatis:mybatis-spring`（Spring 桥接层） | `spring-context`、`spring-jdbc`（均为 `<scope>provided</scope>`），`aspectjweaver` 为 `<optional>` | **provided 意味着不透传** —— 由使用方/Spring Boot 侧提供版本，桥接层自己不锁定 Spring 版本 |
| `mybatis-spring-boot-autoconfigure` | `spring-boot-autoconfigure`、`spring-boot-jdbc` | 自动配置层才真正依赖 Boot |
| `mybatis-spring-boot-starter` | 上面全部 + `spring-boot-starter`（logging/snakeyaml） | starter 只是聚合清单 |

**`mybatis-*` 全家族对 Spring Data 的依赖：无。** 在本地仓库 `~/.m2/repository/org/mybatis` 全树 grep `springframework/data` 无任何命中。方向是反的 —— 是 Spring Data 依赖 MyBatis：

```
spring-data-jdbc 4.1.1 POM:
  org.mybatis : mybatis          <optional>true</optional>
  org.mybatis : mybatis-spring   <optional>true</optional>
```

即 Spring Data JDBC 的「XML/MyBatis 式 SQL 定义」能力是可选挂载 MyBatis 实现的。

## 4. 对照：Spring Data JDBC 自己的依赖

```
spring-data-jdbc -> spring-data-relational, spring-data-commons,
                    spring-tx, spring-context, spring-beans, spring-jdbc, spring-core,
                    (optional) kotlin-stdlib, kotlin-reflect, mybatis, mybatis-spring, liquibase-core
```

结论：`spring-data-*` 全家（commons + relational + jdbc）比 MyBatis 的 Spring 桥接层更重，且把自己的 `Repository`/`Pageable`/`Sort` 类型暴露进公开 API。

## 5. 对 autumn 的取舍

1. **核心模块（`autumn-data-jdbc` / `autumn-data-meta-jdbc`，方言在 `autumn-data-common`）不依赖 `spring-boot-starter-jdbc`**：
   - 现网代码实测只 import `javax.sql.DataSource` + `java.sql.*`，无一处 Spring，也无模板引擎（动态层的 XML 由 `autumn-data-common` 的 `TemplateParser` 解析后解释执行）。
   - 只需 `com.zaxxer:HikariCP`（版本由 `spring-boot-starter-parent` BOM 管理，无需写死）。
   - 事务：首期直接用 JDBC 原生 `Connection.setAutoCommit(false)` / `commit()`；若使用方要 `@Transactional`，只需在 classpath 上有 `spring-tx` + `spring-jdbc` 的 `DataSourceTransactionManager`（对 `DataSource` 编程，不依赖我们的类），无需核心模块透传。
2. **不引入 `spring-data-commons`**：注解（`@Repository`/`@Query`/`@Param`/`CrudRepository`）由 autumn 自定义，方法名解析按 [spring-data-derived-query.md](spring-data-derived-query.md) 在编译期自实现；避免 Spring 类型进公开 API、避免 APT 生成代码依赖运行时反射。
3. **Spring 集成单独成模块**（规划 `autumn-data-spring-boot-starter`）：仿 MyBatis 分层 —— 引擎零 Spring、桥接层 `provided`、autoconfigure 层依赖 Boot。这样 autumn 核心库同样可服务 Micronaut / Quarkus / 裸 JDBC 场景，且 GraalVM native 只需处理 `autumn-data-*` 自己的类。

## 6. 若仍决定保留 `spring-boot-starter-jdbc` 的代价

- 引入约 18 个额外 artifact（见第 2 节净闭包），其中 `snakeyaml` + logback 三件套与数据访问无关。
- GraalVM native 需要为 Spring 反射/资源机制生成配置，配置量与踩坑面按整个 Spring 栈计，而非按 autumn 自身。
- 发布产物 POM 会把 Spring 版本锁进下游依赖仲裁，非 Spring 使用方（Micronaut 等）无法复用。
