package xyz.wewin.autumn.data.using.demo.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.sql.MarkedSql;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories;

/**
 * autumn-data-meta-jdbc 的 HTTP 验证入口：所有端点只操作运行期建出来的演示表 {@value #TABLE}，
 * 不碰示例库里已有的 basic_user / lib_* 表。
 *
 * <p>推荐调用顺序：{@code POST /metadata/init} → {@code GET /metadata/schema} →
 * {@code POST /metadata/rows} → {@code GET /metadata/rows} → {@code POST /metadata/column} →
 * {@code GET /metadata/statements} → {@code GET /metadata/query/sql} →
 * {@code POST /metadata/statements/custom} → {@code GET /metadata/cache} →
 * {@code GET /metadata/safety} → {@code DELETE /metadata/drop}。</p>
 *
 * <p>覆盖的能力：运行期建表不重启即 CRUD、按表结构运行期生成强类型实体（entityClass）、
 * 加列后 refresh 换实体类、参数实体 + XML 语句与分页、语句 XML 自动生成块与自定义语句、
 * 手写 SQL（不落文件即查即写）与代码注册语句、表结构 / 实体类 / 语句三级缓存的分开清理与重载、
 * 逻辑删除自动过滤、列名与标识符白名单（防注入）、按池绑定的客户端与缓存诊断。</p>
 *
 * <p>启动：{@code mvn spring-boot:run -Dspring-boot.run.profiles=demo}（连本地 H2）。
 * 演示表由本控制器自行建/删，DDL 里的列名与类型都过白名单，不接受用户输入拼串。</p>
 */
@RestController
@RequestMapping("/metadata")
public class MetaDataController {

	/** 运行期建出来的演示表：单一自增主键 + 逻辑删除列（查询自动追加 deleted_flag = 0）。 */
	private static final String TABLE = "meta_demo_form";

	private static final Pattern COLUMN_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,30}");

	/** 加列端点允许的类型：DDL 只拼白名单里的字面量。 */
	private static final Set<String> COLUMN_TYPES = Set.of("VARCHAR(64)", "INT", "BIGINT", "DECIMAL(12,2)",
			"TIMESTAMP", "DATE", "BOOLEAN");

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	private final JdbcOperations jdbc;

	/** 语句 XML 根目录；纯内存模式为 null。init/drop 时只清理本演示表自己的文件。 */
	private final Path sqlDirectory;

	public MetaDataController(JdbcOperations jdbc,
			@Value("${autumn.data.meta.mapper.meta-directory:autumn-meta}") String metaDirectory) {
		this.jdbc = jdbc;
		this.sqlDirectory = MetaDirectories.of(metaDirectory).sqlDirectory();
	}

	/**
	 * 使用方手写的自定义语句区：没有 GENERATED 标记，框架不会改写它，只在内存补上七条默认语句。
	 * 这样自定义 id 与 findList/findPage/findOne/findById/save/update/delete 在同一份文件里共存。
	 */
	private static final String PRESET_STATEMENTS = """
			<?xml version="1.0" encoding="UTF-8"?>
			<mapper namespace="default/default/meta_demo_form">
			    <select id="selectList">
			        SELECT id, form_no, title, amount, status, remark, create_time, deleted_flag FROM ${table}
			        <where>
			            AND deleted_flag = 0
			            <if test="id != null">AND id = #{id}</if>
			            <if test="form_no != null">AND form_no = #{form_no}</if>
			            <if test="amount != null">AND amount = #{amount}</if>
			            <if test="status != null">AND status = #{status}</if>
			            <if test="remark != null">AND remark = #{remark}</if>
			            <if test="deleted_flag != null">AND deleted_flag = #{deleted_flag}</if>
			        </where>
			        ORDER BY id
			    </select>
			    <select id="selectCount">SELECT COUNT(*) FROM ${table} WHERE deleted_flag = 0</select>
			    <insert id="insert">
			        INSERT INTO ${table}
			        <trim prefix="(" suffix=")" suffixOverrides=",">
			            <if test="form_no != null">form_no,</if>
			            <if test="title != null">title,</if>
			            <if test="amount != null">amount,</if>
			            <if test="status != null">status,</if>
			            <if test="create_time != null">create_time,</if>
			        </trim>
			        <trim prefix="VALUES (" suffix=")" suffixOverrides=",">
			            <if test="form_no != null">#{form_no},</if>
			            <if test="title != null">#{title},</if>
			            <if test="amount != null">#{amount},</if>
			            <if test="status != null">#{status},</if>
			            <if test="create_time != null">#{create_time},</if>
			        </trim>
			    </insert>
			    <update id="updateByKey">UPDATE ${table} SET remark = #{remark} WHERE id = #{id}</update>
			</mapper>
			""";

	// ==================================================================
	// 一、运行期建表 / 元数据 / 运行期生成的实体类
	// ==================================================================

	/**
	 * 运行期建表并灌入种子数据：不重启、不改代码，建完立刻就能 newEntity/save/findById。
	 * 同时预置一份「使用方手写」的语句 XML（无 GENERATED 标记，框架不改写、只在内存补默认语句）。
	 */
	@PostMapping("/init")
	public Map<String, Object> init() {
		dropStatementFile();
		this.jdbc.execute("DROP TABLE IF EXISTS " + TABLE);
		this.jdbc.execute("CREATE TABLE " + TABLE + " ("
				+ " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
				+ " form_no VARCHAR(32) NOT NULL,"
				+ " title VARCHAR(64),"
				+ " amount DECIMAL(12,2),"
				+ " status INT,"
				+ " remark VARCHAR(64),"
				+ " create_time TIMESTAMP,"
				+ " deleted_flag INT DEFAULT 0)");
		MetaMapper.refresh(TABLE); // 丢掉旧表结构快照 / 旧实体类 / 语句与计划缓存
		writePresetStatementFile(); // 写一份「使用方手写」的自定义语句区，框架不会改写
		List<MetaClass> saved = MetaMapper.saveAll(seedRows());
		this.log.info("runtime table {} created, {} rows seeded, entity={}", TABLE, saved.size(),
				MetaMapper.entityType(TABLE).getName());
		Map<String, Object> result = schema();
		result.put("seedRows", saved.size());
		return result;
	}

	/** 清掉演示表，方便反复验证「运行期建表」。 */
	@DeleteMapping("/drop")
	public Map<String, Object> drop() {
		this.jdbc.execute("DROP TABLE IF EXISTS " + TABLE);
		MetaMapper.refresh(TABLE);
		dropStatementFile();
		return linked("dropped", TABLE, "hint", "POST /metadata/init to rebuild it at runtime");
	}

	/**
	 * 表结构快照：列名、Java 类型、JDBC 类型、主键/自增/可空全来自 DatabaseMetaData，
	 * entityClass 是按这版结构在运行期编译出来的 MetaClass 子类（GENERIC 模式恒为 MetaClass）。
	 */
	@GetMapping("/schema")
	public Map<String, Object> schema() {
		requireInitialized();
		MetaClass entity = MetaMapper.newEntity(TABLE);
		List<Map<String, Object>> columns = new ArrayList<>();
		for (MetaProperty column : entity.getProperties()) {
			columns.add(linked("name", column.getName(), "javaType", column.getType(),
					"jdbcType", column.getJdbcType(), "sqlType", column.getSqlTypeName(),
					"primaryKey", column.isPrimaryKey(), "autoIncrement", column.isAutoIncrement(),
					"nullable", column.isNullable(), "defaultValue", column.getDefaultValue()));
		}
		Class<? extends MetaClass> type = MetaMapper.entityType(TABLE);
		return linked("table", entity.getName(), "columnCount", columns.size(), "columns", columns,
				"entityClass", type.getName(), "generatedEntity", type != MetaClass.class,
				"logicDeleteColumn", "deleted_flag", "tip", "列类型跟着表结构走，加列后 entityClass 会换成新类");
	}

	/**
	 * 运行期加列 + refresh：不重启服务新列即刻可读写，且结构指纹变化会生成一份新的实体类。
	 * 列名过正则、类型过白名单，用户输入不会拼进 DDL。
	 */
	@PostMapping("/column")
	public Map<String, Object> addColumn(@RequestParam(defaultValue = "courier") String name,
			@RequestParam(defaultValue = "VARCHAR(64)") String type) {
		requireInitialized();
		if (!COLUMN_NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("invalid column name: " + name);
		}
		if (!COLUMN_TYPES.contains(type)) {
			throw new IllegalArgumentException("unsupported column type: " + type);
		}
		String before = MetaMapper.entityType(TABLE).getName();
		if (MetaMapper.newEntity(TABLE).has(name)) {
			return linked("added", false, "column", name, "reason", "column already exists",
					"entityClass", before);
		}
		this.jdbc.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + name + " " + type);
		MetaMapper.refresh(TABLE);
		String after = MetaMapper.entityType(TABLE).getName();

		// 新列立即参与写与读：拿一行写入，再按主键读回来
		MetaClass row = MetaMapper.findList(TABLE, params(), 1).get(0);
		row.set(name, sampleValue(type));
		int updated = MetaMapper.update(row);
		MetaClass reread = MetaMapper.findById(TABLE, row.get("id")).orElseThrow();
		this.log.info("column {} added, entity {} -> {}", name, before, after);
		return linked("added", true, "column", name, "type", type,
				"entityClassBefore", before, "entityClassAfter", after,
				"entityClassChanged", !before.equals(after), "writtenRows", updated,
				"readBack", reread.get(name), "readBackJavaType", reread.property(name).getType(),
				"statementFile", "无 GENERATED 标记的手写文件归使用方所有，框架一个字都不改写");
	}

	// ==================================================================
	// 二、实体驱动的 CRUD（saveAll / 部分更新 / 逻辑删除）
	// ==================================================================

	/** 批量新增：写过的列一致走 JDBC batch，自增主键按顺序回填到每个实体上。 */
	@PostMapping("/rows")
	public Map<String, Object> addRows(@RequestParam(defaultValue = "2") int count) {
		requireInitialized();
		if (count < 1 || count > 50) {
			throw new IllegalArgumentException("count must be between 1 and 50");
		}
		long stamp = System.nanoTime();
		List<MetaClass> rows = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			MetaClass row = MetaMapper.newEntity(TABLE);
			row.set("form_no", "B-" + stamp + "-" + i);
			row.set("title", "批量单据-" + i);
			row.set("amount", BigDecimal.valueOf(10L * (i + 1)));
			row.set("status", (i % 2) + 1);
			row.set("create_time", LocalDateTime.now());
			rows.add(row);
		}
		List<MetaClass> saved = MetaMapper.saveAll(rows);
		return linked("saved", saved.size(),
				"generatedKeys", saved.stream().map(row -> row.get("id")).toList(), "rows", maps(saved));
	}

	/** 条件 + 排序 + 分页：条件值只进 #{ } 绑定，排序标识符先过元数据白名单。 */
	@GetMapping("/rows")
	public Map<String, Object> findRows(@RequestParam(required = false) Integer status,
			@RequestParam(required = false) BigDecimal minAmount,
			@RequestParam(required = false) String keyword,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size,
			@RequestParam(defaultValue = "id") String order,
			@RequestParam(defaultValue = "false") boolean desc) {
		requireInitialized();
		if (page < 0 || size < 1 || size > 100) {
			throw new IllegalArgumentException("page must be >= 0 and size between 1 and 100");
		}
		requireColumn(order);
		StringBuilder sql = new StringBuilder(
				"SELECT id, form_no, title, amount, status, remark, create_time FROM ${table} "
						+ "WHERE deleted_flag = 0");
		MetaClass query = params();
		if (status != null) {
			sql.append(" AND status = #{status}");
			query.set("status", status);
		}
		if (minAmount != null) {
			sql.append(" AND amount >= #{minAmount}");
			query.set("minAmount", minAmount);
		}
		if (keyword != null && !keyword.isBlank()) {
			sql.append(" AND form_no LIKE #{keyword}");
			query.set("keyword", "%" + keyword + "%");
		}
		sql.append(" ORDER BY ").append(order).append(desc ? " DESC" : " ASC");
		Page<MetaClass> result = MetaMapper.findPageBySql(TABLE, sql.toString(), null, query,
				Pageable.of(page, size));
		return linked("page", result.page(), "size", result.size(), "total", result.total(),
				"totalPages", result.totalPages(), "content", maps(result.content()));
	}

	/** 按主键取一行，并展示类型化 getter（getString/getBigDecimal/getInt/getLong/getLocalDateTime）。 */
	@GetMapping("/rows/{id}")
	public Map<String, Object> findById(@PathVariable long id) {
		requireInitialized();
		MetaClass row = MetaMapper.findById(TABLE, id)
				.orElseThrow(() -> new IllegalArgumentException("no row with id " + id));
		return linked("row", rowMap(row), "typed", linked(
				"id", row.getLong("id"), "formNo", row.getString("form_no"),
				"amount", row.getBigDecimal("amount"), "status", row.getInt("status"),
				"createTime", row.getLocalDateTime("create_time"), "deletedFlag", row.getInt("deleted_flag")),
				"json", row.toJson(), "hasChanges", row.hasChanges());
	}

	/**
	 * 部分更新：只写传了的列（写 null 才是显式置空），WHERE 用实体主键。
	 * changedColumns 是 update 前拍下的变更集，成功写回后实体会被清脏。
	 */
	@PutMapping("/rows/{id}")
	public Map<String, Object> updateRow(@PathVariable long id,
			@RequestParam(required = false) Integer status,
			@RequestParam(required = false) BigDecimal amount,
			@RequestParam(required = false) String remark,
			@RequestParam(defaultValue = "false") boolean clearRemark) {
		requireInitialized();
		MetaClass row = MetaMapper.findById(TABLE, id)
				.orElseThrow(() -> new IllegalArgumentException("no row with id " + id));
		if (status != null) {
			row.set("status", status);
		}
		if (amount != null) {
			row.set("amount", amount);
		}
		if (remark != null) {
			row.set("remark", remark);
		}
		if (clearRemark) {
			row.set("remark", null);
		}
		List<String> changedColumns = List.copyOf(row.changedNames());
		int rows = MetaMapper.update(row);
		return linked("changedColumns", changedColumns, "updatedRows", rows,
				"row", MetaMapper.findById(TABLE, id).map(MetaDataController::rowMap).orElse(null),
				"cleanAfterUpdate", !row.hasChanges());
	}

	/**
	 * 逻辑删除：只把 deleted_flag 改成 1。查询默认自动追加 deleted_flag = 0 所以查不到，
	 * 显式按 deleted_flag = 1 查能查到（说明行还在）。删除语义由 XML 里的 {@code delete} 语句决定：
	 * 本表有逻辑删除列，默认语句生成逻辑删除；想物理删除只需在自定义区覆盖那条语句。
	 */
	@PostMapping("/rows/{id}/void")
	public Map<String, Object> voidRow(@PathVariable long id) {
		requireInitialized();
		MetaClass row = MetaMapper.findById(TABLE, id)
				.orElseThrow(() -> new IllegalArgumentException("no row with id " + id));
		row.set("deleted_flag", 1);
		int rows = MetaMapper.update(row);
		return linked("updatedRows", rows,
				"visibleAfterVoid", MetaMapper.findById(TABLE, id).isPresent(),
				"voidedRowsFoundByFlag", MetaMapper.findListBySql(TABLE,
						"SELECT id FROM ${table} WHERE id = #{id} AND deleted_flag = #{flag}",
						params("id", id, "flag", 1)).size());
	}

	// ==================================================================
	// 三、参数实体 + 手写 SQL / 命名语句 / 防注入 / 引擎诊断
	// ==================================================================

	/** 一条端点跑遍比较、模糊、区间、IN、空值、排序、限行、count、exists：都是同一套 #{ } 绑定语法。 */
	@GetMapping("/query/operators")
	public Map<String, Object> queryOperators() {
		requireInitialized();
		String base = "SELECT form_no FROM ${table} WHERE deleted_flag = 0";
		return linked(
				"GE", formNos(MetaMapper.findListBySql(TABLE, base + " AND amount >= #{min}",
						params("min", new BigDecimal("50")))),
				"BETWEEN", formNos(MetaMapper.findListBySql(TABLE, base + " AND amount BETWEEN #{lo} AND #{hi}",
						params("lo", new BigDecimal("10"), "hi", new BigDecimal("100")))),
				"LIKE", formNos(MetaMapper.findListBySql(TABLE, base + " AND title LIKE #{kw}",
						params("kw", "%动态%"))),
				"IN", formNos(MetaMapper.findListBySql(TABLE, base + " AND status IN (1, 2)", params())),
				"IS_NULL", formNos(MetaMapper.findListBySql(TABLE, base + " AND remark IS NULL", params())),
				"IS_NOT_NULL", formNos(MetaMapper.findListBySql(TABLE, base + " AND amount IS NOT NULL", params())),
				"ORDER_BY_DESC", formNos(MetaMapper.findListBySql(TABLE, base + " ORDER BY amount DESC", params())),
				"LIMIT_2", formNos(MetaMapper.findList(TABLE, params(), 2)),
				"COUNT_status_1", MetaMapper.count(TABLE, params("status", 1)),
				"EXISTS_F-001", MetaMapper.exists(TABLE, params("form_no", "F-001")));
	}

	/**
	 * 命名语句（同一份语句 XML）：七条默认语句在 GENERATED 块里按表结构生成，
	 * 标记之外的自定义语句归使用方、表结构变化时逐字保留。
	 * sqlFor 只抽取不执行，selectList/selectCount/execute 分别走读、计数与写。
	 */
	@GetMapping("/statements")
	public Map<String, Object> namedStatements() {
		requireInitialized();
		MetaMapperClient client = MetaMapper.defaultClient();
		MetaClass listParams = params("status", 1);
		MarkedSql listSql = client.sqlFor(TABLE, "selectList", listParams);
		List<MetaClass> listed = client.selectList(TABLE, "selectList", listParams);
		long count = client.selectCount(TABLE, "selectCount", params());

		String formNo = "X-" + System.nanoTime();
		MetaClass insertParams = params("form_no", formNo, "title", "命名语句插入", "amount", "66.00", "status", 1);
		MarkedSql insertSql = client.sqlFor(TABLE, "insert", insertParams);
		int inserted = client.execute(TABLE, "insert", insertParams);
		MetaClass insertedRow = client.selectOne(TABLE, "selectList", params("form_no", formNo)).orElseThrow();
		int updated = client.execute(TABLE, "updateByKey",
				params("id", insertedRow.get("id"), "remark", "生成语句改的"));
		MetaClass after = client.selectOne(TABLE, "selectList", params("form_no", formNo)).orElseThrow();

		Path file = existingStatementFile();
		return linked("statementFile", file == null ? null : file.toString(),
				"listSql", listSql.sql(), "listValues", listSql.values(),
				"listedRows", maps(listed), "selectCount", count,
				"insertSql", insertSql.sql(), "insertValues", insertSql.values(),
				"insertedRows", inserted, "updatedRows", updated,
				"afterUpdateRemark", after.getString("remark"));
	}

	/**
	 * 防注入两条边界：#{} 的值永远不进 SQL 文本；列名/排序列必须能在元数据里找到，
	 * 用户字符串根本进不了标识符位置。这里把拒绝信息返回而不是抛 500。
	 */
	@GetMapping("/safety")
	public Map<String, Object> safety() {
		requireInitialized();
		MetaMapperClient client = MetaMapper.defaultClient();
		String payload = "F-001' OR '1'='1";
		MarkedSql bound = client.sqlFor(TABLE, "selectList", params("form_no", payload));
		int matched = client.selectList(TABLE, "selectList", params("form_no", payload)).size();
		return linked("payload", payload,
				"valueInSqlText", bound.sql().contains(payload), "boundValues", bound.values(),
				"matchedRows", matched,
				"illegalConditionColumn", rejection(() -> {
					requireColumn("form_no = 'x' OR 1=1");
					MetaMapper.findList(TABLE, params("form_no = 'x' OR 1=1", "y"));
				}),
				"illegalOrderByIdentifier", rejection(() -> {
					requireColumn("status; DROP TABLE " + TABLE);
					MetaMapper.findList(TABLE, params());
				}));
	}

	/** 引擎诊断：默认池、已实例化的池、编译过的 SQL 形状数、当前实体类。 */
	@GetMapping("/engine")
	public Map<String, Object> engine() {
		requireInitialized();
		return linked("defaultPoolName", MetaMapper.engine().defaultPoolName(),
				"activePools", MetaMapper.engine().clients().keySet().stream().sorted().toList(),
				"boundPool", MetaMapper.of(MetaMapper.engine().defaultPoolName()).poolName(),
				"compiledShapes", MetaMapper.defaultClient().compiledShapes(),
				"entityClass", MetaMapper.entityType(TABLE).getName());
	}

	// ==================================================================
	// 四、手写 SQL / 代码注册语句 / 缓存分级清理
	// ==================================================================

	/**
	 * 手写 SQL：不落语句文件、不注册，直接把一段 SQL 交给 MetaMapper 执行。
	 *
	 * <p>{@code #{}} 走绑定、{@code ${table}} 由方言填表名；返回里 {@code valueInSqlText=false}
	 * 说明参数确实没进 SQL 文本。分页可以给 countSql，也可以留空自动包子查询计数。</p>
	 */
	@GetMapping("/query/sql")
	public Map<String, Object> rawSqlQuery(@RequestParam(defaultValue = "1") int status,
			@RequestParam(defaultValue = "50") BigDecimal minAmount,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "5") int size) {
		requireInitialized();
		if (page < 0 || size < 1 || size > 50) {
			throw new IllegalArgumentException("page must be >= 0 and size between 1 and 50");
		}
		String sql = "SELECT id, form_no, title, amount, status FROM ${table} "
				+ "WHERE status = #{status} AND amount >= #{minAmount} ORDER BY amount DESC";
		String countSql = "SELECT COUNT(*) FROM ${table} WHERE status = #{status} AND amount >= #{minAmount}";
		MetaClass query = params("status", status, "minAmount", minAmount);

		MarkedSql preview = MetaMapper.sqlForText(TABLE, sql, query);
		List<MetaClass> rows = MetaMapper.findListBySql(TABLE, sql, query, 50);
		long total = MetaMapper.countBySql(TABLE, countSql, query);
		Page<MetaClass> firstPage = MetaMapper.findPageBySql(TABLE, sql, countSql, query, Pageable.of(page, size));
		Page<MetaClass> derivedPage = MetaMapper.findPageBySql(TABLE, sql, null, query, Pageable.of(page, size));

		String formNo = "RAW-" + System.nanoTime();
		MetaClass insertParams = params("form_no", formNo, "title", "手写 SQL 插入", "amount", "9.90", "status", 3);
		int inserted = MetaMapper.executeBySql(TABLE,
				"INSERT INTO ${table} (form_no, title, amount, status, create_time) "
						+ "VALUES (#{form_no}, #{title}, #{amount}, #{status}, #{create_time})",
				insertParams);
		int updated = MetaMapper.executeBySql(TABLE,
				"UPDATE ${table} SET remark = #{remark} WHERE form_no = #{formNo}",
				params("remark", "手写 SQL 改的", "formNo", formNo));
		MetaClass reread = MetaMapper.findOneBySql(TABLE,
				"SELECT id, form_no, remark FROM ${table} WHERE form_no = #{formNo}", params("formNo", formNo))
				.orElseThrow();

		return linked("sql", preview.sql(), "boundValues", preview.values(),
				"payloadInSqlText", preview.sql().contains(String.valueOf(minAmount)),
				"rows", maps(rows), "countBySql", total,
				"pageTotal", firstPage.total(), "pageRows", maps(firstPage.content()),
				"derivedCountPageTotal", derivedPage.total(),
				"insertedParams", insertParams, "insertedRows", inserted, "updatedRows", updated,
				"rereadRemark", reread.getString("remark"));
	}

	/**
	 * 代码注册语句：同一段手写 SQL 注册成命名语句后，{@code selectList/selectOne/sqlFor} 按 id 复用；
	 * 注册不要求表此刻存在，{@code refresh} 不会撤销，只有 {@code unregisterStatement} 才会。
	 */
	@PostMapping("/statements/custom")
	public Map<String, Object> customStatement(@RequestParam(defaultValue = "1") int status) {
		requireInitialized();
		MetaMapperClient client = MetaMapper.defaultClient();
		String id = "customHotRows";
		MetaMapper.registerStatement(TABLE, id,
				"SELECT id, form_no, amount, status FROM ${table} WHERE status = #{status} ORDER BY amount DESC");
		MetaClass query = params("status", status);
		MarkedSql preview = client.sqlFor(TABLE, id, query);
		List<MetaClass> rows = client.selectList(TABLE, id, query);
		List<String> idsWithCustom = client.statementIds(TABLE);
		MetaClass first = client.selectOne(TABLE, id, query).orElse(null);
		boolean removed = MetaMapper.unregisterStatement(TABLE, id);
		List<String> idsAfterRemove = client.statementIds(TABLE);
		return linked("statementId", id, "sql", preview.sql(), "boundValues", preview.values(),
				"matchedRows", maps(rows), "firstRow", first == null ? null : rowMap(first),
				"idsWithCustom", idsWithCustom, "unregistered", removed, "idsAfterRemove", idsAfterRemove);
	}

	/** 各级缓存条目与上限：表结构、实体类、语句、执行计划、代码注册语句分别有界。 */
	@GetMapping("/cache")
	public Map<String, Object> cache() {
		requireInitialized();
		return linked("stats", MetaMapper.cacheStats(), "entityClass", MetaMapper.entityType(TABLE).getName());
	}

	/**
	 * 分级清理：{@code scope=table} 只丢表结构快照、{@code entity} 只丢编译出来的实体类、
	 * {@code statement} 只丢语句与执行计划、{@code all} 四件套一起丢。
	 *
	 * <p>{@code entityClassReused=true} 说明这次清理没有连带换类，正好验证两套缓存是分开的。</p>
	 */
	@PostMapping("/cache/clear")
	public Map<String, Object> clearCache(@RequestParam(defaultValue = "table") String scope) {
		requireInitialized();
		Class<? extends MetaClass> before = MetaMapper.entityType(TABLE);
		switch (scope) {
			case "table" -> MetaMapper.clearTableCache(TABLE);
			case "entity" -> MetaMapper.clearEntityCache(TABLE);
			case "statement" -> MetaMapper.clearStatementCache(TABLE);
			case "all" -> MetaMapper.clearCache(TABLE);
			default -> throw new IllegalArgumentException(
					"scope must be table | entity | statement | all, got: " + scope);
		}
		Class<? extends MetaClass> after = MetaMapper.entityType(TABLE);
		return linked("scope", scope, "entityClass", after.getName(), "entityClassReused", before == after,
				"columnCount", MetaMapper.newEntity(TABLE).getProperties().size(),
				"stats", MetaMapper.cacheStats());
	}

	/**
	 * 重新加载：清缓存 + 当场回读元数据 + 当场重编实体类，而不是等下一次查询才懒加载。
	 * 表已被删掉时这里就会直接报错，不会留到业务请求才炸。
	 */
	@PostMapping("/reload")
	public Map<String, Object> reload() {
		requireInitialized();
		Class<? extends MetaClass> before = MetaMapper.entityType(TABLE);
		MetaClass rebuilt = MetaMapper.reload(TABLE);
		return linked("table", TABLE, "columns", rebuilt.getProperties().stream().map(MetaProperty::getName).toList(),
				"entityClass", rebuilt.getClass().getName(), "entityClassReused", before == rebuilt.getClass(),
				"stats", MetaMapper.cacheStats());
	}

	/** 概览 + 端点索引，方便直接照着敲 URL 手工验证。 */
	@GetMapping("/summary")
	public Map<String, Object> summary() {
		Map<String, Object> out = linked("table", TABLE);
		try {
			out.put("initialized", true);
			out.put("rows", MetaMapper.count(TABLE, params()));
			out.put("entityClass", MetaMapper.entityType(TABLE).getName());
		}
		catch (RuntimeException ex) {
			out.put("initialized", false);
			out.put("hint", "POST /metadata/init to create the table at runtime");
		}
		out.put("endpoints", List.of(
				"POST /metadata/init", "DELETE /metadata/drop", "GET /metadata/schema",
				"POST /metadata/column?name=courier&type=VARCHAR(64)",
				"POST /metadata/rows?count=2", "GET /metadata/rows?status=1&minAmount=50&page=0&size=5",
				"GET /metadata/rows/1", "PUT /metadata/rows/1?remark=加急&status=2",
				"POST /metadata/rows/1/void", "GET /metadata/query/operators",
				"GET /metadata/statements", "GET /metadata/query/sql?status=1&minAmount=50&page=0&size=5",
				"POST /metadata/statements/custom?status=1", "GET /metadata/cache",
				"POST /metadata/cache/clear?scope=table", "POST /metadata/reload",
				"GET /metadata/safety", "GET /metadata/engine"));
		return out;
	}

	/** 参数非法、表没准备好、元数据/语句错误都给一段可读的 JSON，而不是 500 堆栈。 */
	@ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class, MetaSqlException.class })
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, Object> onBadRequest(RuntimeException ex) {
		this.log.warn("metadata demo rejected: {}", ex.getMessage());
		return linked("error", ex.getClass().getSimpleName(), "message", ex.getMessage());
	}

	// ==================================================================
	// 内部工具
	// ==================================================================

	private void requireInitialized() {
		try {
			MetaMapper.newEntity(TABLE);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException(
					"table '" + TABLE + "' is not ready; POST /metadata/init first: " + ex.getMessage());
		}
	}

	private void requireColumn(String column) {
		boolean known = MetaMapper.newEntity(TABLE).getProperties().stream()
				.anyMatch(property -> property.getName().equalsIgnoreCase(column));
		if (!known) {
			throw new IllegalArgumentException("unknown column: " + column);
		}
	}

	private List<MetaClass> seedRows() {
		String[] amounts = { "12.50", "88.00", "150.00" };
		List<MetaClass> rows = new ArrayList<>(amounts.length);
		for (int i = 0; i < amounts.length; i++) {
			MetaClass row = MetaMapper.newEntity(TABLE);
			row.set("form_no", "F-00" + (i + 1));
			row.set("title", "动态单据-" + (i + 1));
			row.set("amount", new BigDecimal(amounts[i]));
			row.set("status", i == 1 ? 2 : 1);
			row.set("create_time", LocalDateTime.now().minusDays(i + 1L));
			rows.add(row);
		}
		return rows;
	}

	private static Object sampleValue(String type) {
		return switch (type) {
			case "VARCHAR(64)" -> "加列后立即写入";
			case "INT" -> 7;
			case "BIGINT" -> 7L;
			case "DECIMAL(12,2)" -> new BigDecimal("7.00");
			case "TIMESTAMP" -> LocalDateTime.now();
			case "DATE" -> LocalDate.now();
			case "BOOLEAN" -> Boolean.TRUE;
			default -> "x";
		};
	}

	/** 只清本表演示文件：让每次 init 都按当前表结构重新预置一次（生产代码不要这么做）。 */
	private void dropStatementFile() {
		Path file = statementFile();
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 该表演示文件是否存在（供端点展示）。 */
	private Path existingStatementFile() {
		Path file = statementFile();
		return file != null && Files.isRegularFile(file) ? file : null;
	}

	/** 路径 = {语句根}/{数据源}/{命名空间}/{表名}.xml，与引擎里的唯一语句库保持一致。 */
	private Path statementFile() {
		if (this.sqlDirectory == null) {
			return null;
		}
		return this.sqlDirectory.resolve(MetaMapper.engine().defaultPoolName())
				.resolve(MetaMapper.DEFAULT_NAMESPACE).resolve(TABLE + ".xml");
	}

	/** init 时写一份手写资产：没有 GENERATED 标记，框架不改写，只补齐七条默认语句。 */
	private void writePresetStatementFile() {
		Path file = statementFile();
		if (file == null) {
			return; // 纯内存模式：默认语句照样在内存里生成，自定义语句可用 registerStatement
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, PRESET_STATEMENTS, java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static List<Map<String, Object>> maps(List<MetaClass> rows) {
		return rows.stream().map(MetaDataController::rowMap).toList();
	}

	/** 列名统一转小写：H2 元数据是大写、MySQL 习惯小写，HTTP 输出对齐一种形状。 */
	private static Map<String, Object> rowMap(MetaClass row) {
		Map<String, Object> out = new LinkedHashMap<>();
		row.toMap().forEach((name, value) -> out.put(name.toLowerCase(Locale.ROOT), value));
		return out;
	}

	private static List<String> formNos(List<MetaClass> rows) {
		return rows.stream().map(row -> row.getString("form_no")).toList();
	}

	private static MetaClass params(Object... nameValues) {
		MetaClass params = new MetaClass();
		for (int i = 0; i < nameValues.length; i += 2) {
			params.set((String) nameValues[i], nameValues[i + 1]);
		}
		return params;
	}

	private static String rejection(Runnable action) {
		try {
			action.run();
			return "NOT REJECTED (check me)";
		}
		catch (RuntimeException ex) {
			return ex.getMessage();
		}
	}

	/** 有序、允许 null 值的返回容器。 */
	private static Map<String, Object> linked(Object... nameValues) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < nameValues.length; i += 2) {
			out.put((String) nameValues[i], nameValues[i + 1]);
		}
		return out;
	}

}
