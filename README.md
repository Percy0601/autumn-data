# autumn-data

Autumn 数据访问层：**编译期静态仓储**（`autumn-data-jdbc`）+ **运行期动态表 CRUD**（`autumn-data-meta-jdbc`），共用 `autumn-data-common` 的方言与 MyBatis 式 XML AST。核心不依赖 Spring 运行期，Spring 只在 starter。

## 模块规划

| 模块 | 定位 |
| --- | --- |
| `autumn-data-common` | 方言抽象、多池注册表 `MetaDataSource`、XML AST、`MetaClass` 元模型；纯 JDK。 |
| `autumn-data-jdbc` | 零反射 JDBC 仓储核心：Spring Data 注解风格，运行期零 Spring 依赖。 |
| `autumn-data-jdbc-compiler` | JSR-269 处理器：SQL 编译期内联为生成类常量，解析不了的方法在声明处报错。 |
| `autumn-data-jdbc-spring-boot-starter` | 唯一使用方入口：自动配置仓储与连接源，融入 `@Transactional`。 |
| `autumn-data-jdbc-spring-boot-starter-using` | 使用方示例（不发布）：动态表 HTTP 端点 + Spring Boot 集成用例。 |
| `autumn-data-meta-jdbc` | 运行期动态表 CRUD：统一 `MetaClass` + 有界 LRU 缓存，低代码平台基座。 |
| `autumn-data-benchmark` | 映射性能对照（[README](autumn-data-benchmark/README.md)）。 |

**两条线职责分明**：静态线在编译期生成实现、运行期零反射；动态线的表是运行期设计出来的，一切「首次访问才解析 + 有界弹性缓存」。只共用 common 的方言 / XML AST / `SqlBuild`。

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
String javaType = row.property("amount").getType();        // 属性上带字段类型描述

MetaClass params = new MetaClass();
params.set("status", 1);
Page<MetaClass> page = MetaMapper.findPage("form_order", params, Pageable.of(0, 20));

MetaMapper.of("tenant-a", "erp").findList("form_order", params);  // 绑定数据源 + 命名空间
```

完整 API 与语义见 [docs/meta-dynamic-crud.md](docs/meta-dynamic-crud.md)、[docs/spring-data-derived-query.md](docs/spring-data-derived-query.md)。

## 动态表 CRUD 要点

- **实体统一是 common 的 `MetaClass`**：每行一份属性列表，属性上带 Java / JDBC 类型、主键、自增；没有运行期 javac、没有生成子类、没有反射代理，Native Image / JRE 都能跑。`entityType(table)` 恒为 `MetaClass.class`。
- **一份 JSON 就是一个类定义**：`spec/{表名}.json` 描述 name / namespace / metaType / logicDeleteColumn / properties；定义优先，缺列由数据库补齐，读行时按 `properties[].type` 收口值。完整示例：[DEMO_ORDER.json](docs/examples/meta-data/example-default/example-default/spec/DEMO_ORDER.json)。
- **SQL 只有一套语法、两张文件**：`spec/xml/{表名}.xml` 是手写资产（框架只读），`auto/xml/{表名}.xml` 是框架生成的七条默认语句（结构指纹变化整份重写）；同 id 时 spec 覆盖 auto。路径为 `{根}/{数据源}-default/{命名空间}-default/...`。语法与静态线同一套 XML 方言：`#{}` 绑定、`${}` 标识符白名单、`<if>/<where>/<trim>/<foreach>/<choose>/<sql>/<include>/<resultMap>`，另有 `changed('col')`。完整示例：[DEMO_ORDER.xml](docs/examples/meta-data/example-default/example-default/spec/xml/DEMO_ORDER.xml)。
- **查询 = 参数实体 + XML `<if>`；写入 = 实体变更集**（显式置 NULL 与「这列没动」是两件事）；**删除语义由 XML 的 `delete` 语句决定**（有逻辑删除列默认逻辑删，否则物理删，可覆盖）。
- **缓存全部有界 LRU**：表快照 / 实体模型 / JSON 定义 / 语句 AST / 执行计划 / 池客户端各有上限；自研 `MetaCache` + `LruMetaCache` + `MetaCaches`，不引缓存库、不引 Spring 运行期。
- 多池用 `MetaMapper.of(池, 命名空间)` 绑定句柄，不塞 ThreadLocal；连接池与事务沿用使用方的 Spring `DataSource`。

配置项：`autumn.data.meta.mapper.meta-directory`（默认 `meta-data`，空串纯内存）、`generate-files`、`table-cache-size`、`statement-cache-size`、`default-list-limit`、`logic-delete-columns`。多数据源接管开关 `autumn.data.meta.enabled=true`，额外池 `autumn.data.meta.pools.<名称>.*` 懒建 + 空闲回收，Spring 事务只绑默认池。详见 [docs/meta-dynamic-crud.md](docs/meta-dynamic-crud.md)。

## 依赖策略

| 模块 | 运行时依赖 | 测试依赖 |
| --- | --- | --- |
| `autumn-data-common` | 纯 JDK + `HikariCP`（optional，不传递） | H2、JUnit 5、Mockito |
| `autumn-data-jdbc` | `autumn-data-common`、`HikariCP`、`slf4j-api` | H2 |
| `autumn-data-jdbc-spring-boot-starter` | `autumn-data-jdbc`、`autumn-data-common`、`autumn-data-meta-jdbc`、`spring-boot-starter-jdbc`、`spring-boot-autoconfigure` | H2、spring-boot-test |
| `autumn-data-meta-jdbc` | `autumn-data-common`、`autumn-data-jdbc`、`HikariCP`；无 javac / 模板引擎 / 缓存库依赖 | H2 |
| `autumn-data-jdbc-compiler` | `autumn-data-common`、javac（编译期） | H2 |

对照实证（MyBatis / Spring Data / starter-jdbc）见 [docs/dependency-layering.md](docs/dependency-layering.md)。根 POM 的 `spring-boot-starter-parent` 仅作 BOM 与构建约定，不进发布产物依赖树。

## 运行示例

`autumn-data-jdbc-spring-boot-starter-using` 是可直接跑的使用方示例，保留的是动态表元数据端点：

```bash
./mvnw -B install -DskipTests    # 处理器按 GAV 从本地仓库解析，新环境必做
./mvnw spring-boot:run -pl autumn-data-jdbc-spring-boot-starter-using
curl -X POST http://localhost:8080/metadata/init
curl http://localhost:8080/metadata/schema
curl -X POST 'http://localhost:8080/metadata/rows?count=2'
curl 'http://localhost:8080/metadata/rows?status=1&minAmount=50'
curl http://localhost:8080/metadata/statements
curl http://localhost:8080/metadata/summary
```

`/metadata/init` 会运行期建表并写入 spec 语句；测试用内存库，与手动启动的实例隔离。

## 构建

```bash
./mvnw -B clean install          # 或本机 Maven（需 JDK 25）
```

## 技术栈

- Java 25，Spring Boot 4.1.2 父 POM（仅 BOM / 构建约定）
- HikariCP 连接池，核心模块直接声明，不经 `spring-boot-starter-jdbc`
- MyBatis 式 XML 方言由 `autumn-data-common` 解析成同一棵 AST：静态实体编译期生成 Java，动态表运行期解释
- MySQL / PostgreSQL / H2 方言可扩展

## 文档

- [动态表 CRUD 完整设计与 API](docs/meta-dynamic-crud.md)
- [完整 JSON / XML 示例](docs/examples/meta-data)
- [Spring Data 派生查询规范](docs/spring-data-derived-query.md)
- [依赖分层实证对照](docs/dependency-layering.md)
- [变更记录（历史进度清单）](docs/CHANGELOG.md)
