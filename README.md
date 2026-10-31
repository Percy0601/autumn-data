# autumn-data

Autumn 数据访问层，参考 `autumn-gateway` 的多模块 Maven 工程结构（最外层为 `pom` 聚合工程，兼容 Maven 4.x）。

## 模块规划

| 模块 | 定位 | 关键特性 |
| --- | --- | --- |
| `autumn-data-jdbc` | 零反射 JDBC 仓储 | 采用 **Spring Data 注解风格**（`Repository` / `CrudRepository` / `@Query` / `@Param`），并参考 **Micronaut Data** 在**编译期**通过注解处理器生成实现类，运行时零反射。数据源连接池使用 Spring Boot 默认的 **HikariCP**。 |
| `autumn-data-meta` | 轻量动态 SQL 引擎 | 对标 **MyBatis** 但更轻量，支持**运行时动态加载**；用 **FreeMarker（.ftl）** 语法替代 MyBatis 的 XML SQL。数据源连接池同样使用 **HikariCP**。 |

两个模块均内置多数据库方言（`Dialect`）抽象，至少支持 **MySQL** 与 **PostgreSQL**，并可通过 `DialectResolver` 根据 `DataSource` 的元数据自动识别。

## 当前进度（初始化阶段）

- [x] 根 POM / `.gitignore` / `.gitattributes` / Maven Wrapper
- [x] `autumn-data-jdbc` 模块骨架 + 多数据库 `Dialect`
- [x] `autumn-data-meta` 模块骨架 + FreeMarker 配置 + 多数据库 `Dialect`
- [ ] `autumn-data-jdbc`：编译期注解处理器（零反射实现生成）
- [ ] `autumn-data-meta`：FreeMarker 动态 Mapper 执行引擎

## 构建

```bash
# 使用项目自带的 Maven Wrapper
./mvnw -B clean install -DskipTests

# 或使用本机 Maven（需 JDK 25）
mvn -B clean install -DskipTests
```

## 技术栈

- Java 25
- Spring Boot 4.1.1（Spring Framework 7 / Jakarta EE 11）
- Spring Data Commons（注解与仓储基类）
- HikariCP（数据源连接池，由 `spring-boot-starter-jdbc` 引入）
- FreeMarker（替代 MyBatis XML）
- 目标数据库：MySQL、PostgreSQL（方言可扩展）
