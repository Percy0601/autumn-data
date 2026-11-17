# 动态表 CRUD（autumn-data-meta-jdbc）

面向“运行期设计出来的单据/基础资料”：**低代码开发平台的底层基座**——表单和基础资料的数据表
都是用户运行期设计出来的，服务不重启、工程不需要重新编译，就能按表名 + 列名单做查询、
保存和更新（实体类由框架在运行期生成并编译）。

架构上两条线职责分明：`autumn-data-jdbc` + `autumn-data-jdbc-compiler` 是**编译期生成实现、完全静态**
的仓储；本模块是**完全动态、轻量、弹性懒加载**的路径，不替代前者，也不引入任何编译期注册 /
预加载 / 反射代理。三条硬约束贯穿全文：**重活一律推迟到首次访问**、**所有缓存有界且 LRU 弹性**、
**表结构与表数量在运行期可以任意变化**。

## 分层

### 包分层

外层是**用户级 API**，里面按职责分五层，层与层之间单向依赖（engine → metadata/entity/sql → support）：

```text
xyz.wewin.autumn.data.meta.jdbc            用户级：MetaMapper 门面 / MetaQuery 条件 DSL / MetaSqlException / SqlBuild
  ├─ engine      MetaMapperEngine（池 → client，共享实体注册表）/ MetaMapperClient（一次调用的编排）
  │              MetaMapperOptions / PerThreadConnectionSource
  ├─ metadata    MetaTable（列快照）/ MetaTableRegistry（懒读 + 手工覆盖）/ MetaModelStore（元类定义 JSON）
  ├─ entity      MetaEntitySource（拼 Java 源码）/ MetaEntityCompiler（内存 javac）
  │              MetaEntityRegistry（结构指纹 → 类）/ MetaEntityColumns + MetaEntityReader（结果集映射）
  ├─ sql         MetaSqlBuilder（CRUD 现拼）/ MetaStatementStore + SqlTemplate（文件解释）
  │              MarkedSql（抽取产物）/ MetaPlanCompiler + MetaSqlPlan + MetaBindSlot + MetaTypeCoercion（再转换）
  │              MetaPlanCache（形状 → 计划）
  └─ support     BoundedCache / LruCache / BoundedCaches / MetaDirectories（根目录 + 三个固定子目录）
```

调用链只有一条：

```text
MetaMapper（静态门面，只持有 volatile engine）
  └─ MetaMapperEngine [有界 LRU：池名 → client]
       └─ MetaMapperClient（绑定一个池）
            ├─ MetaTableRegistry     表名 → MetaTable 快照 + 手工登记覆盖                [有界 LRU ×2]
            ├─ MetaEntityRegistry    结构指纹 → 已编译 MetaClass 子类 + READER          [有界 LRU]
            ├─ MetaSqlBuilder        按 MetaTable 现拼 CRUD SQL，标识符只来自快照
            ├─ MetaStatementStore    表名 → Mapper AST；已有则读、缺失生成一次           [有界 LRU]
            ├─ MetaPlanCache         形状指纹 + SQL 文本 → 执行计划                     [有界 LRU]
            └─ AutumnJdbc            复用执行层：query / page / count / update / 生成键 / 预编译计划

所有 [有界 LRU] 都走同一个 BoundedCache 接口 / BoundedCaches 工厂，上限由 table-cache-size 统一配置。
本模块没有任何「编译期生成的 Mapper 注册中心」或接口动态代理：静态注册路径（MetaConfiguration /
MetaSqlSession）已按动态定位移除，SqlBuild 只作为与静态侧共用的无状态值对象保留。
```

核心模块不依赖 Spring；Spring Boot 集成在 `autumn-data-jdbc-spring-boot-starter` 的
`AutumnDataMetaMapperAutoConfiguration` 里：把引擎装进静态门面，并用 `SpringConnectionSource`
接上容器里的 `DataSource`（也就是 `spring.datasource.*` 建出来的那个池），见「Spring Boot 连接池与事务」。

## 为什么复用 MetaClass / MetaProperty

`autumn-data-common` 已经有一套 JSON 元模型：`MetaClass`（name / namespace / metaType /
properties）、`MetaProperty`（name / type / typeName / value）。动态表的物理列信息（JDBC 类型、
可空、主键、自增、默认值）直接扩到 `MetaProperty` 上，不再另建 `MetaColumn`：

- `MetaTable` 是 `MetaClass` 的物理表视图：`name()` = `MetaClass.name`，`schema()` = `namespace`；
- `MetaMapper` 的查询**直接返回 `MetaClass`**，实例是运行期生成的实体子类（见下一节）；
- `MetaClass` 自身补上了 `get/set`、类型化 getter、`toMap()` / `toJson()` 等公共契约，
  通用的「属性列表」实现与生成子类的「直接字段访问」实现共用同一层 API；
- 表名/列名/类型/主键只有一份来源，避免 JSON 元模型与 JDBC 元模型各说一套。

## 介于完全动态与完全静态之间：运行期生成 MetaClass

编译期写死的实体类应付不了「表单设计器运行期建表」；全 Map 的写法又把类型丢光，
后面做校验、转换、序列化时到处要猜。这一版取中间态：

1. 首次访问某张表，从 `DatabaseMetaData` 拿到列快照（`MetaTable`）；
2. `MetaEntitySource` 用 `StringBuilder` 直接拼一份 Java 源码（没有模板引擎）：
   每列一个**真实类型**字段 + getter/setter，`extends MetaClass`；
3. `MetaEntityCompiler` 用 JDK `javax.tools.JavaCompiler` **全程内存编译**：内存源码对象 →
   javac 输出到 `byte[]` → 独立内存 `ClassLoader` 的 `defineClass`。不建临时目录、不落 `.class`，
   因此 LRU 淘汰掉实体模型后，对应的 Class 与 ClassLoader 没有活引用就能被 GC，元空间可回收
   （这一点对「表结构频繁变化」很关键：缓存有界，物理资源也必须同步有界）；
4. 生成类里的 `READER` 是写死的 `rs.getObject(index)` + 直接 setter 调用，
   **逐行映射零反射**；另有 `NEW`（`Supplier<MetaClass>`）供 `<resultMap>` 逐行构造空实体；
   反射只在「加载类时取一次静态字段」这一步发生；
5. `getProperties()` 由生成类把真实字段摊成 `MetaProperty`（带 `type` / `jdbcType` /
   主键 / 自增 / 可空），`MetaUtil.toMap/toJson` 与后续类型校验照常可用。

**生成字段的命名避让**：列名若会与 `MetaClass` 自身契约撞名（`name`、`namespace`、`metaType`、
`properties` 等），类型化 getter 改叫 `getXxxColumn()`，例如 `name` → `getNameColumn()`。
否则 `getName()` / `getProperties()` 会覆写基类方法：轻则 `setName(TABLE_NAME)` 被劫持、
表名写进业务列，重则因返回类型不兼容导致 javac 直接失败。按列名读写走的是 `get/set` 的
switch，仍用原始列名，业务语义不变。

因此 `MetaClass` 既是公共契约（调用方只需面向它编程），又是一个知道自己每个字段类型的实体；
`MetaMapper.entityType(table)` 能拿到生成出来的 `Class<? extends MetaClass>`。

**结构指纹参与包名**：列顺序、名字、JDBC 类型、主键/自增/可空都会算进 SHA-256 指纹，
生成类的包名是 `...meta.jdbc.entity.generated.v<指纹>`。加一列后 `refresh`，指纹变化 → 生成另一个包下的新类，
旧实例继续由各自的 ClassLoader 持有，不会被重定义，也不会新旧实例互相 ClassCastException。
同构的表只编译一次，多个数据源池共享一个 `MetaEntityRegistry`。

生成的源码可以落盘（`meta-directory` 的 `entity/` 子目录），产出的是一份可读、可改、能固化成静态实体的
`.java`；同目录下的 `meta-class/` 放元类定义 JSON，`mapper/` 放 Mapper
文件。运行期需要完整 JDK（JRE 上没有 `javax.tools`，会明确报错）。

> **部署注意**：javac 编译生成源码时需要能从 `java.class.path` / `CodeSource` 找到
> `MetaClass` 等依赖类。普通 classpath 启动（含 Maven Surefire 的 manifest-only jar）已处理；
> Spring Boot 可执行 jar 把依赖放在 `BOOT-INF/lib` 的嵌套 jar 里，`CodeSource` 是 `jar:` URI，
> 目前会被跳过，javac 会报「找不到依赖类」。这类部署需要把依赖展开到文件系统（例如
> `PropertiesLauncher` 的 `loader.path`），或后续给编译器补一个从 ClassLoader 读 `.class`
> 资源的 JavaFileManager。至少不会因为 `jar:` URI 抛 `ExceptionInInitializerError`。
> **这种部署如果还要跑 native，直接用下面的 GENERIC 策略，不经过 javac。**

## GraalVM Native Image

运行期 javac + `defineClass` 与 Native Image 的 closed-world 模型正面冲突：镜像里没有
`java.compiler`（`ToolProvider.getSystemJavaCompiler()` 返回 null），进程内也不允许定义新类。
因此 `MetaEntityStrategy` 给了两档，**native 不是靠配置硬试，而是换一条不产生新类的路径**：

| 策略 | 实体形态 | 反射 | 适用 |
| --- | --- | --- | --- |
| `CODE` | 生成的 `MetaClass` 子类，强类型 getter | 仅加载时读 `READER` / `NEW` 两个静态字段 | 普通 JVM（完整 JDK） |
| `GENERIC` | `MetaClass` 本身（属性列表） | 0 | Native Image、JRE、无 javac 环境 |

`auto`（默认）按系统属性 `org.graalvm.nativeimage.imagecode` 判断：native 里自动选
`GENERIC`，普通 JVM 选 `CODE`。也可以显式配：

```properties
# auto（默认）| true/code（强制生成，native 下启动即报错）| false/generic（强制通用实体）
autumn.data.meta.mapper.entity-codegen=auto
```

GENERIC 下 `MetaClass` 的 `getProperties()`、`set/get`、`toJson()`（json-path，原生支持
native）、`MetaQuery`、Mapper `<sql>/<include>`、`<resultMap>`、分页、逻辑删除全部照旧；
`MetaMapper.entityType(table)` 返回 `MetaClass.class`，`MetaEntityModel.source()` 为 null。
每行深拷贝一份属性列表（值存放在 `MetaProperty` 上），因此行与行之间不共享状态；
读出来的行同样 `clearChanged()`，部分更新语义不变。

**native 构建要点**：

- `javax.tools` 不可达：`MetaEntityRegistry` 通过类名反射调用代码生成器，类名在运行期拼装
  （避免 native-image 把常量 `Class.forName` 自动注册成反射目标），native 下策略是 GENERIC，
  这段不会执行；构建报告里不应再出现 `jdk.compiler`（实测镜像从 62.7 MiB 降到 48.3 MiB）。
  不要显式把 `entity-codegen` 配成 `code`。
- 资源：**不声明** native-image 资源包含。Mapper XML 是使用方的资产，按
  `autumn.data.meta.mapper.meta-directory` 落在磁盘的 `mapper/` 下，native 运行期直接按路径读
  （`Files.readString`）、缺失时照样生成一份，和 `spring-boot-native-samples` 里读
  `/data/html/static.html` 那类外部文件一个路子；把某个版本的 SQL 塞进镜像只会让它过期。
  确实想内置一批默认语句，再按 `-H:IncludeResources=meta-mappers/.*` 自行声明即可。
- 驱动 / 连接池按各自官方的 native 配置准备；H2 自带 `JdbcDataSource`（本次冒烟用的就是它），
  PostgreSQL 用 `org.postgresql.Driver`。

仓库内有一个不依赖 JUnit / Spring 的冒烟入口
`autumn-data-meta-jdbc/src/test/java/.../nativeimage/NativeImageSmoke.java`，构建方式：

```bat
cd autumn-data-meta-jdbc
mvn -q -am -DskipTests test-compile
mvn -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target\native-cp.txt

set /p DEPS=<target\native-cp.txt
native-image --no-fallback ^
  -cp "target\classes;target\test-classes;%DEPS%" ^
  -H:Name=native-smoke -H:Path=target ^
  xyz.wewin.autumn.data.meta.jdbc.nativeimage.NativeImageSmoke

target\native-smoke.exe
REM 期望输出 NATIVE_SMOKE_OK strategy=GENERIC type=xyz.wewin.autumn.data.common.meta.MetaClass
```

### 投影查询

命名语句常只 SELECT 部分列。生成类的 `read` 先用 `MetaEntityColumns.index(rs)` 建
「小写列标签 → 序号」索引，缺列就跳过、字段保持 null，不会因 `rs.getObject("未投影列")`
抛 `SQLException`，也不会串位；同时兼容 H2/Oracle 给大写列名、手写 SQL 用小写列名的差异。

## API

```java
MetaMapper.install(MetaMapperEngine.forDataSource("default", dataSource));

// 查询（返回的就是运行期生成、带真实字段类型的实体）
Optional<MetaClass> row = MetaMapper.findById("form_order", 1L);
Class<? extends MetaClass> type = MetaMapper.entityType("form_order");   // 动态生成出来的类
MetaMapper.findOne("form_order", MetaQuery.by("order_no", "A001"));
MetaMapper.findList("form_order", MetaQuery.by("status", 1)
        .and("create_time", MetaQuery.Op.GE, from)
        .orderBy("create_time", MetaQuery.Direction.DESC));
MetaMapper.findList("form_order", MetaQuery.all().isNull("remark"));      // 空值判断走专门的方法
MetaMapper.findPage("form_order", MetaQuery.by("status", 1), Pageable.of(0, 20));
MetaMapper.count(...); MetaMapper.exists(...);

// 新增：实体上赋过值的列才进 INSERT，自增主键回填到同一个实体上
MetaClass order = MetaMapper.newEntity("form_order");
order.set("order_no", "A001");
order.set("amount", "12.50");
MetaMapper.save(order);
MetaMapper.saveAll(List.of(order, MetaMapper.newEntity("form_order")));   // 列一致走 JDBC batch

// 部分更新：改过的列才进 SET，赋 null 就是真的写 NULL
order.set("status", 2);
MetaMapper.update(order);
MetaClass byKey = MetaMapper.newEntity("form_order");                     // 复合主键按主键读写
byKey.set("tenant", "T1");
byKey.set("order_no", "A001");
MetaMapper.findByKey(byKey);

// 多数据源：显式绑定池
MetaMapper.of("tenant-a").findPage("form_order", MetaQuery.all(), Pageable.of(0, 20));

// 命名语句走 client（id 就是 Mapper 文件里的语句 id，参数也是实体）
MetaMapperClient client = MetaMapper.defaultClient();
MetaClass params = MetaMapper.newEntity("form_order");                    // 或 new MetaClass()
params.set("status", 1);
client.selectList("form_order", "selectList", params);
client.selectOne("form_order", "byOrderNo", params);
client.selectCount("form_order", "countDraft", params);
client.execute("form_order", "voidByNo", params);
MarkedSql sql = client.sqlFor("form_order", "selectList", params);        // 只看文本与绑定值，不执行

// 手写 SQL：不建文件、不注册，即查即写（#{...} 绑定、${table} 由方言填表名）
MetaClass q = new MetaClass();
q.set("status", 1);
q.set("minAmount", new BigDecimal("50"));
List<MetaClass> rows = MetaMapper.findListBySql("form_order",
        "SELECT id, order_no, amount FROM ${table} "
        + "WHERE status = #{status} AND amount >= #{minAmount} ORDER BY amount DESC", q);
Page<MetaClass> page = MetaMapper.findPageBySql("form_order",
        "SELECT id, order_no, amount FROM ${table} WHERE status = #{status}", null, q, Pageable.of(0, 20));
long total = MetaMapper.countBySql("form_order",
        "SELECT COUNT(*) FROM ${table} WHERE status = #{status}", q);
int updated = MetaMapper.executeBySql("form_order",
        "UPDATE ${table} SET remark = #{remark} WHERE id = #{id}", q);
MarkedSql preview = MetaMapper.sqlForText("form_order", "SELECT * FROM ${table} WHERE id = #{id}", q);

// 也可以把手写 SQL 注册成命名语句，之后按 id 复用（同 id 覆盖，refresh 不撤销）
client.registerStatement("form_order", "hotOrders",
        "SELECT id, order_no, amount FROM ${table} WHERE status = #{status} ORDER BY amount DESC");
client.selectList("form_order", "hotOrders", params);
client.unregisterStatement("form_order", "hotOrders");

// 缓存分级清理与重新加载
MetaMapper.clearTableCache("form_order");       // 只丢 MetaTable 快照，下次访问回源 DatabaseMetaData
MetaMapper.clearEntityCache("form_order");      // 只丢编译出来的 MetaClass 子类，下次重新生成 + 编译
MetaMapper.clearStatementCache("form_order");   // 只丢语句 AST 与执行计划
MetaMapper.clearCache("form_order");            // 三样一起清（= 老 refresh 的语义）
MetaClass rebuilt = MetaMapper.reload("form_order");  // 清缓存 + 立即回读 + 立即重编（失败当场暴露）
MetaMapper.cacheStats();                        // 各级条目数与上限，运维巡检用
```

列以外的东西（`<if test>` 的开关、`<foreach>` 的集合、`${orderBy}` 的排序字段）照样写在同一个参数实体上：
它们不是列，就落在实体的动态属性里，`getProperties()` 会一并摊出来。表名也读自实体，所以
`client.execute(table, id, entity)` 里 `table` 与 `entity.getName()` 一致即可。

**没有 delete。** 应用侧不提供物理删除；作废走状态列（`entity.set("status", "VOID")` + `update`）。
若表里有 `deleted_flag` / `del_flag` / `is_deleted`，查询自动追加 `= 0`（可配置关闭）。

## 元数据快照

- 首次访问某张表时从 `DatabaseMetaData` 读取列、主键、自增、可空、默认值，直接构造
  `MetaClass`（`name` = 表名、`namespace` = schema、`metaType = ENTITY`、`properties` = 列）；
- `MetaTable` 不可变，缓存键大小写不敏感；
- 缓存是**分级的**，改完表之后按需要清哪一层就清哪一层，不必每次都把实体类换掉：

| 入口 | 清掉什么 | 何时用 |
| --- | --- | --- |
| `clearTableCache(table)` | 只清 `MetaTable` 快照（含手工覆盖），下次访问回源 `DatabaseMetaData` | 怀疑结构读错了 / 表刚被重建，但不想让所有行换 Class |
| `clearEntityCache(table)` | 只清该表编译出的 `MetaClass` 子类，下次访问重新生成 + 编译 | 换了代码生成器 / 想强制重编一次 |
| `clearStatementCache(table)` | 只清 Mapper 语句 AST 与执行计划 | 改了 Mapper 文件、想丢掉旧 AST；不动结构与实体类 |
| `clearCache(table)` | 以上三样一起清（`refresh(table)` 是它的别名） | 建表 / 加列 / 删列提交之后的标准动作 |
| `reload(table)` | 先清三样，再**立即**回读元数据并重编实体类 | 需要“现在就成功”：表被删、元数据读失败、javac 编译失败会当场抛出，而不是等下一次查询 |
| `reloadAll()` | 当前池里已有快照的表逐张 `reload` | 批量改表之后的一次性重建 |
| `cacheStats()` | —（只读诊断） | 打印各级条目数与上限 |

`MetaMapper` 静态门面提供同名方法（默认池）；`MetaMapper.clearAllCaches()` 走引擎、清掉所有已实例化池的缓存。
代码里 `registerStatement` 注册的语句由代码持有，以上任何清理（包括 `refresh`）都不会撤销它们。

- 旧快照与旧生成类上正在执行的语句不受刷新影响，刷新只影响下一次解析。

## 有界缓存（表数量无上限）

低代码场景下表单设计器能建出成千上万张表，进程内**按表或按池增长的容器全部要有边界**：

| 缓存 | key | 条目重量 | 淘汰后再访问的代价 |
| --- | --- | --- | --- |
| `MetaTableRegistry.cache` | 表名（大小写不敏感） | 一份不可变列快照 | 重读一次 `DatabaseMetaData` |
| `MetaTableRegistry.overrides` | 表名 | 使用方手工登记的结构覆盖 | 回源到 `DatabaseMetaData`；需长期保真就调大上限或重新 `register` |
| `MetaEntityRegistry` | 表名 + 结构指纹 | 一个编译出的 `Class` + 内存 ClassLoader | 重新生成/编译一份同类名的类（新 ClassLoader） |
| `MetaStatementStore.cache` | 表名 | 一棵 Mapper XML AST | 重读已有的 XML 文件（**不会重写**） |
| `MetaStatementStore.declared` | 表名 → 语句 id | 一条代码注册的手写语句 AST | 丢失后需代码重新 `registerStatement`（不落盘、无文件可回源） |
| `MetaMapperEngine.clients` | 池名 | 一套 Registry + 连接源 | 按需重建 client（旧 client 上执行中的操作持有引用，不受影响） |

其中实体类缓存最重，只堵表结构快照没有意义。以上统一走 `BoundedCache`，默认上限
**10_000**，触顶淘汰最久未访问的条目；淘汰是同步的，`size()` 恒不超过上限。
丢掉的都是可重建的派生数据，LRU 淘汰不影响正确性，也不影响已取到旧快照/旧类实例的执行中查询。
运行期编译改为全内存后不再产生临时目录与 `.class`，缓存淘汰能真正回收元空间，
不会出现「内存有界、物理资源无界」。

`refresh` 与「在途加载」并发时（读元数据 / javac 都很慢）也保证不回写旧值：
失效会把该 key 的加载占位一并摘掉，慢加载结束时发现自己已被取代，就只把旧结果交给那一次调用者，
不写回缓存；之后的新请求重新读新结构。`invalidate` / `invalidateAll` / `invalidateIf` 与
`put` 都遵循这条语义，避免「改表 → refresh」之后又被慢查询把旧表结构塞回来。

```properties
autumn.data.meta.mapper.table-cache-size=10000
```

设计上的三点：

- **API 对齐 Caffeine，方便将来换实现**。`BoundedCache` 的 `getIfPresent` / `get(key, mappingFunction)` /
  `put` / `invalidate` / `invalidateAll` / `estimatedSize` / `cleanUp` / `asMap` 与
  `com.github.benmanes.caffeine.cache.Cache` 同名同义；`invalidateIf` / `maximumSize` 两个扩展方法在
  Caffeine 下也各有一行等价写法（`asMap().keySet().removeIf(...)`、
  `policy().eviction().get().getMaximum()`）。`asMap()` 的差别是 LRU 返回只读快照、Caffeine 返回
  可写 live view，本层只做诊断遍历。当前实现 `LruCache` 是 JDK-only：
  `LinkedHashMap(accessOrder=true)` 记顺序 + 一把 `ReentrantLock` 保护顺序与淘汰，
  加载函数在锁外执行（读元数据 / javac 编译不阻塞别的 key 命中），并用 pending 表保证
  「同一个 key 并发未命中只加载一次」。换成 Caffeine 时只需新增一个适配器实现该接口、
  改 `BoundedCaches` 工厂的一行返回值，调用方不用动。
- **没有直接在 `ConcurrentHashMap` 上加淘汰**：CHM 不维护访问顺序，靠外部链表记顺序时，
  「读命中」与「淘汰」之间必然有竞态，容易短暂超上限或误删刚被访问的条目。这里把顺序和
  淘汰收进同一把锁，语义简单、可验证（`BoundedCacheTest` 里有并发单飞与上限断言）。
- **手工登记同样不永久常驻**。低代码场景下「表」本身就是运行期产物，任何登记都不该豁免于
  容量治理：`MetaTableRegistry.register(...)` 的覆盖也走有界 LRU，触顶淘汰后 `require` 会像
  普通表一样懒加载回源到 `DatabaseMetaData`（若该表不存在则报「table not found」）。
  `refresh(table)` 仍按原语义同时清掉该表的快照与覆盖。

## Mapper 文件与命名语句

规则只有一条：**文件存在就用文件，不存在才按表结构生成一次**。

1. 查找顺序：`<meta-directory>/mapper/<表名>.xml` → classpath `meta-mappers/<表名>.xml`；
   两级都按「表名原样 / 小写 / 大写」三个文件名依次找（H2、Oracle 的元数据表名是大写，手写文件多按 MySQL 习惯写小写）；
2. 都没有且 `generate-files=true`：按 `MetaTable` 的列结构生成一份，写到 `mapper/` 子目录后使用；
   `meta-directory` 为空串时只在内存里用、不落盘；
3. 生成文件带 `generated-by: autumn-data-meta-jdbc` 标记。**文件存在就直接使用，永不回头覆盖**：
   改表加列后 `refresh` 只是丢缓存重读文件，要按新结构重来就删文件手工重写。文件归使用方所有。

`client.refresh(table)` 与“重写用户文件”是两件事，故意不联动——否则用户改过的语句会被表结构冲掉。

### 手写 SQL（不落文件）

低代码平台里很多查询是一次性的（设计器里临时拼条件、报表按客户要求改一版），为它们各建一个
XML 文件并不划算。`MetaMapper` 因此补了一条「直接给一段 SQL」的入口：**解析、绑定、类型收口、
计划缓存与文件里的 `<select>` 完全共用一套**，只是没有 XML 标签。

```java
// 一次性：不建文件、不注册
MetaMapper.findListBySql("form_order",
        "SELECT id, order_no, amount FROM ${table} WHERE status = #{status}", params);
MetaMapper.findPageBySql("form_order",
        "SELECT id, order_no, amount FROM ${table} WHERE status = #{status}", null, params, Pageable.of(0, 20));
MetaMapper.countBySql("form_order", "SELECT COUNT(*) FROM ${table} WHERE status = #{status}", params);
MetaMapper.executeBySql("form_order", "UPDATE ${table} SET status = #{status} WHERE id = #{id}", params);

// 命名：注册后可以按 id 复用，也可以被 selectList/selectOne/sqlFor 看到
client.registerStatement("form_order", "hotOrders",
        "SELECT id, order_no, amount FROM ${table} WHERE status = #{status} ORDER BY amount DESC");
```

语义与坑位：

- `#{name}` → JDBC `?`（值永不进 SQL 文本）；`${name}` → 标识符片段，展开结果仍过白名单，
  `${table}` 由客户端按方言填成 `"form_order"`；
- **不接受裸 `?`**：没有名字的槽判不了列类型、进不了计划缓存，解析阶段就直接报错并提示改
  `#{name}`（而不是等驱动报「占位符个数对不上」）；
- 注释（`--` / `/* */`）与单引号字面量里的 `#{...}` 不当占位符；
- 开头关键字决定入口：查询入口只收 `SELECT` / `WITH`，写入口只收 `INSERT` / `UPDATE`，
  `DELETE` 一律拒绝（动态层不做物理删除）；
- 分页时 `countSql` 留空会自动包成 `SELECT COUNT(*) FROM (原查询) autumn_page_count`；
  内容与计数各自按自己的占位符顺序绑定，所以 `countSql` 不必与内容查询参数顺序一致；
- 注册语句由代码持有，`refresh` / `clearCache` 不会撤销；同 id 重复注册即覆盖；
  注册本身不要求表已存在（表可能是稍后才被设计出来的）。

### 谁读文件、谁不读

| 入口 | SQL 来源 | 说明 |
| --- | --- | --- |
| `findById` / `findByKey` / `findList` / `findPage` / `count` / `save` / `saveAll` / `update` … | `MetaSqlBuilder` 现拼 | 条件运算符是开集（`>` / `LIKE` / `IN` / `BETWEEN` / 排序…），固定文件表达不了，因此这条路**完全不读文件** |
| `sqlFor` / `selectList` / `selectOne` / `selectCount` / `execute` | 代码注册的语句优先，其次 Mapper 文件 | 语句 id 就是注册时的 id / XML 里的 `id`；`<select>` 走查询，`<insert>`/`<update>` 走 `execute`，`<delete>` 拒绝执行 |
| `findListBySql` / `findOneBySql` / `findPageBySql` / `countBySql` / `executeBySql` / `sqlForText` | 调用方当场给的文本 | 不读文件、不注册；`#{...}` / `${...}` 语义与文件一致 |
| `metaFind` / `metaSave` / `metaUpdate` / `metaDelete` | `meta/{数据源}/{命名空间}/{表}.xml`，缺语句时回退 `MetaSqlBuilder` 现拼（`metaDelete` 只退化逻辑删除） | 见下一节；同一张表在不同库/租户下各有一份语句 |

生成的文件本身就是一份可跑的样例语句（`selectList` / `selectCount` / `insert` / `updateByKey`），
逐列展开成 `<if>`；投影、排序、运算符想改就直接改这份文件。下面是生成内容的节选：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- generated-by: autumn-data-meta-jdbc -->
<mapper namespace="dynamic.form_order">
    <select id="selectList">
        SELECT "id", "order_no", "amount", "status", "create_time", "deleted_flag" FROM "form_order"
        <where>
            AND "deleted_flag" = 0
            <if test="id != null">AND "id" = #{id}</if>
            <if test="order_no != null">AND "order_no" = #{order_no}</if>
            <if test="status != null">AND "status" = #{status}</if>
        </where>
        ORDER BY "id"
    </select>
    <insert id="insert">
        INSERT INTO "form_order"
        <trim prefix="(" suffix=")" suffixOverrides=",">
            <if test="order_no != null">"order_no",</if>
            <if test="status != null">"status",</if>
        </trim>
        <trim prefix="VALUES (" suffix=")" suffixOverrides=",">
            <if test="order_no != null">#{order_no},</if>
            <if test="status != null">#{status},</if>
        </trim>
    </insert>
    <update id="updateByKey">
        UPDATE "form_order"
        <trim prefix="SET " suffixOverrides=",">
            <if test="status != null">"status" = #{status},</if>
        </trim>
        WHERE "id" = #{id}
    </update>
</mapper>
```

### 按「数据源 + 命名空间」放语句：`metaFind` / `metaSave` / `metaUpdate` / `metaDelete`

`<meta-directory>/mapper/<表名>.xml` 的寻址键只有表名，一张表只能有一份语句。低代码/多租户场景下
同一张表常常在多个库、多个业务分组里各有一份写法，于是另有一层把**数据源与命名空间编进路径**的语句库：

```
src/main/resources/meta/{datasource}/{namespace}/{table}.xml
```

同一张 `form_order` 在 `default/default`、`tenant-a/erp` 下各有一份，互不覆盖。四个操作各有两套 API：

```java
// 一套：默认数据源（= 静态门面绑定的池名）+ 默认命名空间（"default"）
MetaMapper.metaFind("form_order", MetaQuery.by("status", 1));
MetaMapper.metaSave(entity);
MetaMapper.metaUpdate(entity);
MetaMapper.metaDelete(entity);

// 另一套：数据源 / 命名空间 / 表 / 语句 id / 参数全部显式
MetaMapper.metaFind("tenant-a", "erp", "form_order", "findByNo", params);
MetaMapper.metaUpdate("tenant-a", "erp", "form_order", "voidById", params);
```

**语句 id 是约定**：默认那套按操作名找 `metaFind` / `metaSave` / `metaUpdate` / `metaDelete`；
完整那套的 id 由调用方给（可以是这四个约定名，也可以是 `findByNo` 这类自定义语句）。

**缺文件、缺语句不算错**：`metaFind` / `metaSave` / `metaUpdate` 回退到按元数据现拼，
行为与 `findList` / `save` / `update` 一模一样——所以可以先上线、后补语句，也可以只覆盖其中一两个操作。
`metaDelete` 是例外：动态层**不会自己拼 `DELETE`**，只有文件里显式写了 `<delete id="metaDelete">`
（或写成 `<update id="metaDelete">` 的逻辑删除）才执行；没有该语句时，表配置了逻辑删除列就退化成
`UPDATE ... SET deleted = 1 WHERE 主键`，两者都没有则报错并要求写语句。

**条件怎么叠**：默认那套只拿 `MetaQuery`，无法把开集运算符塞进固定文件，所以约定文件里的 `metaFind`
写成不依赖外部参数的「基础语句」（投影、连接、固定过滤含逻辑删除），调用方这次的筛选条件与排序由
框架包成派生表叠在最外层：

```sql
SELECT * FROM ( <文件里的基础语句> ) autumn_meta_base WHERE "status" = ? ORDER BY "id" DESC
```

没有条件也没有排序时直接用原文，不额外包一层。派生表只做投影透传，所以条件里引用的列必须出现在
基础语句的投影里；逻辑删除过滤不在外层补——那是文件那份语句自己的责任。要带参数（固定租户、区间条件）
就走完整那套，`#{name}` 与 `MetaQuery` 是两条互不掺混的路。

**懒加载**：`MetaSqlFileStore` 构造时不扫目录、不读文件；某个 (数据源, 命名空间, 表) 第一次被访问才读盘
解析，结果进有界 LRU（默认 10_000，随表的数量增长而淘汰冷条目）。

**动态更新**：每次访问比对来源的「最后修改时间 + 长度」，改过就重新读、重新解析，不需要重启；
`-Dautumn.data.meta.sql.root=/path/to/resources/meta` 可以把根目录指到磁盘上（开发期改完即时生效），
classpath 里以 jar 条目存在的文件不可变、读一次即固定。`clearMetaSqlCache(ds, ns, table)` 用于手工重读，
`metaStatementIds(ds, ns, table)` 列出可用 id（错误信息里也会带上）。

**宽松解析**：文件可以是完整的 MyBatis 写法（`<?xml?>` + `<mapper namespace="...">`），也可以只写几条语句、
省掉 `<mapper>` 壳（缺的壳与 `namespace` 由解析前补上）。语句内容还是同一套方言，
`<resultMap>` 也照样生效——按文件里声明的映射逐行取值。

### 方言

文件内容就是静态实体用的那套 XML 方言（`autumn-data-common` 的 `TemplateParser`），同一段 XML
在编译期生成器和运行期解释器里产出同样的 SQL 文本。手写语句不受生成文件的逐列等值限制，
比较、区间、`IN` 用的还是同一套标签（标识符引号按方言，这里是双引号示例）：

```xml
<select id="draftOrders">
    SELECT ${table}."id", ${table}."order_no" FROM ${table}
    WHERE "deleted_flag" = 0
    <if test="minAmount != null">AND "amount" &gt; #{minAmount}</if>
    <if test="ids != null">AND "id" IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
    </if>
    ORDER BY "id" DESC
</select>
```

- `#{name}` → `?` + JDBC 绑定，**在解析阶段就被摘成参数节点**，值永远不进 SQL 文本；
- `${name}` → 标识符拼接（表名、列清单、排序方向），展开结果必须过标识符白名单，
  引号、分号、注释符、比较符一律拒绝；`${table}` 已由客户端按方言填好引号后的表名；
- `<if test>` / `<choose>/<when>/<otherwise>` / `<foreach collection item separator open close>` /
  `<where>` / `<trim prefix suffix prefixOverrides suffixOverrides>`；`<where>` 是 `<trim>` 的语法糖，
  整段为空时连 `WHERE` 带它的绑定一起退回；
- `test` 表达式只认 MyBatis 写法：`name != null`、`name == 'active'`、`name != ''`、`!expr`、
  `a && b`（`&&` 需写成 `&amp;&amp;`）。FreeMarker 的 `name??` / `name?has_content` / `name?size`
  已移除，写了会按未知标识符在解析阶段报错；
- **SQL 片段**：`<sql id="allColumns">...</sql>` 定义片段，`<include refid="allColumns"/>` 引用；
  `refid` 支持 `namespace.id`（取最后一段匹配），解析阶段直接内联成 AST，运行期没有额外开销；
  片段互相引用会做环检测，`a -> b -> a` 直接报错；`<include>` 里的 `<property>` 子标签暂不支持；
  生成的 Mapper 文件默认就带一个 `allColumns` 片段，`selectList` 用 `<include>` 复用；
- **字段映射**：`<resultMap id="orderView" type="demo_order">` 下用 `<id column="id" property="id"/>`
  与 `<result column="no" property="order_no"/>` 声明「结果集列名 → MetaClass 属性名」。
  property 会按表元数据归一（驼峰自动转下划线，`orderNo` → `order_no`），列名大小写不敏感；
  有 `resultMap` 的语句逐行 `NEW` 一个空实体、按 properties 直接 setter，最后 `clearChanged()`，
  读出来的行是「干净」的（后续 `update` 不会把整行都当改动写回）。`<association>` / `<collection>`
  这类嵌套映射暂不支持，遇到会明确报错；
- 参数实体上的键按元数据归一，`set("ORDER_NO", ...)` 与 `set("order_no", ...)` 都能对上语句里的列名；
  值按列的 JDBC 类型收口（表单字符串 → Long/BigDecimal/LocalDateTime）；
- 名字按忽略大小写对齐：`#{}` / `test` 里写的列名、调用方传的键、Mapper 文件名三者都不要求
  和元数据的大小写一致。H2、Oracle 的元数据列名是大写，手写文件多按 MySQL 习惯写小写，
  对不上时 `#{order_no}` 会静默绑成 `null`——所以这里显式做成大小写不敏感。

```properties
autumn.data.meta.mapper.meta-directory=autumn-meta             # 空串 = 纯内存，三类产物都不落盘
autumn.data.meta.mapper.generate-files=true                    # false 时缺失文件直接报错
autumn.data.meta.mapper.table-cache-size=10000
autumn.data.meta.mapper.default-list-limit=1000
autumn.data.meta.mapper.default-pool-name=default
autumn.data.meta.mapper.logic-delete-columns=deleted_flag,del_flag,is_deleted
```

`meta-directory` 是唯一的工作目录，三类产物落在它的固定子目录里：`meta-class/`（元类定义 JSON）、
`entity/`（生成的实体 `.java`，留空即全程内存编译）、
`mapper/`（Mapper XML）。子目录里的文件一旦存在就不再重写，所以 `generate-files=false` 只影响「第一次」：
缺失时抛 `MetaSqlException`，适合生产环境要求 Mapper 必须预置的场景。`table-cache-size` 是各缓存容器各自的
条目上限（默认 10000），详见「有界缓存」一节。

### 上手示例

上层怎么调，看使用方模块里的两个文件，都是能直接跑的：

- `autumn-data-jdbc-spring-boot-starter-using/src/test/java/.../using/MetaMapperXmlUsingTest.java`
  按「CRUD 不碰文件 → 命名语句首次生成 → 手写文件优先 → 类型与 id 校验 → 注入边界」五段走一遍，
  Spring 只配了数据源，引擎由 starter 自动装配进 `MetaMapper`；
- `autumn-data-jdbc-spring-boot-starter-using/src/test/resources/meta-mappers/demo_order.xml`
  就是那份手写 Mapper 文件，`<if>/<where>/<trim>/<choose>/<foreach>` + `#{}` + `${}` 各有一条，
  注释里写了查找顺序和三条写法约定。

跑起来：`mvn -B test`（全 reactor）。想只跑这一类示例，先 `mvn -B install -DskipTests`
把编译期处理器装进本地仓库，再 `mvn -pl autumn-data-jdbc-spring-boot-starter-using test
-Dtest=MetaMapperXmlUsingTest`。生成的文件落在 `target/autumn-meta/mapper/`，删掉它就等于
「按新表结构重来」。

## 抽取 / 再转换 / 执行：一次 SQL 的三段

动态 SQL 的形状跟着参数变（给几个条件就拼几段），没法像静态实体那样全在编译期定死；但每条语句都现场
判类型、现场决定怎么绑，又慢又难对齐。这一版把一次调用切成三段，重的两段都有缓存：

1. **抽取** → `MarkedSql(sql, refs, values)`：`#{}` 在解析阶段就摘成 `?`，同时记下每个槽引用的是哪一列
   （`refs`）和这一次调用的实参（`values`）。形状（`sql` + `refs`）与数据（`values`）分开，是同构调用
   复用计划的前提；构造时自校验 `?` 数与槽数一致（单引号字面量里的 `?` 不算槽）。
2. **再转换** → `MetaSqlPlan` + `MetaBindSlot[]`：按「形状指纹 + SQL 文本」缓存，一个形状只做一次。
   类型判断、列查找、setter 选择、写 NULL 时上报的 JDBC 类型全部在这里定完；引用不是列的槽
   （`<if test>` 的开关、`<foreach>` 的循环值）标成 OBJECT，原样交给驱动，不猜类型。
3. **执行**：`parameterCount() == 0` 走 `java.sql.Statement`（纯 `${}` / 字面量，文本就是成品）；
   `> 0` 走 `PreparedStatement`，运行期只剩「取值 + 编译期已经定好的类型化 setter」。
   同形状的第二次调用直接命中 `MetaPlanCache`，不解析、不判类型，数据库侧也能复用游标与执行计划。

`client.sqlFor(...)` 返回的就是 `MarkedSql`，`client.compiledShapes()` 给出已缓存的形状数——
「这条语句拼成了什么、绑了哪几个值」随时可查，注入相关的用例就是直接断言这个返回值。

## 写入语义

| 方法 | 语义 |
| --- | --- |
| `save` | 只做 INSERT，写实体上赋过值的列；自增主键回填到实体上并清掉变更集；非自增主键必须由调用方提供；无主键也能写，但返回的实体没有键 |
| `saveAll` | 每个实体都是「写过的列」，列集合一致时走 JDBC batch；不一致（写过的列不同）时退化为逐条 save；返回按入参顺序 |
| `update` | 部分更新：只 SET 改过的列，未改列保持原值，赋 `null` 就是真的写 NULL；WHERE 用实体上的主键；影响 0 行时不清变更集 |

主键列不允许出现在 UPDATE 的 SET 里：脏主键直接抛错。取行的方式是 `findById`（单主键值）或
`findByKey`（实体上的主键列，复合主键用这个），改完再 `update`。想绕过「先读后改」就写一条
按主键更新的命名语句（生成文件里的 `updateByKey` 就是这一条）。

## 安全边界

- 表名/列名/排序字段只作为引用，拼 SQL 前必须命中 `MetaTable`，否则抛错；
- 所有值走 `?`，包括条件、分页前的谓词、批量参数；命名语句里的 `#{}` 在**解析阶段**就摘成
  参数节点，模板渲染只产出 `?`，不存在“先拼文本再补参数”的顺序；
- `${}` 只能展开成标识符片段（字母数字下划线 + 方言引用符 + 列表标点），带引号、分号、
  注释符、比较符的值直接抛错；
- 值按列 JDBC 类型显式转换（表单字符串 → Long/BigDecimal/LocalDateTime/Boolean 等），
  失败信息带表.列与期望类型；
- `findList` 默认最多 1000 行，避免动态查询误拉全表；需要更多显式传 limit；
- 动态层不执行 `<delete>`：`execute` 遇到删除语句直接拒绝。

## 多数据源：为什么不用 ThreadLocal 存 DataSource

推荐的是**显式绑定句柄**：`MetaMapper.of(pool)` 返回 `MetaMapperClient`，租户/库的选择发生在
请求边界（能拿到租户 ID 的地方），之后把 client 往下传。理由：

1. 异步/线程池/虚拟线程会丢 ThreadLocal，必须到处 `wrap`，与现有 `AutumnDataContextHolder`
   的透传问题重复一遍；
2. 嵌套切换池时容易和事务错位：事务绑在默认池，语句却跑到另一个池；
3. 出问题时堆栈里只剩 `MetaMapper.findList(...)`，看不出用的哪个库。

ThreadLocal 最多作为“请求级默认池”的便利糖，不能作为唯一机制。缓存也按 `(pool, table)` 维度
各自持有 Registry，不能共用一个全局表缓存。

## Spring Boot 连接池与事务

引擎本身只认 `javax.sql.DataSource` + `ConnectionSource`，不认识 Spring；兼容是靠「换连接源」这一个
入口做到的（`MetaMapperEngine.forDataSource(name, dataSource, options, connectionSources)`）。

在 Spring Boot 里（引入 starter、保持 `autumn.data.meta.mapper.enabled=true`）：

- **连接池用 Boot 的**：自动配置把容器里那个 `DataSource` 直接交给引擎——就是
  `spring.datasource.*`（含 `spring.datasource.hikari.*`）配出来、`DataSourceAutoConfiguration`
  建的 Hikari 池。引擎不自建池、不改池参数，连接从池里借、操作结束 `close()` 归还
  （`DataSourceUtils.releaseConnection`），不长期霸占一条连接。
- **事务用 Spring 的**：连接源是 `SpringConnectionSource`（对标 MyBatis 的
  `SpringManagedTransaction`）。`@Transactional` 里调用时，`DataSourceUtils.getConnection` 给的是
  事务绑定的同一条连接，动态层的语句与 `JdbcTemplate` / 静态仓储同处一个事务：
  `begin/commit/rollback` 全部交给 spring-tx，动态层不自己提交。
- **连接源按线程一份**：`SpringConnectionSource` 持有“当前连接”字段，而两条链路共享的是单例
  （容器里的 `AutumnJdbc` / 引擎里的 client），所以两处都套了 `PerThreadConnectionSource`。
  不套就会串连接——甲线程取连接时把乙线程正在用的那条归还掉，事务里的语句也会突然跑到别的连接上。
- **没有事务时**：每次操作借还连接、单方法自提交，与原生 JDBC 行为一致。
- **多数据源**：默认池走上面这条；`MetaMapper.of(pool)` 指向的其他池连接来自
  `MetaDataSource` 注册表，事务要按池自建 `DataSourceTransactionManager`（Spring 事务只绑默认池）。
- 想换成自己的连接源（连接加密、影子库、压测桩等），定义自己的 `MetaMapperEngine` Bean 即可，
  自动配置 `@ConditionalOnMissingBean` 会退让。

非 Spring 场景（`MetaMapperEngine.standalone` / 三参 `forDataSource`）保持原样：
`PerThreadConnectionSource` + `JdbcConnectionSource`，单方法内部语句自提交，跨方法的
`@Transactional` 不生效（没有 Spring 的意思）。

验证见两个测试：
- `AutumnDataMetaMapperSpringPoolTests`：Hikari 池名与「操作后活跃连接归零」、`@Transactional`
  回滚把动态层的写入一起撤回、提交则落库；
- `AutumnDataJdbcSpringConnectionTests`：静态仓储链路同样加入事务，以及「两个线程各持一条连接」。

## 代码与测试

## 代码与测试

- 实现：`autumn-data-meta-jdbc/src/main/java/xyz/wewin/autumn/data/meta/jdbc`，外层放用户级 API
  （`MetaMapper` / `MetaQuery` / `MetaSqlException` / `SqlBuild`），内部分五层：
  `engine`（引擎与 client 的编排）、`metadata`（表结构快照与元类定义）、`entity`（Java 源码拼装 +
  内存 javac + 结果集映射）、`sql`（`MetaSqlBuilder` 现拼、`MetaStatementStore` + `SqlTemplate` 解释文件、
  `MarkedSql` → `MetaPlanCompiler` → `MetaSqlPlan` 三段与 `MetaPlanCache`）、`support`（有界缓存与目录）。
  方言与编译期生成器共用 `autumn-data-common` 的 `TemplateParser` / `TemplateNode`，本层只解释执行。
- 用例（共 76 个）：`engine/MetaMapperDynamicTest` 47 个（建表 → 不重启 CRUD → 加列 refresh → 逻辑删除 →
  复合主键 → Mapper 文件生成一次/改表不重写 → 手工文件优先 → 名字忽略大小写对齐 → 空值判断方法 →
  `#{}` 绑定与注入拒绝 → `${}` 白名单 → `<foreach>` / `<trim>` / `<delete>` 拒绝，以及生成类的真实字段类型 /
  零反射源码 / 加列后生成新类 / 源码落盘 / 投影缺列 / 各缓存淘汰后可重建 / 手工登记也参与淘汰后回源 /
  池 client 淘汰重建 / 内存编译无临时目录 / 默认上限 10000 / 列名撞基类契约 / 关键字与中文列名 /
  并发单飞 / 连续改表，以及**手写 SQL 的绑定与 `${table}` 展开、分页（显式/自动计数）、裸 `?` 与物理删除拒绝、
  注册语句跨 refresh 复用与撤销、表/实体/语句三级缓存分开清理、`reload` 立即重建与表已删报错、`cacheStats`**）；
  `sql/MetaSqlPlanTest` 6 个（形状与数据分离、槽位类型、写过的列决定 INSERT 形状、字面量问号不算槽）；
  `entity/MetaEntityCompilerTest` 1 个（`jar:` CodeSource 兜底）；
  `support/BoundedCacheTest` 15 个（LRU 顺序、上限、并发单飞、加载在锁外、失败不缓存、失效语义、
  `asMap` 只读快照、refresh 与在途加载竞态、并发读写不越界）；另有 `engine/MetaEntityGenericModeTest` 1 个
  （GENERIC 实体）、`engine/MetaMapperDialectTest` 2 个（方言差异）、`entity/MetaEntityStrategyTest` 4 个
  （CODE / GENERIC 两档策略）。
  starter 装配 2 个；using 模块端到端 1 个 + 上层用法示例 5 个。
- 全 reactor：`mvn -B test`，starter / using / compiler / common 全绿。
