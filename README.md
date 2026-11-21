# autumn-data

Autumn 数据访问层，参考 `autumn-gateway` 的多模块 Maven 工程结构（最外层为 `pom` 聚合工程，兼容 Maven 4.x）。

## 模块规划

| 模块 | 定位 | 关键特性 |
| --- | --- | --- |
| `autumn-data-common` | JDBC 公共底座 | 数据库方言抽象（`Dialect` / `DialectResolver` / ANSI / MySQL / PostgreSQL / H2 实现）与通用 JDBC 基础设施；`MetaDataSource` 多数据源注册表（懒加载 + 空闲池轮询回收，面向多连接池场景）；`GlobalScheduler` 全局唯一定时任务线程池；核心零 Spring，被 `autumn-data-jdbc` 与 `autumn-data-meta-jdbc` 复用。 |
| `autumn-data-jdbc` | 零反射 JDBC 仓储 | 采用 **Spring Data 注解风格**（`Repository` / `CrudRepository` / `@Query` / `@Param`），并参考 **Micronaut Data** 在**编译期**通过注解处理器生成实现类，运行时零反射。核心**不依赖 Spring**，数据源连接池使用 **HikariCP**。 |
| `autumn-data-jdbc-spring-boot-starter` | Spring Boot 集成层（唯一使用方入口） | 约定大于配置：只引这一个依赖 + `spring.datasource.*` 即可用。仿 MyBatis 分层（引擎零 Spring / 集成层依赖 Boot），自动配置 `Dialect` Bean，支持 `autumn.data.jdbc.dialect` 显式指定或按 `DataSource` 元数据自动识别。多库场景可用 `autumn.data.meta.enabled=true` 让 `SpringMetaDataSource`（继承 Spring `AbstractDataSource`）接管数据源，底层交给 `MetaDataSource`。 |
| `autumn-data-jdbc-compiler` | JSR-269 注解处理器 | 编译期为 `@AutumnRepository` 接口生成 `<名称>Impl`；仅 `annotationProcessorPaths` 使用，**运行时不依赖**。已实现：实体元数据提取（`@Entity`/`@Table`/`@Id`/`@Column`/`@GeneratedValue`/`@OneToMany`）、CRUD + 分页 + 一对多 + 方法名派生查询 SQL **编译期内联为生成类常量**、`RowMapper` 与批量读写/装配代码生成。解析不了的方法在**接口方法声明处**报错，不静默生成半成品。 |
| `autumn-data-jdbc-spring-boot-starter-using` | 使用方示例（不发布） | **使用驱动开发的主战场**：只引 starter + 挂 compiler 处理器，在这里写后续所有用法（单表、一对多、多对一、派生查询），以用法倒逼核心实现。 |
| `autumn-data-meta-jdbc` | **完全动态**的轻量基座（低代码平台：表单 / 基础资料的表都是运行期设计出来的） | 与 `autumn-data-jdbc` 的「编译期完全静态」职责分明：本模块**不做编译期 Mapper 注册、不预加载、不用反射代理**，元数据 / SQL / 实体类全部按表名懒加载，缓存一律有界 LRU 弹性伸缩。对标 **MyBatis** 但更轻量，支持**运行时动态加载**：XML 方言与静态实体的编译期 Mapper **同一套**（`#{}` 绑定、`${}` 标识符、`<if>/<choose>/<foreach>/<where>/<trim>`、`<sql>/<include>` 片段、`<resultMap>/<result>` 字段映射），动态 SQL 运行期解释执行。数据源连接池同样使用 **HikariCP**；方言与解析器复用 `autumn-data-common`。另含 `dynamic` 包：运行期建表后无需重启即可按表名/列名查询、保存、更新（`MetaMapper`，见 [docs/meta-dynamic-crud.md](docs/meta-dynamic-crud.md)）；**`MetaMapper` 返回 `MetaClass`，其实例是按表结构生成 Java 源码、`javax.tools` 编译加载的实体子类**——有真实类型化字段与 getter，逐行映射零反射；一张表一个语句 XML（`{根}/{数据源}/{命名空间}/{表名}.xml`），七条默认语句在 GENERATED 块里按表结构生成并随结构变化刷新，标记外的自定义语句逐字保留。 |

各数据访问模块共享 `autumn-data-common` 的多数据库方言（`Dialect`）抽象，至少支持 **MySQL** 与 **PostgreSQL**，并可通过 `DialectResolver` 根据 `DataSource` 的元数据自动识别。

**静态 / 动态两条线互不混淆**：`autumn-data-jdbc` + `autumn-data-jdbc-compiler` 是编译期 APT 生成实现、运行期零反射的**完全静态**仓储；`autumn-data-meta-jdbc` 是面向低代码平台的**完全动态**路径——表在运行期才被设计出来，所以一切都是「首次访问才生成 + 有界弹性缓存」。两条线只共用 `autumn-data-common` 的方言、XML AST 和 `SqlBuild` 值对象，运行机制不互相渗透。

## 当前进度（初始化阶段）

- [x] 根 POM / `.gitignore` / `.gitattributes` / Maven Wrapper
- [x] `autumn-data-jdbc` 模块骨架 + 多数据库 `Dialect`
- [x] 新增 `autumn-data-common`：收编 jdbc / meta 两套重复 `Dialect` 为公共底座，各模块改为依赖它
- [x] `autumn-data-meta-jdbc` 模块骨架（原 `autumn-data-meta`，方言已收编至 `autumn-data-common`）
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
- [x] starter 多数据源接管（`autumn.data.meta.enabled=true`，默认关闭）：`SpringMetaDataSource` 继承 Spring 的 `AbstractDataSource` 成为应用唯一的 `DataSource`（配置排在 `DataSourceAutoConfiguration` 之前，Boot 建池自动退让），底层就是 `MetaDataSource` 注册表；默认池参数仍来自 `spring.datasource.*`（含 `spring.datasource.hikari.*` 调优项），额外池 `autumn.data.meta.pools.<名称>.*` 懒建 + 空闲回收，运行期 `register(name, supplier)` 可继续追加；Spring 事务只绑默认池，`@Transactional` 提交/回滚实测不变（using 模块有示例与集成测试）
- [x] `autumn-data-meta-jdbc` 动态表 CRUD：`MetaMapper` 静态门面 + `of(池, 命名空间)` 绑定客户端 + `DatabaseMetaData` 元数据快照 + `refresh` 热更新；查询条件 = 参数实体 + XML `<if>`，写入范围 = 实体变更集（`changed('col')`），删除语义由 XML 的 `delete` 语句决定（有逻辑删除列默认逻辑删除、否则物理删除）；47 个 H2 用例 + using 模块 6 个上层用法示例覆盖「建表 → 不重启读写 → 加列 refresh → 复合主键 → 默认语句落 XML（七条生成块，手写文件首次访问自动插入）→ 自定义语句保留/覆盖 → 名字忽略大小写/注入拒绝 → 缓存分级清理与 reload」
- [ ] 动态表 CRUD 接入 Spring 事务（当前单方法内自提交，跨方法 `@Transactional` 暂不生效）
- [ ] `OrderBy` 之外的排序入参（`findAllBy(Sort)`）与 `Pageable` 排序
- [ ] `autumn-data-meta-jdbc` 同步去 Spring 依赖（同样挪到各自 starter）
- [x] `autumn-data-meta-jdbc`：**唯一语句库** `MetaSqlFileStore`——一张表一个 XML（`{根}/{数据源}/{命名空间}/{表名}.xml`），首次访问生成七条默认语句（`findList/findPage/findOne/findById/save/update/delete`）的 `GENERATED START/END` 块；表结构变化只重写该块，标记之外的自定义语句逐字保留；无标记的手写文件一个字不改写、只在内存补齐默认语句；`SqlTemplate` 解释执行 common 的同一套 MyBatis XML 语法（另有内置谓词 `changed('col')`），`#{}` 解析期摘成 JDBC 绑定、`${}` 只放标识符白名单；命名语句走 `sqlFor/selectList/selectOne/selectCount/execute`，参数一律是 `MetaClass`
- [x] `autumn-data-meta-jdbc` SQL 三段：抽取（`MarkedSql`：文本 + 每槽列引用 + 本次实参）→ 再转换（`MetaSqlPlan`：类型判断、列查找、setter 一次做完，按形状缓存）→ 执行（零槽走 `Statement`，有槽走 `PreparedStatement`），动态形状也能吃到游标/执行计划复用
- [x] XML 方言下沉 `autumn-data-common`（`TemplateParser` / `TemplateNode` / `MapperXmlParser`），编译期生成器与动态运行期共用一棵 AST；**动态 SQL 只认 MyBatis 语法**（`<#if>` 这类模板引擎指令本来就不是合法 XML，`test` 表达式也只认 `a != null` / `a == 'x'` / `!a` / `a && b`，不再兼容 `name??` / `name?has_content`）
- [x] `autumn-data-meta-jdbc` 运行期生成实体类：`MetaMapper` 门面统一返回 `MetaClass`，实例是按 `DatabaseMetaData` 用 `StringBuilder` 直接拼 Java 源码、`javax.tools.JavaCompiler` **内存编译**、内存 `ClassLoader` 加载的 `MetaClass` 子类（真实字段/getter/setter + 类型化 `getProperties()`，逐行映射零反射）；不建临时目录、不落 `.class`，类与 ClassLoader 可随缓存淘汰被 GC；结构指纹决定包名，加列 `refresh` 后生成新类、旧实例不受影响；源码可配置落盘以便固化成静态类（**已移除 FreeMarker 依赖与 `.ftl` 模板**）
- [x] GraalVM Native Image：`MetaEntityStrategy` 两档，native 里 `auto` 自动退到 **GENERIC**（不生成类、不编译、零反射，行数据直接写通用 `MetaClass`），CRUD / 语句 XML / resultMap / 分页 / json-path 序列化语义不变；`entity-codegen` 可显式覆盖，native 下强制 `code` 启动即失败；语句 XML 按磁盘工作目录读取，不声明进镜像资源
- [x] 按动态定位收口：删除零引用的 `MetaConfiguration`（编译期静态 Mapper 注册、无界 `HashMap`）与 `MetaSqlSession`（JDK 动态代理反射、需预注册）；所有按表 / 按池增长的内存容器（表快照、手工登记覆盖、动态实体类、Mapper AST、池 client）统一走 `BoundedCache` 有界 LRU，默认上限 10_000

## 依赖策略

核心模块只依赖 JDK + HikariCP（+ 编译期注解），不透传 Spring 运行时库；Spring Boot 集成放在 `autumn-data-jdbc-spring-boot-starter` 里，**使用方只依赖这一个 starter 即可（约定大于配置）**：

```xml
<dependency>
    <groupId>xyz.wewin.autumn</groupId>
    <artifactId>autumn-data-jdbc-spring-boot-starter</artifactId>
</dependency>
```

只配 `spring.datasource.*` 开箱得到：HikariCP `DataSource`、`JdbcTemplate`/`JdbcClient`、`PlatformTransactionManager`（Boot 自带自动配置）+ autumn 的 `Dialect` Bean（按数据库元数据自动识别；`autumn.data.jdbc.dialect=mysql|postgresql|h2|default|className:<fqcn>` 可覆盖，自定义 `Dialect` Bean 可顶替，`autumn.data.jdbc.enabled=false` 整体关闭）。后续仓储实现（APT 生成）的注册同样收进这个 starter，核心模块保持零 Spring。

多库场景打开接管开关（默认关，关掉时行为与上面完全一致）：`SpringMetaDataSource`（`AbstractDataSource` 子类）成为应用唯一的 `DataSource`，Boot 的建池随之退让，注册/懒加载/空闲回收交给 `MetaDataSource`，轮询任务跑在 `GlobalScheduler` 上。

```properties
autumn.data.meta.enabled=true
autumn.data.meta.default-pool-name=default   # 无参 getConnection() 与 @Transactional 绑这个池
autumn.data.meta.sweep-interval=2m           # 留空则用 MetaDataSource 内置的 3 分钟
autumn.data.meta.pools.tenant-a.url=jdbc:h2:tcp://localhost:9092/tenant_a
autumn.data.meta.pools.tenant-a.username=sa
```

```java
@Autowired SpringMetaDataSource metaDataSource;

metaDataSource.getConnection();                    // 默认池：Spring、事务管理器、生成的仓储都走这条
metaDataSource.connection("tenant-a");             // 按池名取连接（首次访问才建池）
metaDataSource.pool("tenant-a");                   // 拿真实 DataSource：给它单建一个事务管理器
metaDataSource.register("tenant-live", () -> ...); // 运行期追加，工厂方式登记不预占连接
```

Spring 事务只绑定默认池那一个实例，额外池不参与外层 `@Transactional`；跨池分布式事务不在这一层解决。示例见 `autumn-data-jdbc-spring-boot-starter-using` 的 `MultiDataSourceDemo` 与 `application-meta.properties`。

## 动态表 CRUD（运行期建表，不重启）

引入 starter 后自动安装 `MetaMapper` 静态门面；默认池直接用静态方法，多数据源 / 业务划分用 `of(池, 命名空间)` 把边界固定在客户端句柄上：

```java
MetaClass order = MetaMapper.newEntity("form_order");   // 操作对象一律是实体，没有 Map 入口
order.set("order_no", "A001");
order.set("amount", "12.50");
MetaMapper.save(order);                                 // 赋过值的列才进 INSERT，自增主键回填到 order

MetaClass row = MetaMapper.findById("form_order", 1L).orElseThrow();
BigDecimal amount = row.getBigDecimal("amount");          // 类型化读取，不是 Map.get 再猜
Class<? extends MetaClass> type = MetaMapper.entityType("form_order"); // 运行期生成的类

// 查询条件 = 参数实体：赋了值的列按 XML 里的 <if> 进 WHERE
MetaClass query = new MetaClass();
query.set("status", 1);
Page<MetaClass> page = MetaMapper.findPage("form_order", query, Pageable.of(0, 20));

row.set("status", 2);                                   // 改过的列才进 SET，写 null 是显式置空
MetaMapper.update(row);
MetaMapper.delete(row);                                 // 执行 XML 里的 delete 语句

MetaMapper.of("tenant-a", "erp").findList("form_order", query);   // 绑定数据源 + 命名空间
```

- **返回的 `MetaClass` 是动态生成的实体实例**：首次访问按 `DatabaseMetaData` 的列结构用 `MetaEntitySource` 拼出 `MetaClass` 子类源码并编译加载，每列有 `Long` / `BigDecimal` / `LocalDateTime` 等真实字段和 getter，结果集映射是生成代码里的直接 setter 调用（逐行零反射）；`getProperties()` 同时保留 `type` / `jdbcType` / 主键 / 可空等描述。完全静态的实体类应付不了运行期建表，全 Map 又丢了类型，这一层就落在两者之间。`MetaMapper.entityType(table)` 可拿到生成出来的类；
- 结构指纹参与包名：加列/改类型后 `MetaMapper.refresh("form_order")` 会生成新类，已取到的旧实例仍可用，不会同名重定义；
- `autumn.data.meta.mapper.meta-directory=autumn-meta` 是唯一的落盘开关：三类产物分别落在它的 `meta-class/`（元类定义 JSON）、`entity/`（生成的实体 `.java`，可查看也可固化）、`sql/`（语句 XML，其下再按 `数据源/命名空间/表名.xml` 分层）三个固定子目录里。空串表示纯内存模式；**实体默认全程内存编译**，不建临时目录、不落 `.class`；运行期需要完整 JDK（`javax.tools`），JRE 上会明确报错；
- 元数据从 `DatabaseMetaData` 读取，直接填入 `MetaClass` / `MetaProperty`（表名 = `MetaClass.name`、schema = `namespace`、列 = properties）；建表/加列**提交后**调 `MetaMapper.refresh("form_order")` 换快照，不重启；
- **SQL 只有一个来源：XML 语句文件**：七条默认语句（`findList/findPage/findOne/findById/save/update/delete`）由框架按表结构生成在 `GENERATED START/END` 之间，**并写进 XML**——手写文件第一次被访问时，框架会把生成块插到 `<mapper>` 开头，`findOne` 之类的 SQL 在文件里看得见、也改得动。标记之外的自定义语句归使用方所有；
- **查询条件 = 参数实体 + XML `<if>`**：传 `MetaClass`，赋了值的列才进 WHERE；比较、模糊、`IN`、排序等复杂条件写在 XML 自定义语句里，不在 Java 侧现拼 SQL。**写哪几列 = 实体变更集**：默认 `save`/`update` 用内置谓词 `changed('col')`，「显式置 NULL」与「这一列没动」是两件事；
- **API 只留常用路径**：读是 `findById/findOne/findList/findPage` 四条，总数就是 `findPage(...).total()`、存在性就是 `findOne(...).isPresent()`；`findByKey/count/exists/findList(limit)` 这些同义写法不再出现在静态门面上，需要限行或特殊计数时走命名语句（`selectPage(...).total()` / `selectCount`）或 `defaultClient()`；
- **所有缓存都有界且弹性**：表结构快照、动态实体类、语句 AST、SQL 执行计划、池 → client 五处各有独立上限（默认 10_000），触顶按 LRU 淘汰最久未访问的冷条目，下次访问自动懒加载重建。实现是 JDK 自带的 `LinkedHashMap(accessOrder)` + 锁（`BoundedCache` 接口刻意对齐 Caffeine `Cache` 的方法名与语义，将来换实现只改 `BoundedCaches` 工厂），**不引入任何缓存库、不引入 Spring**；
- **缓存可以按层清、也可以立即重建**：静态门面只有 `refresh(table)`（三样一起清）和 `cacheStats()`；`defaultClient()` 上还有 `clearTableCache` / `clearEntityCache` / `clearStatementCache` 的分级清理，以及 `reload(table)`（清完立刻回读 + 重新编译，表被删或编译失败当场抛错）。语句只有 XML 一个来源，清理只丢解析结果与执行计划，磁盘文件不动；
- **手改 SQL 就在 XML 里改**：三条 select 共用 `__autumn_where` 条件片段、共用 `__autumn_columns` 列清单，改条件/列只动这两处；某条语句要完全换写法，就把它复制到标记之后的自定义区（同名 id 后出现者优先）。Java 侧不再接受直接传一段 SQL；
- 文件名与列名按**忽略大小写**对齐：H2/Oracle 元数据给的是大写名，手写文件多按 MySQL 习惯写小写，`demo_order.xml` 配 `DEMO_ORDER` 这张表、`#{order_no}` 配 `ORDER_NO` 这一列都能对上；
- **删除看 XML 里的 `delete` 语句**：有逻辑删除列时默认生成 `UPDATE ... SET deleted_flag = 1`，没有时生成物理 `DELETE`；使用方可在自定义区覆盖 `delete`。`deleted_flag` / `del_flag` / `is_deleted` 命中后查询自动追加 `= 0`；
- `findList` 默认最多 1000 行，多数据源与业务划分按 `of(池, 命名空间)` 绑定句柄而不是 ThreadLocal；
- **连接池与事务沿用 Spring Boot**：starter 的自动配置把容器里的 `DataSource`（`spring.datasource.*` 建出来的 HikariCP 等）直接交给两条链路——编译期生成的静态仓储和动态层引擎，不自建连接池，连接的创建/回收/调优全归使用方那个池；连接源是 `PerThreadConnectionSource` 包一层 `SpringConnectionSource`（每线程一份，单例 `AutumnJdbc` 才不会串连接），所以语句自动加入 `@Transactional`——事务里调 `MetaMapper`、调 `JdbcTemplate`、调生成的仓储用的是同一条连接，回滚一起回滚。无 Spring 时（`MetaMapperEngine.standalone` / `Autumn.using`）仍是 JDBC 连接源自管、单方法自提交。

### 唯一语句库：一张表一个 XML

所有 SQL（含核心 CRUD）只有这一个来源。路径把数据源与业务分组编进去：

```text
{语句根}/{数据源}/{命名空间}/{表名}.xml
  · 语句根 = <meta-directory>/sql（或系统属性 -Dautumn.data.meta.sql.root 指定的目录）
  · 磁盘找不到时再看 classpath:meta/{数据源}/{命名空间}/{表名}.xml
  · 命名空间默认 "default"，可用于业务划分或包名称
```

`MetaMapper.of(池, 命名空间)` 把「哪个库、哪套语句」固定在客户端句柄上，不塞进 ThreadLocal：

```java
MetaMapperClient client = MetaMapper.of("tenant-a", "erp");
MetaClass params = new MetaClass();
params.set("status", 1);

List<MetaClass> rows = client.findList("form_order", params);   // 执行文件里的 findList
client.selectList("form_order", "findByNo", params);           // 执行自定义区里的语句
client.selectCount("form_order", "countDraft", params);
client.execute("form_order", "voidByNo", params);
client.statementIds("form_order");                             // 七条默认 + 自定义语句
client.ensureStatementFile("form_order");                      // 显式生成 / 更新文件并返回位置
```

#### 文件分两块

```xml
<?xml version="1.0" encoding="UTF-8"?>
<mapper namespace="tenant-a/erp/form_order">
  <!-- ===== AUTUMN-DATA GENERATED START fingerprint=3-xxxx ===== -->
  <!-- 本块由框架按表结构生成，表结构变化时只重写本块；手工改动会被覆盖。 -->
    <sql id="__autumn_columns">"id", "order_no", "amount", "status"</sql>
    <sql id="__autumn_where">
        <where>
            AND deleted_flag = 0
            <if test="order_no != null">AND order_no = #{order_no}</if>
            <if test="status != null">AND status = #{status}</if>
        </where>
    </sql>
    <select id="findList">SELECT <include refid="__autumn_columns"/> FROM ${table}
        <include refid="__autumn_where"/> ORDER BY id</select>
    <select id="findPage"> ... 同上 ... </select>
    <select id="findOne"> ... 同上 ... </select>
    <select id="findById">SELECT <include refid="__autumn_columns"/> FROM ${table}
        WHERE id = #{id} AND deleted_flag = 0</select>
    <insert id="save"> ... changed('order_no') ... </insert>
    <update id="update"> ... changed('status') ... </update>
    <update id="delete"> ... </update>
  <!-- ===== AUTUMN-DATA GENERATED END ===== -->

  <!-- ===== 自定义语句区：写在下面，框架不会改动 ===== -->
  <select id="findByNo">SELECT * FROM ${table} WHERE order_no = #{order_no}</select>
  <select id="countDraft">SELECT COUNT(*) FROM ${table} WHERE status = 0</select>
  <update id="voidByNo">UPDATE ${table} SET status = 9 WHERE order_no = #{order_no}</update>
</mapper>
```

- **生成语句落进 XML**：文件不存在时框架生成完整文档；手写文件没有生成块时，第一次访问会把七条默认语句插到 `<mapper>` 开头，`findOne/findList` 等 SQL 在文件里可见、可改；只读 classpath / 纯内存模式（`generate-files=false`）不落盘，改写结果只在内存生效。
- **表结构变化只重写生成块**：标记头带结构指纹（生成器版本 + 表结构），指纹变了只替换 START/END 之间那一段；标记之前的注释、标记之后的自定义语句、缩进与空行逐字保留。
- **自定义语句按 id 调用**：`sqlFor`（只抽取不执行）、`selectList` / `selectOne` / `selectCount` / `selectPage` / `execute`；`<select>` 走查询，`<insert>/<update>/<delete>` 走写。自定义区可以覆盖同名默认 id（文件里后出现的同名语句优先）。
- **删除由 `delete` 语句决定**：有逻辑删除列时默认生成逻辑删除 `UPDATE`，没有时生成物理 `DELETE`；在自定义区覆盖 `delete` 即可换语义。
- **改默认 SQL 的姿势**：条件只改 `__autumn_where`、列清单只改 `__autumn_columns`，三条 select 跟着变；整条语句不满意就复制到标记之后重写同 id，框架下次刷生成块也不会动它。
- **懒加载 + 有界缓存**：构造引擎时不扫目录、不读任何文件；某个 (数据源, 命名空间, 表) 第一次访问才解析。磁盘文件按「修改时间 + 长度」自动重读，classpath / jar 内资源读一次固定；结构指纹也参与判活，表结构一变就重新生成并重新解析，不需要显式 `refresh`。系统属性 `-Dautumn.data.meta.sql.root=/path/to/sql` 可把语句根指到磁盘上（开发期改完即时生效）。
- 文件名与列名按忽略大小写对齐；`<mapper>` 壳与 `namespace` 可以省略，解析前会补齐。语法就是静态实体那套 XML 方言（`#{}/${}/<if>/<choose>/<foreach>/<where>/<trim>/<sql>/<include>/<resultMap>`），另加框架内置谓词 `changed('col')`（读实体变更集，区分「这一列没动」与「显式置 NULL」）。

语句文件相关配置：

```properties
autumn.data.meta.mapper.meta-directory=autumn-meta      # 工作根目录；语句 XML 落在其 sql/ 子目录；空串 = 纯内存
autumn.data.meta.mapper.generate-files=true             # 是否把生成/合并结果写回磁盘；false 时默认语句仍在内存补齐
autumn.data.meta.mapper.table-cache-size=10000          # 各缓存容器的条目上限，默认 10000
autumn.data.meta.mapper.default-list-limit=1000         # findList 未显式限行时的上限
autumn.data.meta.mapper.default-pool-name=default       # 静态门面走的那个池
autumn.data.meta.mapper.logic-delete-columns=deleted_flag,del_flag,is_deleted
autumn.data.meta.mapper.entity-codegen=auto            # auto | code | generic（native 里 auto 即 generic）
```

### GraalVM Native Image

动态层不靠「把 javac 塞进镜像」硬扛，而是换实体形态：

| `entity-codegen` | 实体 | 反射 | 适用 |
| --- | --- | --- | --- |
| `code`（JVM 默认） | 运行期生成的 `MetaClass` 子类，强类型 getter | 只在加载时读 `READER` / `NEW` | 完整 JDK |
| `generic`（native 默认） | `MetaClass` 本身（属性列表） | 0 | Native Image / JRE |

`auto` 按 `org.graalvm.nativeimage.imagecode` 自动选择；native 里若显式要求 `code`，启动即报错，
不会等到首次访问表才失败。GENERIC 下 CRUD / 语句 XML / `<sql>/<include>` / `<resultMap>` / 分页 /
逻辑删除 / json-path 序列化全部一致，只是 `entityType(table)` 返回 `MetaClass.class`、没有类型化 getter。
native 下**不把语句 XML 打进镜像**：这些文件按 `autumn.data.meta.mapper.meta-directory` 落在磁盘的
`sql/{数据源}/{命名空间}/` 下、由框架按需生成并归使用方所有（和 `spring-boot-native-samples` 里读
`/data/html/static.html` 那类示例同理，外部文件留在磁盘上即可），镜像里写死一份反而会过期。构建步骤与冒烟入口见
[docs/meta-dynamic-crud.md](docs/meta-dynamic-crud.md#graalvm-native-image)。

完整设计与语义见 [docs/meta-dynamic-crud.md](docs/meta-dynamic-crud.md)；上层怎么调有两个能直接跑的例子：
`autumn-data-jdbc-spring-boot-starter-using` 的 `MetaMapperXmlUsingTest`（六段走查：默认语句驱动 CRUD →
手写文件不改写 → 生成块刷新与自定义语句保留 → classpath 手写文件驱动动态 SQL → 语句类型/id 与删除语义 → 注入边界）
和它用的手写文件 `src/test/resources/meta/default/default/demo_order.xml`。

| 模块 | 运行时依赖 | 编译期依赖 | test |
| --- | --- | --- | --- |
| `autumn-data-common` | 纯 JDK（`java.sql` / `javax.sql`）+ `com.zaxxer:HikariCP`（optional，仅 `MetaDataSource` 回收检测用，不传递） | — | H2、JUnit 5、Mockito |
| `autumn-data-jdbc` | `autumn-data-common`、`com.zaxxer:HikariCP`、`org.slf4j:slf4j-api`、`javax.sql.DataSource`（JDK） | `jakarta.persistence:jakarta.persistence-api`（实体注解）、APT（待加） | H2 |
| `autumn-data-jdbc-spring-boot-starter` | `autumn-data-jdbc`、`autumn-data-common`、`autumn-data-meta-jdbc`、`spring-boot-starter-jdbc`、`spring-boot-autoconfigure` | `spring-boot-configuration-processor`（构建期，不入依赖树） | H2、spring-boot-test |
| `autumn-data-meta-jdbc` | `autumn-data-common`、`autumn-data-jdbc`、`com.zaxxer:HikariCP`（实体源码用 `StringBuilder` 拼装，无模板引擎依赖）；有界 LRU 缓存是 JDK 自带实现，无缓存库依赖 | — | H2 |

说明：
- `spring-boot-starter-jdbc` 传递引入 `spring-boot-starter` → `spring-boot` + `snakeyaml` + `logback` + `jsp` 等，以及 `spring-jdbc`/`spring-tx`，对「编译期生成 SQL、运行时零反射」的核心库属于额外负担。
- MyBatis 对照（本地 `~/.m2` 真实 POM 核对，见 [docs/dependency-layering.md](docs/dependency-layering.md)）：引擎本体 `org.mybatis:mybatis` 的 POM **没有任何 Spring 依赖**，只有 `ognl`/`javassist`/`cglib` 三个 `<optional>`（字节码已重定位进 JAR）；Spring 依赖只出现在桥接层 `mybatis-spring`（`spring-context`/`spring-jdbc` 且为 `provided`，不透传）与 autoconfigure 层。`mybatis-*` 全家族**零 Spring Data 依赖**——反而是 `spring-data-jdbc` 把 `mybatis`/`mybatis-spring` 标为 optional。autumn 采用同样分层。
- 根 POM 的 `spring-boot-starter-parent` 仅作 BOM/构建用途，不进入发布产物的依赖树。

## 运行示例（using 模块）

### 第 1 步（新环境必做）：把处理器装进本地仓库

```bash
./mvnw -B install -DskipTests                     # reactor 快照进本地仓库
```

`using` 模块的 `annotationProcessorPaths` 是按 `groupId:artifactId:version` 解析的，单模块构建
（`spring-boot:run -pl …`、IDE 从仓库取依赖）拿的是 `~/.m2` 里的 jar，不是 reactor 的 `target/classes`。
这一步不能省：漏装会直接报处理器解析不到；装了旧版则生成的实现类缺新方法、
`META-INF/autumn-data/repositories` 清单缺行，启动时表现为
`required a bean of type 'XRepository' that could not be found`（类在，只是没被注册）。
改过 `autumn-data-jdbc-compiler` 之后同样要重新 install，IDE 侧再 Rebuild 一次。

### 第 2 步：建表与种子数据

应用启动会自己执行脚本（`spring.sql.init.mode=always` + `schema-locations=classpath:main-schema.sql`），
脚本是 `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE` + 固定主键，可以反复跑。
要在启动前手工初始化，就对同一个库跑同一份脚本：

```bash
export H2_PASSWORD='<不写入仓库的本地库密码>'        # 凭据只走环境变量
java -cp "$H2_HOME/lib/h2.jar" org.h2.tools.RunScript \
    -url "jdbc:h2:tcp://localhost:9092/autumn_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE" \
    -user sa -password "$H2_PASSWORD" \
    -script autumn-data-jdbc-spring-boot-starter-using/src/main/resources/main-schema.sql
```

初始化后各表行数：`lib_author` 3、`lib_book` 6、`lib_category` 4、`lib_book_category` 9、`demo_order` 3；
`basic_user` 沿用该库既有数据（演示只读，不插不改）。

### 第 3 步：启动与验证

```bash
./mvnw spring-boot:run -pl autumn-data-jdbc-spring-boot-starter-using \
    -Dspring-boot.run.profiles=demo               # demo profile → H2 TCP 服务库
curl http://localhost:8080/training/user          # 单表 + 一对多装配
curl "http://localhost:8080/training/user/page?page=0&size=1"   # 分页（方言 limit/offset + 总行数）
curl -X POST "http://localhost:8080/training/order/batch?count=3&userId=1"  # 批量插入 + 生成键回填

curl http://localhost:8080/library/summary        # 四张表行数概览
curl http://localhost:8080/library/authors        # 1:n：作者连同书籍（两条语句，无 N+1）
curl http://localhost:8080/library/books          # n:1 + 1:n 同时装配
curl -G "http://localhost:8080/library/books/by-category" --data-urlencode "categoryName=武侠"   # n:1:n 两层关联路径：编译成两层 EXISTS，父行不重复
curl -G "http://localhost:8080/library/books/by-author" --data-urlencode "authorName=金庸"  # n:1 关联路径
curl "http://localhost:8080/library/books/page-sorted?page=0&size=2"   # 分页 + OrderBy
curl "http://localhost:8080/library/derived/first-top"                 # First/Top + Distinct 见 /library/derived/*
```

> 中文查询参数交给 `curl -G --data-urlencode` 编码，别手写百分号串。

配置约定（全项目只用 `.properties`，不用 yaml）：
- `application.properties`：连 `jdbc:h2:tcp://localhost:9092/autumn_security;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`。该库是安全项目在用的真实库，`basic_user` 是它的既有表（17 列），演示只读它、不往里插数据。`main-schema.sql` 只建示例自有表并用 `INSERT IGNORE` + 固定主键补种子：`demo_order`（1:n 子表）与图书馆四张表 `lib_author` / `lib_book` / `lib_category` / `lib_book_category`（覆盖 1:n、n:1、n:1:n 三种关联与 `int/long/String/Boolean/BigDecimal/Date/Timestamp` 全量字段类型）。
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
- MyBatis 式 XML 方言（`#{}` 绑定 + `${}` 标识符 + `<if>/<choose>/<foreach>/<where>/<trim>` + `<sql>/<include>` + `<resultMap>/<result>`），由 `autumn-data-common` 解析成同一棵 AST：静态实体走编译期生成 Java，动态表走运行期解释（SQL 路径不引模板引擎）
- 实体类源码同样不依赖模板引擎：`MetaEntitySource` 直接拼 Java 源码，配合 JDK `javax.tools` 编译加载；与动态 SQL 的 XML 解释器是两条独立的路
- 目标数据库：MySQL、PostgreSQL（方言可扩展；`db/` 下有两套同构的 schema 与初始化说明）
