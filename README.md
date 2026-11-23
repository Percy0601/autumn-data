# autumn-data

Autumn 数据访问层：**编译期静态仓储**（`autumn-data-jdbc`）+ **运行期动态表 CRUD**（`autumn-data-meta-jdbc`），共用 `autumn-data-common` 的方言与 MyBatis 式 XML AST。核心不依赖 Spring 运行期，Spring 只在 starter。

## 模块规划

| 模块 | 定位 |
| --- | --- |
| `autumn-data-common` | 方言抽象、`MetaDataSource` 多池注册表、`GlobalScheduler`、XML AST；纯 JDK。 |
| `autumn-data-jdbc` | 零反射 JDBC 仓储核心：Spring Data 注解风格，运行期零 Spring 依赖。 |
| `autumn-data-jdbc-compiler` | JSR-269 处理器：SQL 编译期内联为生成类常量，解析不了的方法在声明处报错。 |
| `autumn-data-jdbc-spring-boot-starter` | 唯一使用方入口：自动配置仓储与连接源，融入 `@Transactional`。 |
| `autumn-data-jdbc-spring-boot-starter-using` | 使用方示例（不发布），以用法倒逼实现。 |
| `autumn-data-meta-jdbc` | 运行期动态表 CRUD：按表生成 `MetaClass` 子类，有界 LRU 缓存，低代码平台基座。 |
| `autumn-data-benchmark` | 映射性能对照（[README](autumn-data-benchmark/README.md)）。 |

**两条线职责分明**：静态线在编译期生成实现、运行期零反射；动态线的表是运行期设计出来的，所以一切「首次访问才生成 + 有界弹性缓存」。只共用 common 的方言 / XML AST / `SqlBuild`。

## 快速开始

使用方只引 starter，配 `spring.datasource.*` 即可：

```xml
<dependency>
    <groupId>xyz.wewin.autumn</groupId>
    <artifactId>autumn-data-jdbc-spring-boot-starter</artifactId>
</dependency>
```

静态仓储：

```java
@AutumnRepository
public interface OrderRepository extends CrudRepository<Order, Long> {
    List<Order> findByStatus(int status);
}
```

动态表 CRUD（表可以运行期才建，建完不重启直接读写）：

```java
MetaClass order = MetaMapper.newEntity("form_order");
order.set("order_no", "A001");
order.set("amount", "12.50");
MetaMapper.save(order);                                   // 赋过值的列才进 INSERT，自增主键回填

MetaClass row = MetaMapper.findById("form_order", 1L).orElseThrow();
BigDecimal amount = row.getBigDecimal("amount");           // 类型化读取，不是 Map.get 再猜
Class<? extends MetaClass> type = MetaMapper.entityType("form_order");

MetaClass params = new MetaClass();
params.set("status", 1);
Page<MetaClass> page = MetaMapper.findPage("form_order", params, Pageable.of(0, 20));

MetaMapper.of("tenant-a", "erp").findList("form_order", params);  // 绑定数据源 + 命名空间
```

完整 API 与语义见 [docs/meta-dynamic-crud.md](docs/meta-dynamic-crud.md)、[docs/spring-data-derived-query.md](docs/spring-data-derived-query.md)。

## 动态表 CRUD 要点

- **返回的是运行期生成的 `MetaClass` 子类**：按 `DatabaseMetaData` 拼源码、内存编译加载，真实字段 / getter、逐行零反射；结构指纹决定包名，加列 `refresh` 后生成新类，旧实例仍可用；`entityType(table)` 可拿到类。
- **SQL 只有一个来源：XML 语句文件**。路径 `{根}/{数据源}/{命名空间}/{表名}.xml`，跨表 / 聚合语句放同目录 `{表名}.vo.xml`。七条默认语句写在 `GENERATED START/END` 块里随表结构刷新，标记外的自定义语句逐字保留；VO 文件只写一次，之后归使用方。语法是静态实体同一套 XML 方言（`#{}` 绑定、`${}` 标识符白名单、`<if>/<choose>/<foreach>/<where>/<trim>/<sql>/<include>/<resultMap>`），另加内置谓词 `changed('col')`。
- **查询 = 参数实体 + XML `<if>`；写入 = 实体变更集**（显式置 NULL 与「这列没动」是两件事）；**删除语义由 XML 的 `delete` 语句决定**（有逻辑删除列默认逻辑删，否则物理删，可覆盖）。
- **跨表 / 聚合查询**：`resultType="map"` / `"metaclass"` 的语句用 `selectShape/selectShapeOne/selectShapePage`，每条语句按结果列生成一个具体 `MetaClass` 子类；需要 Map 时 `row.toMap()`。与实体入口互相拒绝，不静默丢列。
- **缓存全部有界 LRU**：表快照 / 实体类 / 执行计划各 10000，语句解析结果 1000；JDK `LinkedHashMap` 自研，不引缓存库、不引 Spring。可按层清理，`reload(table)` 清完立刻回读重编。
- **Native Image**：`entity-codegen=auto` 在 native 下自动退到 GENERIC（零反射、通用 `MetaClass`），语句 XML 留在磁盘按需读取。
- 多池用 `MetaMapper.of(池, 命名空间)` 绑定句柄，不塞 ThreadLocal；连接池与事务沿用使用方的 Spring `DataSource`。

配置项：`autumn.data.meta.mapper.meta-directory`（工作根目录，空串纯内存）、`generate-files`、`table-cache-size`、`statement-cache-size`、`default-list-limit`、`logic-delete-columns`、`entity-codegen`。多数据源接管开关 `autumn.data.meta.enabled=true`，额外池 `autumn.data.meta.pools.<名称>.*` 懒建 + 空闲回收，Spring 事务只绑默认池。详见 [docs/meta-dynamic-crud.md](docs/meta-dynamic-crud.md)。

## 依赖策略

| 模块 | 运行时依赖 | 测试依赖 |
| --- | --- | --- |
| `autumn-data-common` | 纯 JDK + `HikariCP`（optional，不传递） | H2、JUnit 5、Mockito |
| `autumn-data-jdbc` | `autumn-data-common`、`HikariCP`、`slf4j-api` | H2 |
| `autumn-data-jdbc-spring-boot-starter` | `autumn-data-jdbc`、`autumn-data-common`、`autumn-data-meta-jdbc`、`spring-boot-starter-jdbc`、`spring-boot-autoconfigure` | H2、spring-boot-test |
| `autumn-data-meta-jdbc` | `autumn-data-common`、`autumn-data-jdbc`、`HikariCP`；实体源码 `StringBuilder` 拼装，无模板引擎 / 缓存库依赖 | H2 |

对照实证（MyBatis / Spring Data / starter-jdbc）见 [docs/dependency-layering.md](docs/dependency-layering.md)。根 POM 的 `spring-boot-starter-parent` 仅作 BOM 与构建约定，不进发布产物依赖树。

## 运行示例

`autumn-data-jdbc-spring-boot-starter-using` 是可直接跑的使用方示例：

```bash
./mvnw -B install -DskipTests    # 处理器按 GAV 从本地仓库解析，新环境必做
./mvnw spring-boot:run -pl autumn-data-jdbc-spring-boot-starter-using \
    -Dspring-boot.run.profiles=demo
curl http://localhost:8080/training/user                    # 单表 + 一对多
curl "http://localhost:8080/training/user/page?page=0&size=1" # 分页
curl http://localhost:8080/library/authors                  # 1:n，两条语句，无 N+1
curl http://localhost:8080/library/books                    # n:1 + 1:n 同时装配
```

启动脚本会自动执行 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`，可反复跑；中文查询参数用 `curl -G --data-urlencode`。测试用内存库，与示例的 TCP 库隔离。

## 构建

```bash
./mvnw -B clean install          # 或本机 Maven（需 JDK 25）
```

## 技术栈

- Java 25，Spring Boot 4.1.2 父 POM（仅 BOM / 构建约定）
- HikariCP 连接池，核心模块直接声明，不经 `spring-boot-starter-jdbc`
- MyBatis 式 XML 方言由 `autumn-data-common` 解析成同一棵 AST：静态实体编译期生成 Java，动态表运行期解释
- 实体源码由 `MetaEntitySource` 直接拼 Java 字符串 + `javax.tools` 内存编译，不依赖模板引擎
- MySQL / PostgreSQL / H2 方言可扩展；`db/` 下有 schema 与初始化说明

## 文档

- [动态表 CRUD 完整设计与 API](docs/meta-dynamic-crud.md)
- [Spring Data 派生查询规范](docs/spring-data-derived-query.md)
- [依赖分层实证对照](docs/dependency-layering.md)
- [变更记录（历史进度清单）](docs/CHANGELOG.md)
