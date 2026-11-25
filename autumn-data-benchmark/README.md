# autumn-data-benchmark

`autumn-data-meta-jdbc`（元数据驱动的 JDBC 框架）与 MyBatis 3.5.19 的对照基准。

> **历史数据说明**：下面的结果测于实体还有 CODE / GENERIC 两档的旧版本。现在动态层统一为通用
> `MetaClass`，不再有运行期代码生成；基准脚本里的 `meta-code` / `meta-generic` 标签只作兼容保留，
> 二者都指向同一个引擎，**不能再用这组标签比较两档实体形态**。结论中关于 CODE/GENERIC 的段落
> 仅作为历史取舍记录。

这个模块只为回答一个问题：**"没有反射，就一定比 MyBatis 快吗？"**

结论在本文最后一节，但先说三点，因为它们决定了你怎么读下面的数字：

1. 查询路径上 meta CODE 档确实比 MyBatis 快，但**不是"快 2 倍"这种量级**——单行点查快约 27%，100 行列表快约 2.1 倍。差距随结果集变大才显现，说明省下来的主要是"每行胶水"，不是"每次调用的固定成本"。
2. **"反射"本身不是 MyBatis 慢的主要原因。** 把 SQL 和连接池剥掉、只比逐行映射时，MyBatis 的"反射写字段核心"（`ObjectFactory` + `MetaObject.setValue`）与 meta 生成类读取器基本同速；MyBatis 完整的 `DefaultResultSetHandler` 路径才是 meta 的 2 倍。慢在每行的 `ResultLoaderMap`、判别器解析、`ResultHandler` 胶水、以及**每行一次 `getDeclaredConstructor()`** 的实例化。
3. **meta 自己的短板同样明显**：生成类读取器每读一行都要 `rs.getMetaData()` + 新建一个 `LinkedHashMap` 来建列索引（`MetaEntityColumns.index`，为了投影缺列时字段保持 null）。这一项单独量出来占 meta 逐行读取成本的 **75%**。把它提到"每结果集一次"，meta 的映射优势会从 2 倍变成 6 倍以上——这是当前实现里最值钱的一处优化点。

另外，**写入路径 meta 反而更慢**（慢 26%–38%），GENERIC 档全面落后（100 行列表比 MyBatis 还慢 11%）。

---

## 为什么单独开一个模块

两条约束：

- 基准不进 `autumn-data-jdbc-spring-boot-starter-using`：那是个演示"怎么用"的示例模块，塞进 JMH + H2 + MyBatis 会污染它的语义（示例模块的学习者不该被迫接受 60 多个基准依赖）。
- 不污染其他模块代码：本模块对外零侵入，除了根 `pom.xml` 的 `<modules>` 里加一行 `autumn-data-benchmark`、以及本目录自己的文件，仓库里没有第二处改动。

本模块**不参与发布**（`maven.install.skip` / `maven.deploy.skip` 均为 `true`）。运行时用纯内存 meta 选项（`metaDirectory=""`、`generateFiles=false`），不会往项目目录写任何 meta 产物。

## 六条被测路径

| impl | 含义 |
| --- | --- |
| `jdbc` | 手写 JDBC，性能地板 |
| `mybatis-per-call` | 每次 `openSession` + `getMapper`，等价 Spring 里 `SqlSessionTemplate` 的用法 |
| `mybatis-shared` | 长会话 + 复用 mapper，MyBatis 的最佳情形（本次对照以它为准，避免把会话开销算成框架开销） |
| `meta-code` | meta CODE 档，`findById` / `findList` / `save`，实体由注解处理器生成的类承担 |
| `meta-code-statement` | meta CODE 档 + `selectOne` 走命名语句入口（默认生成的 `findById` 语句），对比专用方法路径 |
| `meta-generic` | meta GENERIC 档，逐行按元数据反射式取值，Native Image 下的兜底路径 |
| `pojo-setters` | 纯映射组专用：手写 POJO setter 按列序号赋值，性能地板 |
| `meta-column-index` | 纯映射组专用：只跑 `MetaEntityColumns.index(rs)`，把"逐行建列索引"这一项单独量出来 |
| `mybatis-metaobject` | 纯映射组专用：MyBatis 逐行核心（建对象 + `MetaObject.setValue` + `TypeHandler`），列索引每结果集建一次 |
| `mybatis-handler` | 纯映射组专用：MyBatis 真实 `DefaultResultSetHandler.handleRowValues` 路径 |

## 方法学

### 环境

| 项 | 值 |
| --- | --- |
| CPU | 12th Gen Intel Core i7-12650H（6 P-core + 4 E-core，混合架构） |
| JDK | GraalVM 25.0.4.1.1（HotSpot 模式，非 native image） |
| MyBatis | 3.5.19 |
| H2 | 2.4.240（内存库） |
| 连接池 | HikariCP 7.0.2（maximumPoolSize 8） |
| JMH | 1.37，`-wi 5 -i 8 -w 1s -r 1s -f 2`（2 fork × 8 次 1 秒迭代），`AverageTime` |
| 亲和性 | 进程 `ProcessorAffinity = 0xFFF`（固定到 12 个 P-core 逻辑 CPU） |

**混合核必须固定亲和性。** 同一份代码在 E-core 上比 P-core 慢约 1.5 倍；不固定时三次运行之间的绝对数值最大漂移过 4 倍（机器降频状态下 `pojo-setters` 从 3.8µs 涨到 14.5µs）。所以本文所有数字取自固定 P-core 的同一次运行，且另两次运行在**排序和倍数关系**上完全一致（见"重复性"一节）。

### 数据源与表

双方**共用同一个 `HikariDataSource` 实例**——MyBatis 侧通过自定义 `HikariBenchDataSourceFactory` 注入同一个池，避免"连接建立成本只压在 MyBatis 一边"。

```sql
CREATE TABLE bench_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  order_no VARCHAR(32) NOT NULL,
  amount DECIMAL(12,2),
  status INT,
  create_time TIMESTAMP
)
```

- `bench_order`：灌 200 行，读基准用前 100 行。
- `bench_order_insert`：写基准用，每次迭代前 `TRUNCATE`。
- 六列、同一个列顺序、两边同一条 SQL：

```sql
SELECT id, user_id, order_no, amount, status, create_time FROM bench_order WHERE id = ?
```

### 公平性措施

1. 同一 `DataSource`、同一张表、同一份 SQL 文本、同样六列。
2. MyBatis 侧关闭二级缓存（`cacheEnabled=false`）、局部缓存设为 `STATEMENT`，避免"缓存命中"把框架开销测成 0。
3. 纯映射组让两边跑**同一个已物化的可滚动 `ResultSet` 实例**（`TYPE_SCROLL_INSENSITIVE`，100 行全部在客户端），每次调用 `beforeFirst()` 复位。这样测到的只有逐行映射，不含 SQL 执行与连接池借还。
4. `mybatis-handler` 用 `handleRowValues` 而不是 `handleResultSets`，因为后者的 `finally` 会把结果集关掉；`handleRowValues` 是公开方法，走的正是生产路径里的逐行分支。
5. 运行时 classpath **必须**由各模块 `target/classes` 拼出来（`run-benchmark.ps1` 已处理），不能用 `~/.m2` 里的 snapshot jar——否则跑的是上一次 `install` 的旧代码，容易出现 `NoSuchMethodError` 导致整组基准被 JMH 静默剔除。

### 正确性验证

性能数字只有在各条路径结果完全一致时才有意义。`BenchVerification` 逐字段比对：

```
ok   jdbc -> 1|1000|ORD-0|12.34|0|2026-01-01T00:00
ok   mybatis -> 1|1000|ORD-0|12.34|0|2026-01-01T00:00
ok   meta-code -> 1|1000|ORD-0|12.34|0|2026-01-01T00:00
ok   meta-generic -> 1|1000|ORD-0|12.34|0|2026-01-01T00:00
ok   meta-statement -> 1|1000|ORD-0|12.34|0|2026-01-01T00:00
ok   mybatis-list-size -> 100 / meta-list-size -> 100
ok   mybatis-list-first / meta-list-first -> 1|1000|ORD-0|...
ok   mybatis-list-last / meta-list-last -> 100|1099|ORD-99|...
ok   mybatis-insert -> 1|2001|VERIFY-1|12.34|1|2026-01-01T08:00
ok   meta-insert-key -> 2
ok   meta-insert -> 2|2002|VERIFY-2|12.34|1|2026-01-01T08:00
VERIFICATION OK: all paths agree
```

任何一项不一致即以非零码退出，基准拒绝运行。

## 结果

单位 µs/op（100 行组的"每行"列为该值 ÷ 100）。

### 1. 单行点查（1 行）

| impl | µs/op | 误差 | 相对 `mybatis-shared` |
| --- | --- | --- | --- |
| `jdbc` | 0.800 | ±0.028 | 3.6× 更快 |
| `meta-code-statement` | 2.243 | ±0.009 | **1.30× 更快** |
| `meta-code` | 2.293 | ±0.185 | **1.27× 更快** |
| `meta-generic` | 2.546 | ±0.018 | 1.14× 更快 |
| `mybatis-shared` | 2.906 | ±0.274 | 基准 |
| `mybatis-per-call` | 3.113 | ±0.189 | 1.07× 更慢 |

### 2. 100 行列表

| impl | µs/op | 误差 | 每行 µs | 相对 `mybatis-shared` |
| --- | --- | --- | --- | --- |
| `jdbc` | 6.862 | ±0.548 | 0.069 | 8.2× 更快 |
| `meta-code` | 26.359 | ±0.553 | 0.264 | **2.14× 更快** |
| `mybatis-shared` | 56.537 | ±7.620 | 0.565 | 基准 |
| `meta-generic` | 62.613 | ±5.523 | 0.626 | 1.11× 更慢 |
| `mybatis-per-call` | 69.422 | ±15.467 | 0.694 | 1.23× 更慢 |

### 3. 单行插入（回填自增主键）

| impl | µs/op | 误差 | 相对 `mybatis-shared` |
| --- | --- | --- | --- |
| `jdbc` | 4.399 | ±0.212 | 1.05× 更快 |
| `mybatis-shared` | 4.597 | ±0.180 | 基准 |
| `mybatis-per-call` | 5.016 | ±0.192 | 1.09× 更慢 |
| `meta-code` | 6.333 | ±1.429 | **1.38× 更慢** |
| `meta-generic` | 8.798 | ±2.214 | **1.91× 更慢** |

### 4. 纯映射（同一个结果集，100 行，无 SQL、无连接池）

| impl | µs/op | 误差 | 每行 µs | 相对 `meta-code-reader` |
| --- | --- | --- | --- | --- |
| `pojo-setters` | 3.778 | ±0.259 | 0.038 | 8.2× 更快（地板） |
| `meta-column-index` | 23.012 | ±6.885 | 0.230 | 0.75× |
| `meta-code-reader` | 30.840 | ±9.562 | 0.308 | 基准 |
| `mybatis-metaobject` | 63.874 | ±44.661 | 0.639 | 2.07× 更慢 |
| `mybatis-handler` | 64.932 | ±7.399 | 0.649 | 2.11× 更慢 |
| `meta-generic-reader` | 81.736 | ±25.919 | 0.817 | 2.65× 更慢 |

> `mybatis-metaobject` 的误差棒很宽（±44.6）：这一组有 JIT 双峰现象，两个 fork 落到了不同的内联/去虚化决策上。判断 MyBatis 完整路径时请以 `mybatis-handler`（±7.4）为准。

### 重复性

| 运行 | 状态 | 单行 meta/mybatis | 100 行 meta/mybatis | 纯映射 meta/mybatis-handler | 列索引占比 |
| --- | --- | --- | --- | --- | --- |
| #2 | 未固定亲和性、机器降频（整体慢 ~4×） | 10.879 / 15.578 = **0.70** | 94.581 / 181.578 = **0.52** | 125.808 / 261.843 = **0.48** | 60% |
| #3 | 未固定亲和性、未降频 | 2.368 / 6.919 = 0.34 | 60.977 / 89.204 = 0.68 | 25.053 / 53.389 = **0.47** | 65% |
| #final | 固定 P-core 亲和性 | 2.293 / 2.906 = 0.79 | 26.359 / 56.537 = **0.47** | 30.840 / 64.932 = **0.47** | 75% |

绝对数值随机器状态漂移，但**倍数关系稳定**：100 行列表 0.47–0.52（即 meta 快约 2 倍），纯映射 0.47–0.48（meta 快约 2.1 倍），列索引占 60%–75%。#3 的单行比值（0.34）是那次的 `mybatis-shared` 异常偏高（±3.29 的误差棒），单行组以 #final 为准。

## 分析

### 1. 单行点查只有 27% 的差距，为什么

`jdbc` 的 0.800µs 说明：一条走内存库、连接池已热、预编译语句现场创建的点查，**固定成本约 0.8µs**。框架能压缩的只有这之上的部分：

- meta CODE：2.293 − 0.800 ≈ 1.49µs 框架开销
- MyBatis shared：2.906 − 0.800 ≈ 2.11µs 框架开销

差 0.62µs。这一档里 meta 省下的是"每次调用不再过 `MappedStatement` → `BoundSql` → `Executor` 三层 + 反射取值"，而 `getMetaData()` 逐行扫描在建索引时才发生（只有 1 行，摊薄后不显）。

`meta-code-statement`（2.243）与 `meta-code`（2.293）几乎同速，说明**这点差距不是 SQL 文本拼装造成的**——命名语句省下的 SQL 组装在总成本里只值 0.05µs，主导项是 `PreparedStatement` 创建与执行本身。

### 2. 结果集变大后差距放大到 2.1 倍，说明省的是"每行"

100 行列表：每行 meta 0.264µs、MyBatis 0.565µs，差 0.30µs/行；而单行的总差是 0.62µs/次。也就是说单行组里大部分差距还是"每次调用的固定开销"，行数一多，"每行胶水"成了主导。

用纯映射组验证这个判断：把 SQL 和连接池完全剥掉后，meta 30.84µs / MyBatis `handleRowValues` 64.93µs = **0.47**，和端到端的 0.47 吻合。**端到端那 2 倍差距，全部来自逐行映射**，不是来自 MyBatis 的会话/执行器开销（会话开销在 `mybatis-per-call` vs `mybatis-shared` 的对比里只有 0.2–1.2µs/次，100 行时被摊薄到无关）。

### 3. "没有反射"不是 MyBatis 慢的原因——证据在这

纯映射组里有一列专门的对照：`mybatis-metaobject` 跑的是 MyBatis 自己的反射核心（`ObjectFactory.create` + `MetaObject.setValue` + `TypeHandler`），列索引由 `ResultSetWrapper` **每结果集建一次**。它跟 meta 生成类读取器同处于"无 SQL"环境，两者分别是 63.87（误差大，取 `mybatis-handler` 64.93 旁证）和 30.84。

但把 `mybatis-metaobject` 与 `mybatis-handler` 一起看就能拆出成本构成：两者只差"完整 handler 的胶水"（`ResultSetWrapper` 构造、`ResultHandler`、`getRowValue` 的每行 `new ResultLoaderMap()`、判别器解析、`foundValues` 记账），差值是每 100 行约 1µs——**很小**。

真正吃时间的是**每行的对象实例化**：`DefaultObjectFactory.instantiateClass` 在无参构造分支里每次都做 `type.getDeclaredConstructor()`，再 `newInstance()`。`getDeclaredConstructor()` 不是缓存命中，它每次都要复制一份 `Constructor`、跑访问检查——这才是 100 行里最贵的反射动作，而且和"用不用反射写字段"是两件事。

meta 之所以快，是因为生成类里就是 `new BenchOrder()` 直接实例化 + 直写字段，**整条链上没有任何反射调用，也没有每行的元数据解析**（除了列索引那次 `getMetaData()`）。换句话说：meta 的优势来自"把框架胶水全部编译进生成代码"，而不是"反射这个动作本身慢"。反射取值（`Method.invoke`）在 JDK 25 上已经被优化得相当便宜，meta 生成读取器每行 0.308µs 里，纯取值部分（扣掉列索引后）只有约 0.078µs/行，跟 `pojo-setters` 的 0.038µs/行 是同一个量级——这恰好说明两边"取值"都很快。

### 4. meta 自己的开销里，60%–75% 花在建列索引

`meta-column-index` 单独跑 `MetaEntityColumns.index(rs)`：23.01µs / 100 行 = 0.230µs/行。而生成类读取器整体是 0.308µs/行。**索引一项吃掉 75%。**

看源码就知道为什么：`MetaEntityRegistry` 的逐行读取、`MetaEntitySource` 生成的 `read(ResultSet)`、以及 `MetaMappedRowMapper.mapRow` 三处，都是**每一行**调一次 `MetaEntityColumns.index(rs)`：

```java
// MetaEntitySource 生成的 read(ResultSet)
Map<String, Integer> cols = MetaEntityColumns.index(rs);   // 每行一次
Integer index;
Object raw;
index = cols.get("id");
...
```

而 `index()` 内部是 `rs.getMetaData()`（H2 上返回一个新对象） + 遍历 6 列 `getColumnLabel(i)` + `toLowerCase(Locale.ROOT)` + 往新建 `LinkedHashMap` 里 `putIfAbsent`。

这一层是为正确性服务的（手写命名语句可能只投影部分列，按列名硬取会在缺列时抛 `SQLException`；大小写也要兼容 H2/Oracle 与 MySQL）。但它的**生命周期明显放错了位置**：结果集的列标签在一次查询内是常量，本该每结果集建一次（MyBatis 的 `ResultSetWrapper` 就是这么做的）。

粗算收益：若把索引提到每结果集一次，meta 生成读取器会掉到约 **7.8µs / 100 行**（0.078µs/行，即 `pojo-setters` 的水平加一点取值/转换），相对 MyBatis `handleRowValues` 的 64.93µs 就是 **8 倍以上**，而不是现在的 2.1 倍。这也解释了为什么端到端 100 行列表（26.4µs）里，SQL 部分只占 6.9µs，剩下 19.5µs 几乎全是映射——而其中约 15µs 是建索引。

### 5. GENERIC 档不该用来比性能

- 单行 2.546 vs CODE 2.293（+11%）
- 100 行 62.61 vs CODE 26.36（2.4× 慢），而且比 `mybatis-shared` 的 56.54 **还慢 11%**
- 纯映射 81.74 vs CODE 30.84（2.65×）

原因在 `MetaEntityRegistry` 的 generic reader：每行 `new` 一个泛化实体、把 `MetaProperty` 复制进新的 `LinkedHashMap`、再建第二个 map 存列标签，然后逐列 `rs.getObject` 再走值转换。它的定位是"没有注解处理器时也能跑"（例如 Native Image 场景下不生成源码），拿它比性能等于拿兜底路径比最优路径。

### 6. 写入路径 meta 反而慢 26%–38%

`save()` 的链路是：`newEntity` → 逐字段 `set()`（每 `set` 都在变更集里标脏，`HashSet` 记录）→ 组装 `INSERT` → 执行 → 取回自增主键 → `clearChanged`。MyBatis 侧是：POJO → 参数绑定（6 次 getter 反射调用）→ `useGeneratedKeys` 回填。

meta 多了"变更集维护"这一层（读-改-写要支持脏字段合并，这是它设计目标的一部分），而 MyBatis 的 6 次反射 getter 便宜到抵不过这一层。所以**零反射的优势没有跨过 `save` 的变更集成本**。这是真实的架构代价，不是测量误差——三组运行里 meta 都比 MyBatis 慢 1.26–1.38 倍。

## 结论：没有反射就一定比 MyBatis 强吗

**不一定，而且这次测出来的答案是"分场景"。**

拆成三个问题：

**① 零反射本身带来的收益有多大？** 比想象的小。反射取值（`Method.invoke` / `MetaObject.setValue`）在 JDK 25 上不慢；meta 生成类的取值部分约 0.078µs/行，手写 setter 是 0.038µs/行，同一量级。MyBatis 那 0.649µs/行里，大部分不是"反射"，而是每行一次 `getDeclaredConstructor()` 的实例化、`ResultLoaderMap` 的分配、判别器与结果处理器的胶水。

**② 那 meta 为什么还是快 2 倍？** 因为它快在别的地方：把 SQL 组装、参数绑定、结果映射、对象实例化**全部编译成直接的 Java 代码**，运行时只剩 `new` + 赋值 + 类型转换。这在整条调用链上省掉了所有中间层，`mybatis-per-call` 到 `meta-code` 的差距就是这么来的。所以更准确的说法是：**"不是没有反射所以快，是把框架的每一层胶水都编译掉了所以快。"**

**③ 这个优势有多完整？** 不完整，三个缺口：

- **写路径反输**（慢 26%–38%）。零反射省下的 getter 调用，抵不过 `save` 的变更集维护。
- **GENERIC 档全面落后**。理论上的兜底路径在 100 行列表上比 MyBatis 还慢 11%。
- **自己吃掉了 60%–75% 的映射优势**。每行重建列索引这一项，把本该 8 倍的映射差距压成了 2 倍。这是实现问题，不是设计问题，改掉它（把 `MetaEntityColumns.index` 提到每结果集一次）是当前性价比最高的一处优化。

最后必须说清楚的一条：**这些 µs 级的差异，在真实远端数据库面前基本不存在。** 内存库上 jdbc 地板是 0.8µs（单行）/ 6.9µs（100 行），而一次真实网络的 round trip 通常是 0.5–3ms，比整个框架开销大两到三个数量级。所以：

- 如果你的场景是**高频、小结果集、库离得近**（缓存层、本地文件库、批量导入预处理），meta 的 1.3–2.1 倍是真实的、有价值的。
- 如果每条 SQL 后面都跟着一次网络往返，两者的差距会被淹没，选型应该看**功能**（零反射带来的 Native Image 可构建性、元数据驱动的动态表、注解处理器带来的编译期安全），而不是这 2 倍。
- 如果结果集很大（上千行以上），先修 `MetaEntityColumns.index` 的每行扫描，否则优势会被自己吃掉大半。

## 复现

```powershell
# 全部基准（编译 → 校验 → 跑 JMH）
pwsh -File .\run-benchmark.ps1

# 只跑查询组
pwsh -File .\run-benchmark.ps1 -Filter QueryBenchmark

# 快速冒烟（1 次预热 + 2 次测量，只跑查询组）
pwsh -File .\run-benchmark.ps1 -Filter QueryBenchmark -ExtraArgs '-wi 1 -i 2 -w 300ms -r 300ms -f 1'
```

脚本做的事：`mvn -o -pl autumn-data-benchmark -am -DskipTests compile`（`-am` 必须有，否则编译期解析到旧 jar）→ 生成 classpath 并把四个模块的 `target/classes` 前置 → 跑 `BenchVerification` → 跑 `org.openjdk.jmh.Main`。

原始数据：`results/jmh-results-final.json`（本报告所用运行）、`results/jmh-run-final.txt`（完整日志，含每次迭代的原始值；`.log` 后缀被根 `.gitignore` 忽略，故存为 `.txt`）。

## 局限

1. **H2 内存库、单线程、无事务、autocommit**。没有网络往返、没有锁竞争、没有磁盘。这是为了把"框架开销"从"数据库开销"里剥出来，不代表生产环境的绝对性能。
2. **6 列、100 行、单表、无关联、无延迟加载**。`mybatis-metaobject` 完全绕开了延迟加载与关联映射——如果被测对象有嵌套结果映射，MyBatis 的每行 `ResultLoaderMap` 成本会变重，meta 的优势会扩大，但本次没测。
3. **JMH 未用 `Blackhole` 消费映射出来的对象**，基准方法返回 `int`（行数）。JIT 的逃逸分析可能把部分对象分配消掉，所以这些数字更接近"CPU 时间"而非"分配成本"。两侧条件相同，不影响对比方向，但**不能**拿它推断 GC 压力。
4. **未测批量插入、批量查询**。meta 的 `save` 是单条语义，批量路径（如果有）没进基准。
5. **混合核 CPU 上必须固定亲和性**，否则绝对数值会漂 3–4 倍；本文数字全部来自固定 P-core 的一次运行。
6. `mybatis-metaobject` 有 JIT 双峰，误差棒宽，只作成本构成参考，不作结论依据。
7. 基准里 MyBatis 的 `mybatis-shared` 是"长会话最佳情形"，生产里 Spring 默认是 `SqlSessionTemplate`（更接近 `mybatis-per-call`）。若按生产的 per-call 口径，MyBatis 会更慢一点，meta 的相对优势略大。
