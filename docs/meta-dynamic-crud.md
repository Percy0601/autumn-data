# 动态表 CRUD（autumn-data-meta-jdbc）

面向“运行期设计出来的单据/基础资料”：**低代码开发平台的底层基座**——表单和基础资料的数据表
都是用户运行期设计出来的，服务不重启、工程不需要重新编译，就能按表名 + 列名单做查询、
保存和更新（实体类由框架在运行期生成并编译）。

架构上两条线职责分明：`autumn-data-jdbc` + `autumn-data-jdbc-compiler` 是**编译期生成实现、完全静态**
的仓储；本模块是**完全动态、轻量、弹性懒加载**的路径，不替代前者，也不引入任何编译期注册 /
预加载 / 反射代理。三条硬约束贯穿全文：**重活一律推迟到首次访问**、**所有缓存有界且 LRU 弹性**、
**表结构与表数量在运行期可以任意变化**。

## 分层

```text
MetaMapper（静态门面，只持有 volatile engine；查询返回 MetaClass）
  └─ MetaMapperEngine（实例，按 pool 懒建/缓存 client，共享一个 MetaEntityRegistry）[有界 LRU]
       └─ MetaMapperClient（绑定一个 pool）
            ├─ MetaTableRegistry（表名 → MetaTable 快照 + 手工登记覆盖）        [有界 LRU ×2]
            ├─ MetaSqlBuilder（按 MetaTable 生成 SQL，标识符只来自快照）
            ├─ MetaStatementStore（表名 → Mapper AST；已有则读、缺失生成一次） [有界 LRU]
            ├─ MetaEntityRegistry（结构指纹 → 已编译 MetaClass 子类 + READER） [有界 LRU]
            │    ├─ MetaEntityTemplate（FreeMarker 生成 .java 源码）
            │    └─ MetaEntityCompiler（javax.tools 内存编译 + 内存 ClassLoader，零临时目录）
            └─ AutumnJdbc（复用现有执行层：query / page / count / update / 生成键）

所有 [有界 LRU] 都走同一个 BoundedCache 接口 / BoundedCaches 工厂，上限由 table-cache-size 统一配置。
本模块没有任何「编译期生成的 Mapper 注册中心」或接口动态代理：静态注册路径（MetaConfiguration /
MetaSqlSession）已按动态定位移除，SqlBuild 只作为与静态侧共用的无状态值对象保留。
```

核心模块不依赖 Spring；Spring Boot 集成在 `autumn-data-jdbc-spring-boot-starter` 的
`AutumnDataMetaMapperAutoConfiguration` 里，只负责把引擎装进静态门面。

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
2. `MetaEntityTemplate` 用 FreeMarker 模板 `meta-class.ftl` 渲染一份 Java 源码：
   每列一个**真实类型**字段 + getter/setter，`extends MetaClass`；
3. `MetaEntityCompiler` 用 JDK `javax.tools.JavaCompiler` **全程内存编译**：内存源码对象 →
   javac 输出到 `byte[]` → 独立内存 `ClassLoader` 的 `defineClass`。不建临时目录、不落 `.class`，
   因此 LRU 淘汰掉实体模型后，对应的 Class 与 ClassLoader 没有活引用就能被 GC，元空间可回收
   （这一点对「表结构频繁变化」很关键：缓存有界，物理资源也必须同步有界）；
4. 生成类里的 `READER` 是写死的 `rs.getObject(index)` + 直接 setter 调用，
   **逐行映射零反射**；反射只在「加载类时取一次静态字段」这一步发生；
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
生成类的包名是 `...generated.v<指纹>`。加一列后 `refresh`，指纹变化 → 生成另一个包下的新类，
旧实例继续由各自的 ClassLoader 持有，不会被重定义，也不会新旧实例互相 ClassCastException。
同构的表只编译一次，多个数据源池共享一个 `MetaEntityRegistry`。

生成的源码可以配置落盘（`autumn.data.meta.mapper.entity-source-directory`），产出的是一份
可读、可改、能固化成静态实体的 `.java`。运行期需要完整 JDK（JRE 上没有 `javax.tools`，
会明确报错）。

> **部署注意**：javac 编译生成源码时需要能从 `java.class.path` / `CodeSource` 找到
> `MetaClass` 等依赖类。普通 classpath 启动（含 Maven Surefire 的 manifest-only jar）已处理；
> Spring Boot 可执行 jar 把依赖放在 `BOOT-INF/lib` 的嵌套 jar 里，`CodeSource` 是 `jar:` URI，
> 目前会被跳过，javac 会报「找不到依赖类」。这类部署需要把依赖展开到文件系统（例如
> `PropertiesLauncher` 的 `loader.path`），或后续给编译器补一个从 ClassLoader 读 `.class`
> 资源的 JavaFileManager。至少不会因为 `jar:` URI 抛 `ExceptionInInitializerError`。

### 投影查询

命名语句常只 SELECT 部分列。生成类的 `read` 先用 `MetaEntityColumns.index(rs)` 建
「小写列标签 → 序号」索引，缺列就跳过、字段保持 null，不会因 `rs.getObject("未投影列")`
抛 `SQLException`，也不会串位；同时兼容 H2/Oracle 给大写列名、手写 SQL 用小写列名的差异。

## API

```java
MetaMapper.install(MetaMapperEngine.forDataSource("default", dataSource));

// 查询
Optional<MetaClass> row = MetaMapper.findById("form_order", 1L);
Class<? extends MetaClass> type = MetaMapper.entityType("form_order");   // 动态生成出来的类
MetaMapper.findOne("form_order", MetaQuery.by("order_no", "A001"));
MetaMapper.findList("form_order", MetaQuery.by("status", 1)
        .and("create_time", MetaQuery.Op.GE, from)
        .orderBy("create_time", MetaQuery.Direction.DESC));
MetaMapper.findList("form_order", MetaQuery.all().isNull("remark"));      // 空值判断走专门的方法
MetaMapper.findPage("form_order", MetaQuery.by("status", 1), Pageable.of(0, 20));
MetaMapper.count(...); MetaMapper.exists(...);

// 保存（只 INSERT）
Object id = MetaMapper.save("form_order", Map.of("order_no", "A001", "amount", "12.50"));
List<Object> ids = MetaMapper.saveAll("form_order", rows);

// 更新（部分更新）
MetaMapper.updateById("form_order", id, Map.of("status", 2));

// 多数据源：显式绑定池
MetaMapper.of("tenant-a").findPage("form_order", MetaQuery.all(), Pageable.of(0, 20));

// 命名语句走 client（id 就是 Mapper 文件里的语句 id）
MetaMapperClient client = MetaMapper.defaultClient();
client.selectList("form_order", "selectList", Map.of("status", 1));
client.selectOne("form_order", "byOrderNo", Map.of("order_no", "A001"));
client.selectCount("form_order", "countDraft", Map.of("status", 1));
client.execute("form_order", "voidByNo", Map.of("order_no", "A001"));
SqlBuild sql = client.sqlFor("form_order", "selectList", Map.of("status", 1)); // 只看文本，不执行
```

**没有 delete。** 应用侧不提供物理删除；作废走 `updateById(..., Map.of("status", "VOID"))`。
若表里有 `deleted_flag` / `del_flag` / `is_deleted`，查询自动追加 `= 0`（可配置关闭）。

## 元数据快照

- 首次访问某张表时从 `DatabaseMetaData` 读取列、主键、自增、可空、默认值，直接构造
  `MetaClass`（`name` = 表名、`namespace` = schema、`metaType = ENTITY`、`properties` = 列）；
- `MetaTable` 不可变，缓存键大小写不敏感；
- 建表/加列/删列**提交后**调 `client.refresh(table)` 或 `MetaMapper.refresh(table)`：
  元数据快照、该表已编译的实体类、Mapper 文件 AST 缓存一起换新；
- 旧快照与旧生成类上正在执行的语句不受刷新影响，刷新只影响下一次解析。

## 有界缓存（表数量无上限）

低代码场景下表单设计器能建出成千上万张表，进程内**按表或按池增长的容器全部要有边界**：

| 缓存 | key | 条目重量 | 淘汰后再访问的代价 |
| --- | --- | --- | --- |
| `MetaTableRegistry.cache` | 表名（大小写不敏感） | 一份不可变列快照 | 重读一次 `DatabaseMetaData` |
| `MetaTableRegistry.overrides` | 表名 | 使用方手工登记的结构覆盖 | 回源到 `DatabaseMetaData`；需长期保真就调大上限或重新 `register` |
| `MetaEntityRegistry` | 表名 + 结构指纹 | 一个编译出的 `Class` + 内存 ClassLoader | 重新生成/编译一份同类名的类（新 ClassLoader） |
| `MetaStatementStore` | 表名 | 一棵 Mapper XML AST | 重读已有的 XML 文件（**不会重写**） |
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

1. 查找顺序：`<xml-directory>/<表名>.xml` → classpath `meta-mappers/<表名>.xml`；
2. 都没有且 `generate-xml=true`：按 `MetaTable` 的列结构生成一份，写到 `xml-directory` 后使用；
   目录为空串时只在内存里用、不落盘；
3. 生成文件带 `generated-by: autumn-data-meta-jdbc` 标记。**文件存在就直接使用，永不回头覆盖**：
   改表加列后 `refresh` 只是丢缓存重读文件，要按新结构重来就删文件手工重写。文件归使用方所有。

`client.refresh(table)` 与“重写用户文件”是两件事，故意不联动——否则用户改过的语句会被表结构冲掉。

### 谁读文件、谁不读

| 入口 | SQL 来源 | 说明 |
| --- | --- | --- |
| `findById` / `findList` / `findPage` / `count` / `save` / `updateById` … | `MetaSqlBuilder` 现拼 | 条件运算符是开集（`>` / `LIKE` / `IN` / `BETWEEN` / 排序…），固定文件表达不了，因此这条路**完全不读文件** |
| `sqlFor` / `selectList` / `selectOne` / `selectCount` / `execute` | Mapper 文件的命名语句 | 语句 id 就是 XML 里的 `id`；`<select>` 走查询，`<insert>`/`<update>` 走 `execute`，`<delete>` 拒绝执行 |

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
- `test` 表达式既认 MyBatis 的 `name != null`、`name == 'active'`，也认 FreeMarker 表达式的
  `name??`、`name?has_content`、`name?size`、`!expr`、`a && b`。FreeMarker 的 `<#if>` 标签本身
  不是合法 XML（标签名不能以 `#` 开头），DOM 解析阶段就进不来，所以兼容只做在表达式层；
- 调用方传的参数键按元数据归一，`Map.of("ORDER_NO", ...)` 与 `order_no` 都能对上模板里的列名；
  值按列的 JDBC 类型收口（表单字符串 → Long/BigDecimal/LocalDateTime）；
- 名字按忽略大小写对齐：`#{}` / `test` 里写的列名、调用方传的键、Mapper 文件名三者都不要求
  和元数据的大小写一致。H2、Oracle 的元数据列名是大写，手写文件多按 MySQL 习惯写小写，
  对不上时 `#{order_no}` 会静默绑成 `null`——所以这里显式做成大小写不敏感。

```properties
autumn.data.meta.mapper.xml-directory=meta-mappers
autumn.data.meta.mapper.generate-xml=true
autumn.data.meta.mapper.entity-source-directory=generated-entities
autumn.data.meta.mapper.table-cache-size=10000
```

`entity-source-directory` 是生成的实体 `.java` 落盘目录，留空表示全程内存编译、完全不落盘。
`table-cache-size` 是各缓存容器各自的条目上限（默认 10000），详见「有界缓存」一节。
`xml-directory` 为空串时只生成到内存；`generate-xml=false` 时缺失文件直接抛
`MetaSqlException`，适合生产环境要求 Mapper 必须预置的场景。

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
-Dtest=MetaMapperXmlUsingTest`。生成的文件落在 `target/meta-mappers/`，删掉它就等于
「按新表结构重来」。

## 写入语义

| 方法 | 语义 |
| --- | --- |
| `save` | 只做 INSERT；自增主键回填；非自增主键必须由调用方提供；无主键也能写，但返回 `null` |
| `saveAll` | SQL 文本一致时走 JDBC batch；不一致（传的列不同）时退化为逐条 save |
| `updateById` / `updateByKey` | 部分更新：只 SET 传入的列；未传列保持原值；置空必须显式传 `MetaValue.NULL` |

主键列不允许出现在 UPDATE 的 SET 里；单主键用 `updateById`，复合主键用 `updateByKey`。

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

## 事务现状与后续接入点

当前版本每次操作取连接、结束归还（`PerThreadConnectionSource` + `JdbcConnectionSource`），
单方法内部语句自提交；跨方法的 `@Transactional` 暂不生效。

后续接入事务只需要替换 `MetaMapperEngine` 的 client 工厂里的 `ConnectionSource`：
- Spring 默认池：`SpringConnectionSource`，交给 `DataSourceUtils`；
- 非默认池：按池取 `DataSource` 后各建一个事务管理器；
- 写入路径（save/update）本身已经共用同一连接，batch 也在同一操作内完成，不需要改 SQL 层。

## 代码与测试

- 实现：`autumn-data-meta-jdbc/src/main/java/xyz/wewin/autumn/data/meta/jdbc/dynamic`
  （`MetaStatementStore` 管文件生命周期，`SqlTemplate` 是运行期解释器，方言与编译期生成器
  共用 `autumn-data-common` 的 `TemplateParser` / `TemplateNode`；
  `MetaEntityTemplate` / `MetaEntityCompiler` / `MetaEntityRegistry` 管 FreeMarker 生成实体类；
  `BoundedCache` / `LruCache` / `BoundedCaches` 管全部有界缓存；`MetaEntityCompiler` 全内存编译；
  `SqlBuild` 只作为与静态侧共用的无状态值对象保留）
- 用例：`MetaMapperDynamicTest`（建表 → 不重启 CRUD → 加列 refresh → 逻辑删除 → 复合主键 →
  Mapper 文件生成一次/改表不重写 → 手工文件优先 → 名字忽略大小写对齐 → 空值判断方法 →
  `#{}` 绑定与注入拒绝 → `${}` 白名单 → `<foreach>` / `<trim>` / `<delete>` 拒绝，
  以及生成类的真实字段类型 / 零反射源码 / 加列后生成新类 / 源码落盘 / 投影缺列 /
  各缓存淘汰后可重建 / 手工登记也参与淘汰后回源 / 池 client 淘汰重建 /
  内存编译无临时目录 / 默认上限 10000 / 列名撞基类契约 / 关键字与中文列名 /
  并发单飞 / 连续改表 / jar: CodeSource 兜底），共 33 个；
  `BoundedCacheTest` 15 个（LRU 顺序、上限、并发单飞、加载在锁外、失败不缓存、失效语义、
  `asMap` 只读快照、refresh 与在途加载竞态、并发读写不越界）；
  starter 装配 2 个；using 模块端到端 1 个 + 上层用法示例 5 个。
- 全 reactor：`mvn -B test`，starter / using / compiler / common 全绿。
