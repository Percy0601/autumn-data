# 变更记录

本文收录 README 精简前的开发进度清单（迁移于 2026-11-23），保留当时的实现细节与技术取舍。
当前能力以 [README.md](../README.md) 与 [docs/meta-dynamic-crud.md](meta-dynamic-crud.md) 为准。

## 2026-11-25 动态层收敛（以本节为准，下面为历史记录）

- [x] 目录统一为 `{root}/{数据源}-default/{命名空间}-default/{spec,auto}`：`spec/xml` 手写只读，
      `auto/xml` 框架生成整份重写，`spec/vo` 只是使用方约定的 VO 目录（框架不读写）。
- [x] 删除 VO / shape 整套逻辑（`selectShape*` / `registerShapeStatement(s)` / `.vo.xml` /
      `MetaShape*`）、删除运行期 javac 代码生成（`MetaEntityCompiler` / `MetaEntitySource` /
      `MetaEntityStrategy` / CODE / GENERIC 双档）。实体统一为 common 的 `MetaClass`。
- [x] `MetaModelStore` 落地为 `spec/{表名}.json` 类定义：顶层 name / namespace / metaType /
      logicDeleteColumn / properties 全字段解析，定义优先、缺列由数据库补齐；读行按
      `properties[].type` 收口（`MetaValueReader.asType`）。
- [x] `MetaSqlGenerator` 不再维护 GENERATED START/END 块；auto XML 整份生成，指纹头
      `AUTUMN-DATA GENERATED fingerprint=...`，版本升到 4；spec 同 id 覆盖 auto。
- [x] 缓存重命名：`BoundedCache → MetaCache`、`BoundedCaches → MetaCaches`、
      `LruCache → LruMetaCache`（含文件名与测试）。
- [x] `SqlBuild` 从 meta-jdbc 迁到 common（compiler 生成代码共用），`entity-codegen` 配置删除。
- [x] using 模块清理 demo 业务（library / training / vo / 多数据源示例），只保留动态表
      HTTP 端点与集成测试；配置示例与文档同步重写。
- [x] 新增完整可复制示例：`docs/examples/meta-data/.../DEMO_ORDER.json` 与
      `.../spec/xml/DEMO_ORDER.xml`，由 `MetaExampleDefinitionTest` 在 H2 上端到端验证。
- [x] Spring Boot 父 POM 从 4.1.2 升到 4.2.0（4.2 线最新正式版），实际解析到 Spring Framework
      7.1.0；全 reactor 232 用例通过。

## 初始化阶段进度（原 README「当前进度」章节，逐条保留）

- [x] 根 POM / `.gitignore` / `.gitattributes` / Maven Wrapper
- [x] `autumn-data-jdbc` 模块骨架 + 多数据库 `Dialect`
- [x] 新增 `autumn-data-common`：收编 jdbc / meta 两套重复 `Dialect` 为公共底座，各模块改为依赖它
- [x] `autumn-data-meta-jdbc` 模块骨架（原 `autumn-data-meta`，方言已收编至 `autumn-data-common`）
- [x] `autumn-data-common` 新增 `MetaDataSource`：多连接池注册表（`name → DataSource`，懒加载 + 多池模式 3 分钟轮询回收空闲 Hikari 池 + 默认池保护）
- [x] `autumn-data-common` 新增 `GlobalScheduler`：框架内**唯一**的定时任务线程池（懒启动的单线程守护调度器，任务抛异常只记日志、不静默停止排期）；`MetaDataSource` 的空闲池轮询改为挂在其上，组件「停止」只 `cancel` 自己的 future，不再各自 `Executors.newXxx` 建池
- [x] Spring Data 派生查询（方法名约定）规范文档：[docs/spring-data-derived-query.md](spring-data-derived-query.md)
- [x] 依赖分层实证对照（MyBatis / Spring Data / starter-jdbc）：[docs/dependency-layering.md](dependency-layering.md)
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
- [x] 派生查询 / `@Query` 支持**自定义返回类型（VO / DTO 投影）**：`List<Vo>` / `Optional<Vo>` / `Page<Vo>`，VO 为 `public record`（规范构造器）或普通类（无参构造 + `setXxx`）；派生查询按实体字段映射 VO 成员并只 `SELECT` 所需列，`@Query` 按结果集列标签映射（成员名转下划线，`@Column(name)` 可覆盖）；生成显式构造 / setter 调用，运行期零反射、零列名匹配，成员对不上实体字段时在方法声明处报错（示例见 `autumn-data-jdbc-spring-boot-starter-using` 的 `demo/vo`）
- [x] compiler：解析不了的接口方法（未知属性/关键词、参数或返回类型不符、`Distinct`/`First`/`deleteBy`/两层以上关联）在**方法声明处**报错，不再静默跳过后由 javac 报「实现类未覆盖…」
- [x] 一次性取数上下文（`AutumnDataContextHolder`，按线程保存 + `wrap` 线程池透传）：分页关联加载三态（`DEFAULT` 阈值级联 / `CASCADE` 强制级联 / `DISABLED` 强制关闭）与**字段投影**（`selectFields` / `excludeFields` 运行期真裁剪 `SELECT` 列清单，主键与多对一外键编译期判定为不可省略，级联不被裁剪打断）；被最近一次取实体行的查询消费后自动清除，无需手动 `clear`
- [x] starter 多数据源接管（`autumn.data.meta.enabled=true`，默认关闭）：`SpringMetaDataSource` 继承 Spring 的 `AbstractDataSource` 成为应用唯一的 `DataSource`（配置排在 `DataSourceAutoConfiguration` 之前，Boot 建池自动退让），底层就是 `MetaDataSource` 注册表；默认池参数仍来自 `spring.datasource.*`（含 `spring.datasource.hikari.*` 调优项），额外池 `autumn.data.meta.pools.<名称>.*` 懒建 + 空闲回收，运行期 `register(name, supplier)` 可继续追加；Spring 事务只绑默认池，`@Transactional` 提交/回滚实测不变（using 模块有示例与集成测试）
- [x] `autumn-data-meta-jdbc` 动态表 CRUD：`MetaMapper` 静态门面 + `of(池, 命名空间)` 绑定客户端 + `DatabaseMetaData` 元数据快照 + `refresh` 热更新；查询条件 = 参数实体 + XML `<if>`，写入范围 = 实体变更集（`changed('col')`），删除语义由 XML 的 `delete` 语句决定（有逻辑删除列默认逻辑删除、否则物理删除）；96 个 H2 用例 + using 模块 6 个上层用法示例覆盖「建表 → 不重启读写 → 加列 refresh → 复合主键 → 默认语句落 XML（七条生成块，手写文件首次访问自动插入）→ 自定义语句保留/覆盖 → VO 语句独立 `.vo.xml` 且只创建一次 → 磁盘改 SQL 自动重读 → 缓存分级清理与 reload」
- [ ] 动态表 CRUD 接入 Spring 事务（当前单方法内自提交，跨方法 `@Transactional` 暂不生效）
- [ ] `OrderBy` 之外的排序入参（`findAllBy(Sort)`）与 `Pageable` 排序
- [ ] `autumn-data-meta-jdbc` 同步去 Spring 依赖（同样挪到各自 starter）
- [x] `autumn-data-meta-jdbc` 跨表 / 自定义结果形状查询：语句在 XML 上声明 `resultType="map"`（历史名字）或 `resultType="metaclass"`（显式名字），`selectShape/selectShapeOne/selectShapePage` 返回 `List<MetaClass>` / `Optional<MetaClass>` / `Page<MetaClass>`；**每条语句按结果列生成一个具体的 `MetaClass` 子类**（类名由语句 id 派生，包名带 schema/表名/列形状指纹，字段与 typed getter 真实声明，静态 `READER` 按列序号零反射读取），行上可直接读 `getClass().getName()/getPackageName()/getSimpleName()`，需要 `Map` 时 `row.toMap()`；用于 JOIN 其它表、分组聚合这类结果形状不由单表结构决定的查询；SQL 写在锚定表的 VO 语句文件（`{表名}.vo.xml`）里，参数实体 / `<if>/<foreach>` / `${}` 白名单 / 绑定槽编译 / 分页包子查询计数全部复用同一条链路；形状语句与实体入口互相显式拒绝（不静默丢列），时序值归一成 `java.time`，native 模式回退通用 `MetaClass`。示例：`MetaDataController#joinQuery`（`meta_demo_form` LEFT JOIN `meta_demo_customer` + 按客户聚合，`map`/`metaclass` 两种写法各一条语句，返回具体类名/包名）
- [x] `autumn-data-meta-jdbc`：**语句库** `MetaSqlFileStore`——实体语句 `{表名}.xml` + VO 语句 `{表名}.vo.xml`（`{根}/{数据源}/{命名空间}/` 下），首次访问生成七条默认语句（`findList/findPage/findOne/findById/save/update/delete`）的 `GENERATED START/END` 块；表结构变化只重写该块，标记之外的自定义语句逐字保留；VO 文件只在首次注册时写入，已存在则绝不覆盖；`SqlTemplate` 解释执行 common 的同一套 MyBatis XML 语法（另有内置谓词 `changed('col')`），`#{}` 解析期摘成 JDBC 绑定、`${}` 只放标识符白名单；命名语句走 `sqlFor/selectList/selectOne/selectCount/execute`，参数一律是 `MetaClass`
- [x] `autumn-data-meta-jdbc` SQL 三段：抽取（`MarkedSql`：文本 + 每槽列引用 + 本次实参）→ 再转换（`MetaSqlPlan`：类型判断、列查找、setter 一次做完，按形状缓存）→ 执行（零槽走 `Statement`，有槽走 `PreparedStatement`），动态形状也能吃到游标/执行计划复用
- [x] XML 方言下沉 `autumn-data-common`（`TemplateParser` / `TemplateNode` / `MapperXmlParser`），编译期生成器与动态运行期共用一棵 AST；**动态 SQL 只认 MyBatis 语法**（`<#if>` 这类模板引擎指令本来就不是合法 XML，`test` 表达式也只认 `a != null` / `a == 'x'` / `!a` / `a && b`，不再兼容 `name??` / `name?has_content`）
- [x] `autumn-data-meta-jdbc` 运行期生成实体类：`MetaMapper` 门面统一返回 `MetaClass`，实例是按 `DatabaseMetaData` 用 `StringBuilder` 直接拼 Java 源码、`javax.tools.JavaCompiler` **内存编译**、内存 `ClassLoader` 加载的 `MetaClass` 子类（真实字段/getter/setter + 类型化 `getProperties()`，逐行映射零反射）；不建临时目录、不落 `.class`，类与 ClassLoader 可随缓存淘汰被 GC；结构指纹决定包名，加列 `refresh` 后生成新类、旧实例不受影响；源码可配置落盘以便固化成静态类（**已移除 FreeMarker 依赖与 `.ftl` 模板**）
- [x] GraalVM Native Image：`MetaEntityStrategy` 两档，native 里 `auto` 自动退到 **GENERIC**（不生成类、不编译、零反射，行数据直接写通用 `MetaClass`），CRUD / 语句 XML / resultMap / 分页 / json-path 序列化语义不变；`entity-codegen` 可显式覆盖，native 下强制 `code` 启动即失败；语句 XML 按磁盘工作目录读取，不声明进镜像资源
- [x] 按动态定位收口：删除零引用的 `MetaConfiguration`（编译期静态 Mapper 注册、无界 `HashMap`）与 `MetaSqlSession`（JDK 动态代理反射、需预注册）；所有按表 / 按池增长的内存容器（表快照、手工登记覆盖、动态实体类、Mapper AST、池 client）统一走 `BoundedCache` 有界 LRU，默认上限 10_000
