package xyz.wewin.autumn.data.meta.jdbc.engine;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementType;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.PerThreadConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityColumns;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityModel;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaModelStore;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;
import xyz.wewin.autumn.data.meta.jdbc.sql.MarkedSql;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaPlanCache;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlFileStore;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlGenerator;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动态表 CRUD 的 H2 集成测试：建表 → 不重启直接读写 → 加列后 refresh 继续可用。
 */
class MetaMapperDynamicTest {

	private static final AtomicInteger DB_SEQ = new AtomicInteger();

	/** 同构的表只建一次读取器：所有用例共享注册表，避免每个用例都重新读一遍元数据。 */
	private static final MetaEntityRegistry ENTITIES = new MetaEntityRegistry();

	/**
	 * 使用方手写的自定义语句区（spec/xml）：框架只读，绝不改写；七条默认语句在另一份 auto/xml 里，
	 * 两份文件由语句库按 id 合并，这里的 {@code selectList/selectCount/insert} 与默认语句共存。
	 */
	private static final String CUSTOM_STATEMENTS = """
			<?xml version="1.0" encoding="UTF-8"?>
			<mapper namespace="test/default/form_order">
			    <select id="selectList">
			        SELECT id, order_no, amount, status, create_time, deleted_flag FROM ${table}
			        <where>
			            AND deleted_flag = 0
			            <if test="id != null">AND id = #{id}</if>
			            <if test="order_no != null">AND order_no = #{order_no}</if>
			            <if test="amount != null">AND amount = #{amount}</if>
			            <if test="status != null">AND status = #{status}</if>
			            <if test="create_time != null">AND create_time = #{create_time}</if>
			        </where>
			        ORDER BY id
			    </select>
			    <select id="selectCount">
			        SELECT COUNT(*) FROM ${table}
			        <where>
			            AND deleted_flag = 0
			            <if test="status != null">AND status = #{status}</if>
			        </where>
			    </select>
			    <insert id="insert">
			        INSERT INTO ${table}
			        <trim prefix="(" suffix=")" suffixOverrides=",">
			            <if test="order_no != null">order_no,</if>
			            <if test="amount != null">amount,</if>
			            <if test="status != null">status,</if>
			            <if test="create_time != null">create_time,</if>
			        </trim>
			        <trim prefix="VALUES (" suffix=")" suffixOverrides=",">
			            <if test="order_no != null">#{order_no},</if>
			            <if test="amount != null">#{amount},</if>
			            <if test="status != null">#{status},</if>
			            <if test="create_time != null">#{create_time},</if>
			        </trim>
			    </insert>
			    <update id="updateByKey">
			        UPDATE ${table} SET status = #{status} WHERE id = #{id}
			    </update>
			    <select id="highValue">
			        SELECT id, order_no, amount, status FROM ${table}
			        WHERE status = #{status} AND amount &gt;= #{minAmount} ORDER BY id DESC
			    </select>
			    <select id="createdNotNull">
			        SELECT order_no FROM ${table} WHERE status = #{status} AND create_time IS NOT NULL
			    </select>
			    <select id="createdNull">
			        SELECT order_no FROM ${table} WHERE status = #{status} AND create_time IS NULL
			    </select>
			    <delete id="deleteRow">DELETE FROM ${table} WHERE id = #{id}</delete>
			    <select id="rawHot">
			        SELECT id, order_no, amount FROM ${table} WHERE status = #{status} ORDER BY amount DESC
			    </select>
			    <insert id="insertRaw">
			        INSERT INTO ${table} (order_no, amount, status) VALUES (#{order_no}, #{amount}, #{status})
			    </insert>
			    <select id="countStatus">SELECT COUNT(*) FROM ${table} WHERE status = #{status}</select>
			    <update id="updateAmount">
			        UPDATE ${table} SET amount = #{amount} WHERE order_no = #{order_no}
			    </update>
			    <select id="byOrderNo">
			        SELECT id, order_no, amount FROM ${table} WHERE order_no = #{order_no}
			    </select>
			    <select id="rawStats">SELECT id FROM ${table}</select>

			</mapper>
			""";

	private JdbcDataSource dataSource;

	private MetaMapperClient client;

	private ConnectionSource connectionSource;

	private MetaTableRegistry registry;

	@TempDir
	Path xmlDirectory;

	@BeforeEach
	void setUp() throws Exception {
		this.dataSource = new JdbcDataSource();
		this.dataSource.setURL("jdbc:h2:mem:meta_dynamic_" + DB_SEQ.incrementAndGet()
				+ ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		this.dataSource.setUser("sa");
		try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE form_order ("
					+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
					+ "order_no VARCHAR(32) NOT NULL, "
					+ "customer_no VARCHAR(32), "
					+ "amount DECIMAL(12,2), "
					+ "status INT, "
					+ "create_time TIMESTAMP, "
					+ "deleted_flag INT NOT NULL DEFAULT 0)");
			// 跨表查询用的第二张表：只在 SQL 里 JOIN，不经过 MetaMapper 的实体读写
			statement.execute("CREATE TABLE customer ("
					+ "customer_no VARCHAR(32) PRIMARY KEY, "
					+ "customer_name VARCHAR(64), "
					+ "region VARCHAR(32))");
			statement.execute("INSERT INTO customer (customer_no, customer_name, region) VALUES "
					+ "('C1', '北京客户', 'north'), ('C2', '上海客户', 'east')");
		}
		// 预置一份「使用方手写」的 spec/XML：框架只读，默认语句在首次访问时另写到 auto/XML。
		Path file = statementFile();
		Files.createDirectories(file.getParent());
		Files.writeString(file, CUSTOM_STATEMENTS);
		this.connectionSource = new PerThreadConnectionSource(() -> new JdbcConnectionSource(this.dataSource));
		AutumnJdbc jdbc = new AutumnJdbc(this.connectionSource, new H2Dialect(), FetchPolicy.defaults());
		this.registry = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of("deleted_flag"));
		MetaSqlGenerator generator = new MetaSqlGenerator(new H2Dialect());
		MetaSqlFileStore statements = new MetaSqlFileStore(MetaDirectories.of(this.xmlDirectory.toString()),
				getClass().getClassLoader(), 10_000, generator, true);
		this.client = new MetaMapperClient("test", jdbc, this.connectionSource, this.registry,
				ENTITIES, new MetaPlanCache(), 1000, statements);
	}

	/** 手写语句放在 spec/xml；这里的池名是 test，命名空间是 default。 */
	private Path statementFile() {
		return MetaDirectories.of(this.xmlDirectory.toString())
				.specXmlDirectory("test", "default").resolve("form_order.xml");
	}

	/** 框架生成的默认语句放在 auto/xml，与 spec 完全分离。 */
	private Path autoStatementFile() {
		return MetaDirectories.of(this.xmlDirectory.toString())
				.autoXmlDirectory("test", "default").resolve("form_order.xml");
	}

	@Test
	void saveCoercesFormValuesAndBackfillsGeneratedKey() {
		MetaClass saved = this.client.save(row("form_order",
				"order_no", "A001",
				"amount", "12.50",
				"status", "1",
				"create_time", "2026-11-11 10:00:00"));

		assertThat(saved.get("id")).isInstanceOf(Number.class);
		assertThat(saved.hasChanges()).isFalse(); // 写完即干净，下一次 update 不会顺手重发这些列
		MetaClass row = this.client.findById("form_order", saved.get("id")).orElseThrow();
		assertThat(row.getString("order_no")).isEqualTo("A001");
		assertThat(row.getBigDecimal("amount")).isEqualByComparingTo("12.50");
		assertThat(row.getInt("status")).isEqualTo(1);
		assertThat(row.getLocalDateTime("create_time")).isEqualTo(LocalDateTime.of(2026, 11, 11, 10, 0));
		assertThat(row.getInt("deleted_flag")).isZero();
	}

	@Test
	void updateWritesOnlyChangedColumnsAndSupportsExplicitNull() {
		MetaClass saved = this.client.save(row("form_order",
				"order_no", "A002", "amount", "3.40", "status", 1, "create_time", "2026-11-11 11:00:00"));

		MetaClass statusChange = this.client.findById("form_order", saved.get("id")).orElseThrow();
		statusChange.set("status", 2);
		assertThat(this.client.update(statusChange)).isEqualTo(1);
		MetaClass row = this.client.findById("form_order", saved.get("id")).orElseThrow();
		assertThat(row.getInt("status")).isEqualTo(2);
		assertThat(row.getString("order_no")).isEqualTo("A002");
		assertThat(row.getBigDecimal("amount")).isEqualByComparingTo("3.40");

		// 显式写 null 才是把这一列置空；没写过的列根本不进 SET
		MetaClass nullOut = this.client.findById("form_order", saved.get("id")).orElseThrow();
		nullOut.set("amount", null);
		assertThat(this.client.update(nullOut)).isEqualTo(1);
		assertThat(this.client.findById("form_order", saved.get("id")).orElseThrow().getBigDecimal("amount")).isNull();
	}

	@Test
	void updateRejectsDirtyPrimaryKeyAndEntityWithoutChanges() {
		MetaClass saved = this.client.save(row("form_order", "order_no", "A003", "status", 1));

		MetaClass renamed = this.client.findById("form_order", saved.get("id")).orElseThrow();
		renamed.set("id", 999L);
		assertThatThrownBy(() -> this.client.update(renamed)).isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("primary key column 'id' cannot be updated");

		MetaClass untouched = this.client.findById("form_order", saved.get("id")).orElseThrow();
		assertThatThrownBy(() -> this.client.update(untouched)).isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("has no values written");
		assertThatThrownBy(() -> this.client.save(row("form_order"))).isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("has no values written");
	}

	@Test
	void findListUsesParamsAndCustomStatementsCarryComplexConditions() {
		this.client.save(row("form_order", "order_no", "B001", "amount", "5.00", "status", 1));
		this.client.save(row("form_order", "order_no", "B002", "amount", "15.00", "status", 1));
		this.client.save(row("form_order", "order_no", "B003", "amount", "25.00", "status", 2));

		// 默认 findList 只做「赋了值的列 = ?」；比较、排序这类条件写在 XML 命名语句里，参数实体负责喂值。
		List<MetaClass> rows = this.client.selectList("form_order", "highValue",
				params("status", 1, "minAmount", new BigDecimal("10")));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getString("order_no")).isEqualTo("B002");

		assertThat(this.client.findList("form_order", params("status", 1))).hasSize(2);
		List<MetaClass> limited = this.client.findPage("form_order", params(), Pageable.of(0, 2)).content();
		assertThat(limited).hasSize(2);
	}

	@Test
	void nullChecksAreExpressedInXmlStatements() {
		this.client.save(row("form_order",
				"order_no", "N001", "status", 1, "create_time", "2026-01-01 10:00:00"));
		this.client.save(row("form_order", "order_no", "N002", "status", 1));

		// IS NULL / IS NOT NULL 这类条件在 XML 里写死，Java 侧只传参数实体
		assertThat(this.client.selectList("form_order", "createdNotNull", params("status", 1)))
				.extracting(row -> row.getString("order_no")).containsExactly("N001");
		assertThat(this.client.selectList("form_order", "createdNull", params("status", 1)))
				.extracting(row -> row.getString("order_no")).containsExactly("N002");
	}

	@Test
	void findPageReturnsContentAndTotal() {
		for (int i = 1; i <= 3; i++) {
			this.client.save(row("form_order", "order_no", "P00" + i, "status", 1, "amount", i * 10));
		}

		MetaClass query = params("status", 1);
		Page<MetaClass> page = this.client.findPage("form_order", query, Pageable.of(0, 2));

		assertThat(page.content()).hasSize(2);
		assertThat(page.total()).isEqualTo(3);
		assertThat(page.totalPages()).isEqualTo(2);
		assertThat(this.client.findOne("form_order", params("order_no", "P002"))).isPresent();
	}

	@Test
	void saveAllBackfillsGeneratedKeysInOrder() {
		List<MetaClass> saved = this.client.saveAll(List.of(
				row("form_order", "order_no", "M001", "status", 1),
				row("form_order", "order_no", "M002", "status", 1),
				row("form_order", "order_no", "M003", "status", 1)));

		assertThat(saved).hasSize(3);
		assertThat(saved).allSatisfy(entity -> assertThat(entity.get("id")).isInstanceOf(Number.class));
		assertThat(saved.get(0).get("id")).isNotEqualTo(saved.get(2).get("id"));
		assertThat(this.client.findOne("form_order", row("form_order", "id", saved.get(2).get("id")))
				.orElseThrow().getString("order_no")).isEqualTo("M003");
	}

	@Test
	void saveAllFallsBackToRowByRowWhenShapesDiffer() {
		List<MetaClass> saved = this.client.saveAll(List.of(
				row("form_order", "order_no", "S001", "status", 1),
				row("form_order", "order_no", "S002", "amount", "8.00")));

		assertThat(saved).allSatisfy(entity -> assertThat(entity.get("id")).isNotNull());
		MetaClass second = this.client.findOne("form_order", params("order_no", "S002")).orElseThrow();
		assertThat(second.getBigDecimal("amount")).isEqualByComparingTo("8.00");
		assertThat(second.get("status")).isNull(); // 这条没写 status，交给数据库默认，不是被刷成 0
	}

	@Test
	void refreshSeesNewColumnWithoutRestart() throws Exception {
		MetaClass saved = this.client.save(row("form_order", "order_no", "R001", "status", 1));

		try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE form_order ADD COLUMN remark VARCHAR(64)");
		}
		this.client.refresh("form_order");

		MetaClass row = this.client.findById("form_order", saved.get("id")).orElseThrow();
		row.set("remark", "加急");
		assertThat(this.client.update(row)).isEqualTo(1);
		assertThat(this.client.findById("form_order", saved.get("id")).orElseThrow().getString("remark")).isEqualTo("加急");
	}

	@Test
	void logicDeleteColumnFiltersQueries() {
		MetaClass saved = this.client.save(row("form_order", "order_no", "L001", "status", 1));

		MetaClass voided = this.client.findById("form_order", saved.get("id")).orElseThrow();
		voided.set("deleted_flag", 1);
		assertThat(this.client.update(voided)).isEqualTo(1);

		assertThat(this.client.findById("form_order", saved.get("id"))).isEmpty();
		assertThat(this.client.findPage("form_order", params(), Pageable.of(0, 1)).total()).isZero();
	}

	@Test
	void unknownColumnInParamsIsIgnoredAndEmptyWritesAreRejected() {
		// 参数实体里的非列名字对默认语句不可见：不会被拼进 SQL，更不会造成注入。
		this.client.save(row("form_order", "order_no", "U001", "status", 1));
		assertThat(this.client.findList("form_order", params("missing_column", 1))).hasSize(1);

		MetaClass entity = this.client.newEntity("form_order");
		entity.set("order_no", "E001");
		entity.set("nick_name", "no-such-column");
		assertThatThrownBy(() -> this.client.save(entity)).isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("unknown column 'nick_name'");
	}

	@Test
	void staticFacadeUsesDefaultPoolAndBoundHandle() {
		MetaMapperOptions options = new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of("deleted_flag"),
				this.xmlDirectory.toString(), true);
		MetaMapper.install(MetaMapperEngine.forDataSource("default", this.dataSource, options));

		MetaClass entity = MetaMapper.newEntity("form_order");
		entity.set("order_no", "S001");
		entity.set("status", 1);
		MetaClass saved = MetaMapper.save(entity);
		assertThat(MetaMapper.findById("form_order", saved.get("id"))).isPresent();
		assertThat(MetaMapper.of("default").findById("form_order", saved.get("id"))
				.orElseThrow().getString("order_no")).isEqualTo("S001");
	}

	@Test
	void compositeKeyFindsAndUpdatesByKey() throws Exception {
		try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE form_link (a BIGINT NOT NULL, b BIGINT NOT NULL, label VARCHAR(32), "
					+ "PRIMARY KEY (a, b))");
		}

		assertThatThrownBy(() -> this.client.save(row("form_link", "a", 1L, "label", "missing-b")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("non-generated primary key 'b'");

		this.client.save(row("form_link", "a", 1L, "b", 2L, "label", "first"));
		MetaClass key = row("form_link", "a", 1L, "b", 2L);
		MetaClass row = this.client.findOne("form_link", key).orElseThrow();
		assertThat(row.getString("label")).isEqualTo("first");

		row.set("label", "second");
		assertThat(this.client.update(row)).isEqualTo(1);
		assertThat(this.client.findOne("form_link", key).orElseThrow().getString("label")).isEqualTo("second");
	}

	@Test
	void autoAndSpecFilesStaySeparateAndSpecIsNeverRewritten() throws Exception {
		// ① 首次访问生成 auto/xml，spec/xml 里的手写语句一个字都不动
		this.client.save(row("form_order", "order_no", "G001", "status", 1));
		assertThat(this.client.statementIds("form_order")).contains("findList", "findPage", "findOne", "findById",
				"save", "update", "delete", "selectList");
		assertThat(Files.readString(statementFile())).isEqualTo(CUSTOM_STATEMENTS);
		String generated = Files.readString(autoStatementFile());
		assertThat(generated).contains("AUTUMN-DATA GENERATED fingerprint=", "id=\"findOne\"",
				"id=\"__autumn_where\"", "id=\"__autumn_columns\"")
				.doesNotContain("id=\"selectList\"");
		assertThat(this.client.selectCount("form_order", "selectCount", params())).isEqualTo(1);

		// ② 表结构变化：auto 整份重写，spec 仍然原样
		try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE form_order ADD COLUMN remark VARCHAR(64)");
		}
		this.client.refresh("form_order");
		assertThat(this.client.statementIds("form_order")).contains("selectList", "findList");
		assertThat(Files.readString(autoStatementFile())).contains("remark").doesNotContain("id=\"selectList\"");
		assertThat(Files.readString(statementFile())).isEqualTo(CUSTOM_STATEMENTS);
	}

	@Test
	void generatedStatementsBindValuesAndKeepHoldersInSql() {
		MetaClass saved = this.client.save(row("form_order", "order_no", "N001", "status", 1));
		Object key = saved.get("id");

		MarkedSql sql = this.client.sqlFor("form_order", "selectList", params("order_no", "N' OR '1'='1"));
		assertThat(sql.sql()).doesNotContain("N' OR '1'='1").contains("= ?");
		assertThat(sql.values()).containsExactly("N' OR '1'='1");
		assertThat(this.client.selectList("form_order", "selectList", params("order_no", "N' OR '1'='1")))
				.isEmpty();

		MetaClass matched = this.client
				.selectOne("form_order", "selectList", params("status", 1, "id", key)).orElseThrow();
		assertThat(matched.getString("order_no")).isEqualTo("N001");

		this.client.execute("form_order", "updateByKey", params("id", key, "status", 2));
		assertThat(this.client.findById("form_order", key).orElseThrow().getInt("status")).isEqualTo(2);
	}

	@Test
	void executeRunsThePartialInsertFromTheMapperFile() {
		// 只给两个列：trim 把没传值的列整段剥掉，交给数据库默认值
		assertThat(this.client.execute("form_order", "insert", params("order_no", "I001", "status", 1))).isEqualTo(1);

		MetaClass row = this.client.selectOne("form_order", "selectList", params("order_no", "I001")).orElseThrow();
		assertThat(row.getInt("status")).isEqualTo(1);
		assertThat(row.getInt("deleted_flag")).isZero();
		assertThat(row.get("amount")).isNull();
	}

	@Test
	void customMapperFileGetsDefaultsInjectedAndItsStatementsStillWork() throws Exception {
		String custom = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="custom.form_order">
				    <select id="byStatus">
				        SELECT id, order_no FROM ${table}
				        <where>
				            AND deleted_flag = 0
				            <if test="status != null">AND status = #{status}</if>
				            <if test="amount != null">AND amount &gt; #{amount}</if>
				        </where>
				    </select>
				    <select id="orderNosByIds">
				        SELECT order_no FROM ${table} WHERE id IN
				        <foreach collection="ids" item="no" open="(" separator="," close=")">#{no}</foreach>
				    </select>
				    <delete id="dropById">DELETE FROM ${table} WHERE id = #{id}</delete>
				</mapper>
				""";
		Path file = statementFile();
		Files.writeString(file, custom);

		MetaClass first = this.client.save(row("form_order", "order_no", "C001", "status", 1, "amount", "5.00"));
		MetaClass second = this.client.save(row("form_order", "order_no", "C002", "status", 2, "amount", "50.00"));

		assertThat(this.client.selectList("form_order", "byStatus", params("status", 1)))
				.extracting(row -> row.getString("order_no")).containsExactly("C001");
		assertThat(this.client.selectList("form_order", "byStatus", params("amount", "10")))
				.extracting(row -> row.getString("order_no")).containsExactly("C002");

		MarkedSql in = this.client.sqlFor("form_order", "orderNosByIds",
				params("ids", List.of(first.get("id"), second.get("id"))));
		assertThat(in.sql()).contains("(?,?)");
		assertThat(in.values()).containsExactly(first.get("id"), second.get("id"));
		assertThat(this.client.selectList("form_order", "orderNosByIds",
				params("ids", List.of(first.get("id"), second.get("id"))))).hasSize(2);

		// 删不删由语句决定：文件里显式写了 <delete> 就执行物理删除，框架不再额外拦截。
		assertThat(this.client.execute("form_order", "dropById", params("id", first.get("id")))).isEqualTo(1);
		assertThat(this.client.findById("form_order", first.get("id"))).isEmpty();

		// 自定义语句只在 spec，默认语句只在 auto，两份文件互不改写
		assertThat(Files.readString(file)).doesNotContain("AUTUMN-DATA GENERATED")
				.contains("id=\"byStatus\"", "id=\"orderNosByIds\"", "id=\"dropById\"");
		assertThat(Files.readString(autoStatementFile())).contains("AUTUMN-DATA GENERATED fingerprint=", "id=\"findOne\"");
		assertThat(this.client.statementIds("form_order")).contains("findList", "findOne", "delete", "byStatus");
	}

	@Test
	void sqlFragmentsAreInlinedAndReusable() throws Exception {
		Files.writeString(statementFile(), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="fragment.form_order">
				    <sql id="allColumns">id, order_no, amount</sql>
				    <sql id="activeRows">deleted_flag = 0</sql>
				    <select id="active">
				        SELECT <include refid="allColumns"/> FROM ${table}
				        WHERE <include refid="fragment.form_order.activeRows"/>
				        <if test="status != null">AND status = #{status}</if>
				    </select>
				</mapper>
				""");
		this.client.save(row("form_order", "order_no", "F001", "status", 1, "amount", "6.00"));
		this.client.save(row("form_order", "order_no", "F002", "status", 2, "amount", "60.00"));

		assertThat(this.client.selectList("form_order", "active", params("status", 1)))
				.extracting(row -> row.getString("order_no")).containsExactly("F001");
		assertThat(this.client.selectList("form_order", "active", params())).hasSize(2);

		// 片段在解析阶段就内联：运行期 AST 与 SQL 文本里都没有 <include> 这一层
		MarkedSql sql = this.client.sqlFor("form_order", "active", params("status", 1));
		assertThat(sql.sql()).doesNotContain("include").doesNotContain("allColumns");
		assertThat(sql.sql()).contains("id, order_no, amount");
	}

	@Test
	void cyclicSqlFragmentsFailAtParseTime() throws Exception {
		Files.writeString(statementFile(), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="cycle.form_order">
				    <sql id="a">SELECT 1 <include refid="b"/></sql>
				    <sql id="b"><include refid="a"/></sql>
				    <select id="loop">SELECT 1</select>
				    <select id="usesCycle">SELECT <include refid="a"/> FROM ${table}</select>
				</mapper>
				""");

		assertThatThrownBy(() -> this.client.selectList("form_order", "usesCycle", params()))
				.hasMessageContaining("cyclic <sql> include")
				.hasMessageContaining("a -> b -> a");
		// 缺片段同样在解析期报错，并把可用片段列出来
		Files.writeString(statementFile(), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="missing.form_order">
				    <select id="broken">SELECT <include refid="nope"/> FROM ${table}</select>
				</mapper>
				""");
		this.client.refresh("form_order");
		assertThatThrownBy(() -> this.client.selectList("form_order", "broken", params()))
				.hasMessageContaining("has no matching <sql id=\"nope\">");
	}

	/**
	 * 映射就是「结果集列名 → MetaClass 属性名」：SELECT 起别名之后，生成实体的固定列 READER
	 * 读不到原名，必须按 resultMap 逐列取值；property 写列名或驼峰字段名都行。
	 */
	@Test
	void resultMapMapsAliasedColumnsToProperties() throws Exception {
		Files.writeString(statementFile(), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="mapping.form_order">
				    <resultMap id="orderView" type="form_order">
				        <id column="id" property="id"/>
				        <result column="no" property="order_no"/>
				        <result column="total" property="amount"/>
				        <result column="order_time" property="createTime"/>
				    </resultMap>
				    <select id="aliased" resultMap="orderView">
				        SELECT id, order_no AS no, amount AS total, create_time AS order_time
				        FROM ${table} WHERE deleted_flag = 0
				    </select>
				</mapper>
				""");
		MetaClass saved = this.client.save(row("form_order", "order_no", "AM01", "amount", "18.00",
				"status", 1, "create_time", "2026-11-12 09:30:00"));

		MetaClass view = this.client.selectOne("form_order", "aliased", params()).orElseThrow();
		assertThat(view.get("id")).isEqualTo(saved.get("id"));
		assertThat(view.getString("order_no")).isEqualTo("AM01");
		assertThat(view.getBigDecimal("amount")).isEqualByComparingTo("18.00");
		assertThat(view.getLocalDateTime("create_time")).isEqualTo(LocalDateTime.of(2026, 11, 12, 9, 30));
		// 读出来的行是干净的：后续 update 不会把映射过来的列误当成用户改动
		assertThat(view.hasChanges()).isFalse();

		// 映射不到的表列（比如 status）保持 null，不会被别的列串值
		assertThat(view.get("status")).isNull();
	}

	@Test
	void unknownResultMapFailsAtParseTime() throws Exception {
		Files.writeString(statementFile(), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="badmap.form_order">
				    <select id="broken" resultMap="noSuchMap">SELECT * FROM ${table}</select>
				</mapper>
				""");
		assertThatThrownBy(() -> this.client.selectList("form_order", "broken", params()))
				.hasMessageContaining("unknown <resultMap id=\"noSuchMap\">");
	}

	@Test
	void statementIdentifiersMatchColumnsRegardlessOfCase() throws Exception {
		// 元数据列名是小写（H2 DATABASE_TO_LOWER），手写文件常按 MySQL/Oracle 习惯写大写：两边必须对上，
		// 否则 #{ORDER_NO} 会静默绑成 null
		Files.writeString(statementFile(), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="upper.form_order">
				    <select id="byStatus">
				        SELECT ORDER_NO FROM ${table}
				        <where>
				            <if test="STATUS != null">AND STATUS = #{STATUS}</if>
				        </where>
				    </select>
				</mapper>
				""");
		this.client.save(row("form_order", "order_no", "U001", "status", 1));
		this.client.save(row("form_order", "order_no", "U002", "status", 2));

		assertThat(this.client.selectList("form_order", "byStatus", params("status", 1)))
				.extracting(row -> row.getString("order_no")).containsExactly("U001");
		// 调用方传大写键也一样：参数键先按元数据归一，再大小写不敏感地查表
		assertThat(this.client.selectList("form_order", "byStatus", params("STATUS", 2)))
				.extracting(row -> row.getString("order_no")).containsExactly("U002");
		assertThat(this.client.sqlFor("form_order", "byStatus", params()).sql()).doesNotContain("WHERE");
	}

	@Test
	void dollarInterpolationRejectsNonIdentifierValues() throws Exception {
		Files.writeString(statementFile(), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="custom.form_order">
				    <select id="rawNo">SELECT order_no FROM ${table} WHERE order_no = ${order_no}</select>
				</mapper>
				""");

		assertThatThrownBy(() -> this.client
				.sqlFor("form_order", "rawNo", params("order_no", "A001' OR 1=1 --")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");

		// ${} 走拼接：过白名单的标识符照拼，这是写语句的人显式承担的风险面
		assertThat(this.client.sqlFor("form_order", "rawNo", params("order_no", "A001")).sql())
				.contains("WHERE order_no = A001");
	}

	@Test
	void missingStatementReportsTheAvailableOnes() {
		assertThatThrownBy(() -> this.client.sqlFor("form_order", "noSuchStatement", params()))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("noSuchStatement")
				.hasMessageContaining("selectList");
	}

	/** 命名语句的参数不止列名：业务开关、排序列名、循环集合都活在实体的动态属性上。 */
	@Test
	void nonColumnParametersLiveOnTheEntityAndReachTheStatement() throws Exception {
		Files.writeString(statementFile(), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="dynamic.form_order">
				    <select id="ordered">
				        SELECT order_no FROM ${table} WHERE deleted_flag = 0 ORDER BY ${orderBy}
				    </select>
				</mapper>
				""");
		this.client.save(row("form_order", "order_no", "D002", "status", 2));
		this.client.save(row("form_order", "order_no", "D001", "status", 1));

		MetaClass args = params("orderBy", "order_no", "operator", "ASC");
		assertThat(args.getProperties()).extracting(MetaProperty::getName)
				.containsExactly("orderBy", "operator"); // 列以外的名字一个都没被生成类丢掉
		assertThat(this.client.selectList("form_order", "ordered", args))
				.extracting(item -> item.getString("order_no")).containsExactly("D001", "D002");
	}

	@Test
	void projectionQuerySkipsMissingColumnsInsteadOfFailing() throws Exception {
		Files.writeString(statementFile(), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="projection.form_order">
				    <select id="onlyOrderNo">SELECT order_no FROM ${table} WHERE deleted_flag = 0</select>
				</mapper>
				""");
		this.client.save(row("form_order", "order_no", "PRJ-1", "status", 1));

		MetaClass row = this.client.selectOne("form_order", "onlyOrderNo", params()).orElseThrow();
		assertThat(row.getString("order_no")).isEqualTo("PRJ-1");
		// 未投影的列是 null，而不是 SQLException / 错位取值
		assertThat(row.get("amount")).isNull();
		assertThat(row.get("id")).isNull();
	}

	@Test
	void tableCacheEvictsColdTablesAndReloadsOnDemand() throws Exception {
		createTable("cache_a");
		createTable("cache_b");
		createTable("cache_c");
		MetaTableRegistry bounded = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of(),
				MetaModelStore.none(), 2);

		assertThat(bounded.require("cache_a").name()).isEqualTo("cache_a");
		assertThat(bounded.require("cache_b").name()).isEqualTo("cache_b");
		assertThat(bounded.require("cache_a").name()).isEqualTo("cache_a"); // a 变成最近使用
		assertThat(bounded.require("cache_c").name()).isEqualTo("cache_c");

		assertThat(bounded.maximumSize()).isEqualTo(2);
		assertThat(bounded.size()).isEqualTo(2);
		// 淘汰只是丢快照：冷表再次访问会重读 DatabaseMetaData，结果不变
		assertThat(bounded.require("cache_b").columns()).hasSize(2);
		assertThat(bounded.size()).isEqualTo(2);
	}

	@Test
	void manualRegistrationsAreBoundedAndFallBackToLazyLoad() throws Exception {
		createTable("manual_table");
		MetaTableRegistry bounded = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of(),
				MetaModelStore.none(), 1);
		MetaTable manual = manualTable("manual_table");
		bounded.register(manual);

		assertThat(bounded.require("manual_table")).isSameAs(manual);
		assertThat(bounded.size()).isEqualTo(1);

		// 覆盖层同样有界：再登记一张表，上一张的覆盖被 LRU 淘汰
		bounded.register(manualTable("manual_other"));
		MetaTable reloaded = bounded.require("manual_table");
		assertThat(reloaded).isNotSameAs(manual);
		assertThat(reloaded.columns()).hasSize(2); // 淘汰后像普通表一样懒加载回源到 DatabaseMetaData
		assertThat(bounded.size()).isLessThanOrEqualTo(2);

		// refresh 仍按原语义同时清掉快照与覆盖，之后仍可按需重建
		bounded.refresh("manual_table");
		assertThat(bounded.require("manual_table").columns()).hasSize(2);
		bounded.refreshAll();
		assertThat(bounded.size()).isZero();
	}

	@Test
	void poolClientsAreBoundedAndRebuiltOnDemand() {
		AtomicInteger created = new AtomicInteger();
		MetaMapperEngine engine = new MetaMapperEngine("default", pool -> {
			created.incrementAndGet();
			return this.client;
		}, ENTITIES, 1);

		engine.client("tenant-a");
		assertThat(engine.clients()).containsKey("tenant-a");
		engine.client("tenant-b"); // 触顶淘汰 tenant-a
		assertThat(engine.clients()).hasSize(1).containsKey("tenant-b");

		engine.client("tenant-a"); // 冷池再次访问 → 懒重建
		assertThat(created.get()).isEqualTo(3);
		assertThat(engine.clients()).hasSize(1).containsKey("tenant-a");
		assertThatThrownBy(() -> engine.clients().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	private static long tempEntityDirs(Path tmp) throws Exception {
		try (var stream = Files.list(tmp)) {
			return stream.filter(path -> path.getFileName().toString().startsWith("autumn-meta-entity-"))
					.count();
		}
	}

	private static MetaTable manualTable(String name) {
		MetaProperty id = new MetaProperty();
		id.setName("id");
		id.setType(Long.class.getName());
		id.setJdbcType(Types.BIGINT);
		id.setPrimaryKey(true);
		MetaClass meta = new MetaClass();
		meta.setName(name);
		meta.setProperties(List.of(id));
		return new MetaTable(meta, null);
	}

	@Test
	void entityAndStatementCachesAreBoundedToo() throws Exception {
		createTable("cache_x");
		createTable("cache_y");
		MetaTableRegistry tables = new MetaTableRegistry(this.dataSource, new H2Dialect());
		MetaTable x = tables.require("cache_x");
		MetaTable y = tables.require("cache_y");

		MetaEntityRegistry entities = new MetaEntityRegistry(1);
		MetaEntityModel first = entities.model(x);
		assertThat(entities.size()).isEqualTo(1);
		entities.model(y);
		assertThat(entities.size()).isEqualTo(1); // 有界 LRU：y 把 x 挤掉
		// 淘汰后重新构建：实体类型恒为 MetaClass，模型按当前表结构重建
		MetaEntityModel reloaded = entities.model(x);
		assertThat(entities.size()).isEqualTo(1);
		assertThat(reloaded.fingerprint()).isEqualTo(first.fingerprint());
		assertThat(reloaded.factory().get().getName()).isEqualTo(x.name());
		assertThat(entities.entityType(x)).isSameAs(MetaClass.class);

		MetaSqlFileStore statements = new MetaSqlFileStore(null, getClass().getClassLoader(), 1,
				new MetaSqlGenerator(new H2Dialect()), false);
		assertThat(statements.statements("test", "default", x)).containsKey("findList");
		statements.statements("test", "default", y);
		assertThat(statements.size()).isEqualTo(1);
		// 纯内存模式：淘汰后按结构重新生成默认语句，不落盘、不重写任何文件
		assertThat(statements.statements("test", "default", x)).containsKey("findList");
		assertThat(statements.size()).isEqualTo(1);
	}

	@Test
	void tableCacheSizeDefaultsToTenThousandAndIsConfigurable() {
		assertThat(MetaMapperOptions.defaults().tableCacheSize()).isEqualTo(10_000);
		assertThat(this.registry.maximumSize()).isEqualTo(10_000);
		assertThat(new MetaEntityRegistry().maximumSize()).isEqualTo(10_000);
		assertThat(new MetaSqlFileStore(null).maximumSize()).isEqualTo(10_000);
		assertThat(new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", true, 7)
				.tableCacheSize()).isEqualTo(7);
		assertThat(MetaMapperOptions.defaults().statementCacheSize()).isEqualTo(1000);
		assertThat(new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", true, 7)
				.statementCacheSize()).isEqualTo(1000);
		assertThat(new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", true, 7, 5).statementCacheSize())
				.isEqualTo(5);
		assertThatThrownBy(() -> new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", true, 0))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tableCacheSize");
		assertThatThrownBy(() -> new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", true, 7, 0))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("statementCacheSize");
	}

	// ------------------------------------------------------------------
	// 运行期生成实体的边界：列名撞基类契约、并发单飞、反复改表、特殊标识符
	// ------------------------------------------------------------------

	/**
	 * 表单表里 name / namespace / properties 这类列非常常见。生成类的 getter 不能意外覆写
	 * {@link MetaClass} 的同名方法：否则要么 javac 直接编译失败（返回类型不兼容），
	 * 要么 setName(TABLE_NAME) 被生成类劫持、把表名写进业务列，getName() 也不再返回表名。
	 */
	@Test
	void namedStatementsBindValuesAndHonourStatementTypes() {
		this.client.save(row("form_order", "order_no", "RS-1", "amount", "88.00", "status", 1));
		this.client.save(row("form_order", "order_no", "RS-2", "amount", "12.00", "status", 2));
		MetaClass query = params("status", 1, "minAmount", new BigDecimal("50.00"));

		MarkedSql marked = this.client.sqlFor("form_order", "highValue", query);
		assertThat(marked.sql()).contains("\"form_order\"").doesNotContain("88.00").doesNotContain("50.00");
		assertThat(marked.values()).containsExactly(1, new BigDecimal("50.00"));

		List<MetaClass> rows = this.client.selectPage("form_order", "highValue", query, Pageable.of(0, 10)).content();
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getString("order_no")).isEqualTo("RS-1");
		assertThat(rows.get(0).getBigDecimal("amount")).isEqualByComparingTo("88.00");

		// 语句类型由 XML 标签决定：<select> 不能用 execute，<delete> 不能用 selectList
		assertThatThrownBy(() -> this.client.execute("form_order", "highValue", query))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("is a <select>");
		assertThatThrownBy(() -> this.client.selectList("form_order", "deleteRow", params("id", -1)))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("is not a <select>");
	}

	@Test
	void selectPageUsesTheStatementForContentAndDerivedCount() {
		for (int i = 1; i <= 3; i++) {
			this.client.save(row("form_order", "order_no", "RP-" + i, "amount", String.valueOf(i * 10),
					"status", 1));
		}
		Page<MetaClass> first = this.client.selectPage("form_order", "highValue",
				params("status", 1, "minAmount", "0"), Pageable.of(0, 2));
		assertThat(first.total()).isEqualTo(3);
		assertThat(first.content()).hasSize(2);
		assertThat(first.totalPages()).isEqualTo(2);

		Page<MetaClass> second = this.client.selectPage("form_order", "highValue",
				params("status", 1, "minAmount", "0"), Pageable.of(1, 2));
		assertThat(second.total()).isEqualTo(3);
		assertThat(second.content()).hasSize(1);
	}

	@Test
	void namedStatementInsertUpdateCountAndFindOne() {
		int inserted = this.client.execute("form_order", "insertRaw",
				params("order_no", "RW-1", "amount", "7.70", "status", 9));
		assertThat(inserted).isEqualTo(1);

		long counted = this.client.selectCount("form_order", "countStatus", params("status", 9));
		assertThat(counted).isEqualTo(1);

		int updated = this.client.execute("form_order", "updateAmount",
				params("amount", "9.90", "order_no", "RW-1"));
		assertThat(updated).isEqualTo(1);

		MetaClass row = this.client.selectOne("form_order", "byOrderNo", params("order_no", "RW-1")).orElseThrow();
		assertThat(row.getBigDecimal("amount")).isEqualByComparingTo("9.90");
		assertThat(this.client.selectOne("form_order", "byOrderNo", params("order_no", "NOPE"))).isEmpty();
	}

	@Test
	void customStatementOverridesGeneratedOneAndSurvivesRefresh() throws Exception {
		this.client.save(row("form_order", "order_no", "RG-1", "amount", "30.00", "status", 1));
		this.client.save(row("form_order", "order_no", "RG-2", "amount", "60.00", "status", 1));

		// 同 id 的自定义语句写进 spec 文件，spec 优先于 auto：findOne 改按金额倒序取第一条
		String file = Files.readString(statementFile());
		Files.writeString(statementFile(), file.replace("</mapper>", """
				    <select id="findOne">
				        SELECT id, order_no, amount, status FROM ${table}
				        WHERE status = #{status} ORDER BY amount DESC
				    </select>
				</mapper>"""));
		this.client.clearStatementCache("form_order");

		MarkedSql preview = this.client.sqlFor("form_order", "findOne", params("status", 1));
		assertThat(preview.sql()).contains("ORDER BY amount DESC").doesNotContain("30.00");
		assertThat(this.client.findOne("form_order", params("status", 1)).orElseThrow().getString("order_no"))
				.isEqualTo("RG-2");

		// 表结构变化刷新后，auto 重写、spec 里的覆盖语义不变
		try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE form_order ADD COLUMN remark VARCHAR(64)");
		}
		this.client.refresh("form_order");
		assertThat(this.client.findOne("form_order", params("status", 1)).orElseThrow().getString("order_no"))
				.isEqualTo("RG-2");
		assertThat(Files.readString(statementFile())).contains("ORDER BY amount DESC");
		assertThat(Files.readString(autoStatementFile())).doesNotContain("ORDER BY amount DESC");
	}

	@Test
	void tableAndEntityCachesClearIndependently() {
		MetaClass original = this.client.newEntity("form_order");
		long modelsBefore = ((Number) this.client.cacheStats().get("entityModels")).longValue();

		this.client.clearTableCache("form_order");
		MetaClass afterTable = this.client.newEntity("form_order");
		assertThat(afterTable.getClass()).isSameAs(original.getClass()); // 统一 MetaClass，没有生成类可换
		assertThat(((Number) this.client.cacheStats().get("entityModels")).longValue())
				.isEqualTo(modelsBefore); // 只清结构快照，不动实体模型缓存

		this.client.clearEntityCache("form_order");
		assertThat(((Number) this.client.cacheStats().get("entityModels")).longValue()).isLessThan(modelsBefore);
		MetaClass afterEntity = this.client.newEntity("form_order");
		assertThat(afterEntity.getClass()).isSameAs(MetaClass.class);
		assertThat(afterEntity.getProperties().stream().map(MetaProperty::getName).toList())
				.contains("order_no", "amount");

		this.client.clearStatementCache("form_order");
		assertThat(this.client.entityType("form_order")).isSameAs(MetaClass.class);

		MetaClass saved = this.client.save(row("form_order", "order_no", "CC-1", "status", 1));
		assertThat(saved.getClass()).isSameAs(MetaClass.class);
	}

	@Test
	void reloadRebuildsImmediatelyAndFailsForDroppedTable() throws Exception {
		MetaClass rebuilt = this.client.reload("form_order");
		assertThat(rebuilt.getClass()).isSameAs(MetaClass.class);
		assertThat(rebuilt.getProperties().stream().map(MetaProperty::getName).toList())
				.contains("order_no", "amount");
		assertThat(this.client.entityType("form_order")).isSameAs(MetaClass.class);

		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE form_order");
		}
		assertThatThrownBy(() -> this.client.reload("form_order"))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("table not found");
	}

	@Test
	void cacheStatsReportsEveryBoundedLayer() {
		this.client.entityType("form_order");
		this.client.ensureStatementFile("form_order");
		Map<String, Object> stats = this.client.cacheStats();
		assertThat(stats).containsEntry("pool", "test")
				.containsKeys("tables", "entityModels", "statementTables", "specTables", "compiledShapes",
						"tableLimit", "entityLimit", "statementLimit", "planLimit");
		assertThat(((Number) stats.get("tables")).longValue()).isGreaterThanOrEqualTo(1);
		assertThat(((Number) stats.get("entityModels")).longValue()).isGreaterThanOrEqualTo(1);
		assertThat(((Number) stats.get("statementTables")).longValue()).isGreaterThanOrEqualTo(1);
	}

	private static MetaTable tableWithColumns(String name, String... columnNames) {
		List<MetaProperty> columns = new ArrayList<>();
		for (String column : columnNames) {
			MetaProperty property = new MetaProperty();
			property.setName(column);
			property.setType(String.class.getName());
			property.setJdbcType(Types.VARCHAR);
			property.setNullable(true);
			columns.add(property);
		}
		MetaClass meta = new MetaClass();
		meta.setName(name);
		meta.setProperties(columns);
		return new MetaTable(meta, null);
	}

	private void createTable(String name) throws Exception {
		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE " + name + " (id BIGINT AUTO_INCREMENT PRIMARY KEY, label VARCHAR(32))");
		}
	}

	/** 待写入的实体：形参是「列名, 值, 列名, 值…」，写过的列才是 INSERT/UPDATE 的范围。 */
	private MetaClass row(String table, Object... nameValues) {
		return fill(this.client, table, nameValues);
	}

	private static MetaClass fill(MetaMapperClient client, String table, Object... nameValues) {
		MetaClass entity = client.newEntity(table);
		for (int i = 0; i < nameValues.length; i += 2) {
			entity.set((String) nameValues[i], nameValues[i + 1]);
		}
		return entity;
	}

	/** 命名语句的参数对象：列以外的开关、集合和循环变量落在实体的动态属性上。 */
	private static MetaClass params(Object... nameValues) {
		MetaClass entity = new MetaClass();
		for (int i = 0; i < nameValues.length; i += 2) {
			entity.set((String) nameValues[i], nameValues[i + 1]);
		}
		return entity;
	}
}
