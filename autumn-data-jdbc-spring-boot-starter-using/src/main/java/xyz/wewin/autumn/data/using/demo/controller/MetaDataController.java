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
 * autumn-data-meta-jdbc 的 HTTP 验证入口：所有端点只操作运行期建出来的演示表 {@value #TABLE}。
 *
 * <p>推荐调用顺序：{@code POST /metadata/init} → {@code GET /metadata/schema} →
 * {@code POST /metadata/rows} → {@code GET /metadata/rows} → {@code POST /metadata/column} →
 * {@code GET /metadata/statements} → {@code GET /metadata/query/statement} →
 * {@code GET /metadata/cache} → {@code GET /metadata/safety} →
 * {@code DELETE /metadata/drop}。</p>
 *
 * <p>覆盖的能力：运行期建表不重启即 CRUD、统一用 common 的 {@link MetaClass} 承载任意表结构
 * （属性描述里带着每列的 Java/JDBC 类型）、加列后 refresh 用新结构继续读写、
 * 参数实体 + XML 语句与分页、spec/xml 自定义语句与 auto/xml 默认语句分时管理、
 * 自定义语句的分页 / 写 / 按条件取一行、
 * 表结构 / 实体模型 / 语句三级缓存的分开清理与重载、
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

	/** spec/xml 手写语句目录；纯内存模式为 null。init/drop 时只清理本演示表自己的 spec 文件。 */
	private final Path sqlDirectory;

	public MetaDataController(JdbcOperations jdbc,
			@Value("${autumn.data.meta.mapper.meta-directory:meta-data}") String metaDirectory) {
		this.jdbc = jdbc;
		this.sqlDirectory = MetaDirectories.of(metaDirectory).specXmlDirectory("default", "default");
	}

	/**
	 * 使用方手写的 spec/xml：框架只读、绝不改写；七条默认语句另放在 auto/xml，
	 * 同 id 时 spec 覆盖 auto。改完这段 XML 清一下语句缓存即可生效。
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
			            <if test="minAmount != null">AND amount &gt;= #{minAmount}</if>
			            <if test="keyword != null">AND form_no LIKE #{keyword}</if>
			            <if test="remark != null">AND remark = #{remark}</if>
			            <if test="deleted_flag != null">AND deleted_flag = #{deleted_flag}</if>
			        </where>
			        ORDER BY
			        <choose>
			            <when test="orderBy != null">${orderBy}</when>
			            <otherwise>id</otherwise>
			        </choose>
			        <choose>
			            <when test="desc">DESC</when>
			            <otherwise>ASC</otherwise>
			        </choose>
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

			    <!-- 比较 / 区间 / 模糊 / IN / 空值 / 排序：复杂条件写在 XML，Java 侧只喂参数实体 -->
			    <select id="ge">
			        SELECT form_no FROM ${table} WHERE deleted_flag = 0 AND amount &gt;= #{min} ORDER BY id
			    </select>
			    <select id="between">
			        SELECT form_no FROM ${table} WHERE deleted_flag = 0
			        AND amount BETWEEN #{lo} AND #{hi} ORDER BY id
			    </select>
			    <select id="likeTitle">
			        SELECT form_no FROM ${table} WHERE deleted_flag = 0 AND title LIKE #{kw} ORDER BY id
			    </select>
			    <select id="statusIn">
			        SELECT form_no FROM ${table} WHERE deleted_flag = 0 AND status IN (1, 2) ORDER BY id
			    </select>
			    <select id="remarkNull">
			        SELECT form_no FROM ${table} WHERE deleted_flag = 0 AND remark IS NULL ORDER BY id
			    </select>
			    <select id="amountNotNull">
			        SELECT form_no FROM ${table} WHERE deleted_flag = 0 AND amount IS NOT NULL ORDER BY id
			    </select>
			    <select id="amountDesc">
			        SELECT form_no FROM ${table} WHERE deleted_flag = 0 ORDER BY amount DESC
			    </select>
			    <select id="countVoided">
			        SELECT COUNT(*) FROM ${table} WHERE id = #{id} AND deleted_flag = #{flag}
			    </select>

			    <!-- 自定义语句分页：内容用这条语句，计数由 selectPage 自动包一层子查询 -->
			    <select id="pageByAmount">
			        SELECT id, form_no, title, amount, status FROM ${table}
			        WHERE status = #{status} AND amount &gt;= #{minAmount} ORDER BY amount DESC
			    </select>
			    <insert id="insertRaw">
			        INSERT INTO ${table} (form_no, title, amount, status, create_time)
			        VALUES (#{form_no}, #{title}, #{amount}, #{status}, #{create_time})
			    </insert>
			    <update id="updateRemarkByFormNo">
			        UPDATE ${table} SET remark = #{remark} WHERE form_no = #{formNo}
			    </update>
			    <select id="byFormNo">
			        SELECT id, form_no, remark FROM ${table} WHERE form_no = #{formNo}
			    </select>
			</mapper>
			""";

	// ==================================================================
	// 一、运行期建表 / 元数据 / 运行期生成的实体类
	// ==================================================================

	/**
	 * 运行期建表并灌入种子数据：不重启、不改代码，建完立刻就能 newEntity/save/findById。
	 * 同时预置一份 spec/xml 手写语句；首次访问时框架在 auto/xml 生成 findOne / findList / save
	 * 等七条默认语句，spec 文件保持原样。
	 */
	@PostMapping("/init")
	public Map<String, Object> init() {
		dropStatementFile();
		this.jdbc.execute("DROP TABLE IF EXISTS " + TABLE);
		this.jdbc.execute("CREATE TABLE " + TABLE + " ("
				+ " id BIGINT AUTO_INCREMENT PRIMARY KEY,"
				+ " form_no VARCHAR(32) NOT NULL,"
				+ " customer_no VARCHAR(32),"
				+ " title VARCHAR(64),"
				+ " amount DECIMAL(12,2),"
				+ " status INT,"
				+ " remark VARCHAR(64),"
				+ " create_time TIMESTAMP,"
				+ " deleted_flag INT DEFAULT 0)");
		MetaMapper.refresh(TABLE); // 丢掉旧表结构快照 / 旧实体类 / 语句与计划缓存
		writePresetStatementFile(); // 使用方手写 spec/xml；默认语句由框架另写 auto/xml
		List<MetaClass> saved = MetaMapper.saveAll(seedRows());
		this.log.info("runtime table {} created, {} rows seeded, entity={}", TABLE, saved.size(),
				MetaMapper.entityType(TABLE).getName());
		Map<String, Object> result = schema();
		result.put("seedRows", saved.size());
		result.put("statementFile", existingStatementFile() == null ? null : existingStatementFile().toString());
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
	 * 表结构快照：列名、Java 类型、JDBC 类型、主键/自增/可空全来自 DatabaseMetaData；
	 * entityClass 恒为通用 {@link MetaClass}，类型信息在每列的 MetaProperty 上。
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
				"entityClass", type.getName(), "unifiedMetaClass", type == MetaClass.class,
				"logicDeleteColumn", "deleted_flag", "tip", "列类型跟着表结构走，加列后属性描述会同步更新");
	}

	/**
	 * 运行期加列 + refresh：不重启服务新列即刻可读写，结构指纹变化会让 auto/xml 整份重写。
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
		int columnsBefore = MetaMapper.newEntity(TABLE).getProperties().size();
		if (MetaMapper.newEntity(TABLE).has(name)) {
			return linked("added", false, "column", name, "reason", "column already exists",
					"columnCount", columnsBefore);
		}
		this.jdbc.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + name + " " + type);
		MetaMapper.refresh(TABLE);
		int columnsAfter = MetaMapper.newEntity(TABLE).getProperties().size();

		// 新列立即参与写与读：拿一行写入，再按主键读回来
		MetaClass row = MetaMapper.findPage(TABLE, params(), Pageable.of(0, 1)).content().get(0);
		row.set(name, sampleValue(type));
		int updated = MetaMapper.update(row);
		MetaClass reread = MetaMapper.findById(TABLE, row.get("id")).orElseThrow();
		this.log.info("column {} added, columns {} -> {}", name, columnsBefore, columnsAfter);
		return linked("added", true, "column", name, "type", type,
				"columnCountBefore", columnsBefore, "columnCountAfter", columnsAfter,
				"structureChanged", columnsAfter > columnsBefore, "writtenRows", updated,
				"readBack", reread.get(name), "readBackJavaType", reread.property(name).getType(),
				"statementFile", "auto/xml 整份重写，spec/xml 自定义语句保持原样");
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

	/** 条件 + 排序 + 分页：条件值只进 #{ } 绑定，排序标识符先过元数据白名单，SQL 全部来自语句 XML。 */
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
		MetaClass query = params("orderBy", order, "desc", desc);
		if (status != null) {
			query.set("status", status);
		}
		if (minAmount != null) {
			query.set("minAmount", minAmount);
		}
		if (keyword != null && !keyword.isBlank()) {
			query.set("keyword", "%" + keyword + "%");
		}
		Page<MetaClass> result = MetaMapper.defaultClient()
				.selectPage(TABLE, "selectList", query, Pageable.of(page, size));
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
				"voidedRowsFoundByFlag", MetaMapper.defaultClient()
						.selectCount(TABLE, "countVoided", params("id", id, "flag", 1)));
	}

	// ==================================================================
	// 三、参数实体 + 命名语句 / 防注入 / 引擎诊断
	// ==================================================================

	/** 一条端点跑遍比较、模糊、区间、IN、空值、排序、限行、count、exists：SQL 全在语句 XML 里。 */
	@GetMapping("/query/operators")
	public Map<String, Object> queryOperators() {
		requireInitialized();
		MetaMapperClient client = MetaMapper.defaultClient();
		return linked(
				"GE", formNos(client.selectList(TABLE, "ge", params("min", new BigDecimal("50")))),
				"BETWEEN", formNos(client.selectList(TABLE, "between",
						params("lo", new BigDecimal("10"), "hi", new BigDecimal("100")))),
				"LIKE", formNos(client.selectList(TABLE, "likeTitle", params("kw", "%动态%"))),
				"IN", formNos(client.selectList(TABLE, "statusIn", params())),
				"IS_NULL", formNos(client.selectList(TABLE, "remarkNull", params())),
				"IS_NOT_NULL", formNos(client.selectList(TABLE, "amountNotNull", params())),
				"ORDER_BY_DESC", formNos(client.selectList(TABLE, "amountDesc", params())),
				"LIMIT_2", formNos(MetaMapper.findPage(TABLE, params(), Pageable.of(0, 2)).content()),
				"COUNT_status_1", MetaMapper.findPage(TABLE, params("status", 1), Pageable.of(0, 1)).total(),
				"EXISTS_F-001", MetaMapper.findOne(TABLE, params("form_no", "F-001")).isPresent());
	}

	/**
	 * 命名语句：七条默认语句在 auto/xml 按表结构生成，自定义语句在 spec/xml 归使用方所有、
	 * 表结构变化时逐字保留；同 id 时 spec 覆盖 auto。
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
	// 四、自定义语句分页 / 写 / 缓存分级清理
	// ==================================================================

	/**
	 * 自定义语句分页 + 写 + 按条件取一行：内容语句负责查询，计数由 {@code selectPage} 自动包一层子查询；
	 * 写语句走 {@code execute}。SQL 全部写在 {@code meta_demo_form.xml} 的自定义区里。
	 */
	@GetMapping({ "/query/statement", "/query/sql" })
	public Map<String, Object> statementPaging(@RequestParam(defaultValue = "1") int status,
			@RequestParam(defaultValue = "50") BigDecimal minAmount,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "5") int size) {
		requireInitialized();
		if (page < 0 || size < 1 || size > 50) {
			throw new IllegalArgumentException("page must be >= 0 and size between 1 and 50");
		}
		MetaMapperClient client = MetaMapper.defaultClient();
		MetaClass query = params("status", status, "minAmount", minAmount);

		MarkedSql preview = client.sqlFor(TABLE, "pageByAmount", query);
		Page<MetaClass> firstPage = client.selectPage(TABLE, "pageByAmount", query, Pageable.of(page, size));

		String formNo = "STMT-" + System.nanoTime();
		MetaClass insertParams = params("form_no", formNo, "title", "自定义语句插入", "amount", "9.90", "status", 3,
				"create_time", LocalDateTime.now());
		int inserted = client.execute(TABLE, "insertRaw", insertParams);
		int updated = client.execute(TABLE, "updateRemarkByFormNo",
				params("remark", "自定义语句改的", "formNo", formNo));
		MetaClass reread = client.selectOne(TABLE, "byFormNo", params("formNo", formNo)).orElseThrow();

		return linked("sql", preview.sql(), "boundValues", preview.values(),
				"pageTotal", firstPage.total(), "pageRows", maps(firstPage.content()),
				"insertedParams", insertParams, "insertedRows", inserted, "updatedRows", updated,
				"rereadRemark", reread.getString("remark"));
	}

	/** 各级缓存条目与上限：表结构、实体类、语句、执行计划分别有界。 */
	@GetMapping("/cache")
	public Map<String, Object> cache() {
		requireInitialized();
		return linked("stats", MetaMapper.cacheStats(), "entityClass", MetaMapper.entityType(TABLE).getName());
	}

	/**
	 * 分级清理：{@code scope=table} 只丢表结构快照、{@code entity} 只丢实体模型读取器、
	 * {@code statement} 只丢语句与执行计划、{@code all} 四件套一起丢。
	 * 实体类型恒为 {@link MetaClass}，用列数和缓存统计验证各级缓存是分开的。
	 */
	@PostMapping("/cache/clear")
	public Map<String, Object> clearCache(@RequestParam(defaultValue = "table") String scope) {
		requireInitialized();
		switch (scope) {
			case "table" -> MetaMapper.defaultClient().clearTableCache(TABLE);
			case "entity" -> MetaMapper.defaultClient().clearEntityCache(TABLE);
			case "statement" -> MetaMapper.defaultClient().clearStatementCache(TABLE);
			case "all" -> MetaMapper.refresh(TABLE);
			default -> throw new IllegalArgumentException(
					"scope must be table | entity | statement | all, got: " + scope);
		}
		return linked("scope", scope, "entityClass", MetaMapper.entityType(TABLE).getName(),
				"columnCount", MetaMapper.newEntity(TABLE).getProperties().size(),
				"stats", MetaMapper.cacheStats());
	}

	/**
	 * 重新加载：清缓存 + 当场回读元数据 + 当场重建实体模型，而不是等下一次查询才懒加载。
	 * 表已被删掉时这里就会直接报错，不会留到业务请求才炸。
	 */
	@PostMapping("/reload")
	public Map<String, Object> reload() {
		requireInitialized();
		MetaClass rebuilt = MetaMapper.defaultClient().reload(TABLE);
		return linked("table", TABLE, "columns", rebuilt.getProperties().stream().map(MetaProperty::getName).toList(),
				"entityClass", rebuilt.getClass().getName(), "stats", MetaMapper.cacheStats());
	}

	/** 概览 + 端点索引，方便直接照着敲 URL 手工验证。 */
	@GetMapping("/summary")
	public Map<String, Object> summary() {
		Map<String, Object> out = linked("table", TABLE);
		try {
			out.put("initialized", true);
			out.put("rows", MetaMapper.findPage(TABLE, params(), Pageable.of(0, 1)).total());
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
				"GET /metadata/statements", "GET /metadata/query/statement?status=1&minAmount=50&page=0&size=5",
				"GET /metadata/cache",
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
		deleteIfExists(statementFile());
	}

	private static void deleteIfExists(Path file) {
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

	/** 手写语句文件路径 = {语句根}/{数据源}/{命名空间}/spec/xml/{表名}.xml，与引擎的 spec 语句库一致。 */
	private Path statementFile() {
		return this.sqlDirectory == null ? null : this.sqlDirectory.resolve(TABLE + ".xml");
	}

	/** init 时写一份 spec/xml 手写语句；框架只读，默认语句另写 auto/xml。 */
	private void writePresetStatementFile() {
		Path file = statementFile();
		if (file == null) {
			return; // 纯内存模式：默认语句照样在内存里生成，不落盘
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
