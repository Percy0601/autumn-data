package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.SqlBuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动态表 CRUD 的 H2 集成测试：建表 → 不重启直接读写 → 加列后 refresh 继续可用。
 */
class MetaMapperDynamicTest {

	private static final AtomicInteger DB_SEQ = new AtomicInteger();

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
					+ "amount DECIMAL(12,2), "
					+ "status INT, "
					+ "create_time TIMESTAMP, "
					+ "deleted_flag INT NOT NULL DEFAULT 0)");
		}
		this.connectionSource = new PerThreadConnectionSource(() -> new JdbcConnectionSource(this.dataSource));
		AutumnJdbc jdbc = new AutumnJdbc(this.connectionSource, new H2Dialect(), FetchPolicy.defaults());
		this.registry = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of("deleted_flag"));
		MetaStatementStore statementStore = new MetaStatementStore(this.xmlDirectory.toString(), true,
				new H2Dialect());
		this.client = new MetaMapperClient("test", jdbc, this.connectionSource, this.registry,
				new MetaSqlBuilder(new H2Dialect()), statementStore, 1000);
	}

	@Test
	void saveCoercesFormValuesAndReturnsGeneratedKey() {
		Object key = this.client.save("form_order", Map.of(
				"order_no", "A001",
				"amount", "12.50",
				"status", "1",
				"create_time", "2026-11-11 10:00:00"));

		assertThat(key).isInstanceOf(Number.class);
		MetaRow row = this.client.findById("form_order", key).orElseThrow();
		assertThat(row.getString("order_no")).isEqualTo("A001");
		assertThat(row.getBigDecimal("amount")).isEqualByComparingTo("12.50");
		assertThat(row.getInt("status")).isEqualTo(1);
		assertThat(row.getLocalDateTime("create_time")).isEqualTo(LocalDateTime.of(2026, 11, 11, 10, 0));
		assertThat(row.getInt("deleted_flag")).isZero();
	}

	@Test
	void updateByIdIsPartialAndSupportsExplicitNull() {
		Object key = this.client.save("form_order", Map.of(
				"order_no", "A002", "amount", "3.40", "status", 1, "create_time", "2026-11-11 11:00:00"));

		assertThat(this.client.updateById("form_order", key, Map.of("status", 2))).isEqualTo(1);
		MetaRow row = this.client.findById("form_order", key).orElseThrow();
		assertThat(row.getInt("status")).isEqualTo(2);
		assertThat(row.getString("order_no")).isEqualTo("A002");
		assertThat(row.getBigDecimal("amount")).isEqualByComparingTo("3.40");

		assertThat(this.client.updateById("form_order", key, Map.of("amount", MetaValue.NULL))).isEqualTo(1);
		assertThat(this.client.findById("form_order", key).orElseThrow().getBigDecimal("amount")).isNull();
	}

	@Test
	void findListAppliesConditionsOrderAndLimit() {
		this.client.save("form_order", Map.of("order_no", "B001", "amount", "5.00", "status", 1));
		this.client.save("form_order", Map.of("order_no", "B002", "amount", "15.00", "status", 1));
		this.client.save("form_order", Map.of("order_no", "B003", "amount", "25.00", "status", 2));

		List<MetaRow> rows = this.client.findList("form_order",
				MetaQuery.by("status", 1).and("amount", MetaQuery.Op.GE, "10")
						.orderBy("id", MetaQuery.Direction.DESC));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getString("order_no")).isEqualTo("B002");

		List<MetaRow> limited = this.client.findList("form_order", MetaQuery.all(), 2);
		assertThat(limited).hasSize(2);
	}

	@Test
	void findPageReturnsContentAndTotal() {
		for (int i = 1; i <= 3; i++) {
			this.client.save("form_order", Map.of("order_no", "P00" + i, "status", 1, "amount", i * 10));
		}

		Page<MetaRow> page = this.client.findPage("form_order", MetaQuery.by("status", 1), Pageable.of(0, 2));

		assertThat(page.content()).hasSize(2);
		assertThat(page.total()).isEqualTo(3);
		assertThat(page.totalPages()).isEqualTo(2);
		assertThat(this.client.count("form_order", MetaQuery.by("status", 1))).isEqualTo(3);
		assertThat(this.client.exists("form_order", MetaQuery.by("order_no", "P002"))).isTrue();
	}

	@Test
	void saveAllReturnsGeneratedKeysInOrder() {
		List<Object> keys = this.client.saveAll("form_order", List.of(
				Map.of("order_no", "M001", "status", 1),
				Map.of("order_no", "M002", "status", 1),
				Map.of("order_no", "M003", "status", 1)));

		assertThat(keys).hasSize(3);
		assertThat(keys).allSatisfy(key -> assertThat(key).isInstanceOf(Number.class));
		assertThat(this.client.findByKey("form_order", Map.of("id", keys.get(2)))
				.orElseThrow().getString("order_no")).isEqualTo("M003");
	}

	@Test
	void refreshSeesNewColumnWithoutRestart() throws Exception {
		Object key = this.client.save("form_order", Map.of("order_no", "R001", "status", 1));

		try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE form_order ADD COLUMN remark VARCHAR(64)");
		}
		this.client.refresh("form_order");

		assertThat(this.client.updateById("form_order", key, Map.of("remark", "加急"))).isEqualTo(1);
		assertThat(this.client.findById("form_order", key).orElseThrow().getString("remark")).isEqualTo("加急");
	}

	@Test
	void logicDeleteColumnFiltersQueries() {
		Object key = this.client.save("form_order", Map.of("order_no", "L001", "status", 1));

		assertThat(this.client.updateById("form_order", key, Map.of("deleted_flag", 1))).isEqualTo(1);

		assertThat(this.client.findById("form_order", key)).isEmpty();
		assertThat(this.client.count("form_order", MetaQuery.all())).isZero();
	}

	@Test
	void unknownColumnAndEmptyUpdateAreRejected() {
		assertThatThrownBy(() -> this.client.findList("form_order", MetaQuery.by("missing_column", 1)))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("missing_column");

		Object key = this.client.save("form_order", Map.of("order_no", "E001", "status", 1));
		assertThatThrownBy(() -> this.client.updateById("form_order", key, Map.of()))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("must not be empty");
	}

	@Test
	void staticFacadeUsesDefaultPoolAndBoundHandle() {
		MetaMapperOptions options = new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of("deleted_flag"),
				this.xmlDirectory.toString(), true);
		MetaMapper.install(MetaMapperEngine.forDataSource("default", this.dataSource, options));

		Object key = MetaMapper.save("form_order", Map.of("order_no", "S001", "status", 1));
		assertThat(MetaMapper.findById("form_order", key)).isPresent();
		assertThat(MetaMapper.of("default").findById("form_order", key).orElseThrow().getString("order_no"))
				.isEqualTo("S001");
	}

	@Test
	void compositeKeyFindsAndUpdatesByKey() throws Exception {
		try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE form_link (a BIGINT NOT NULL, b BIGINT NOT NULL, label VARCHAR(32), "
					+ "PRIMARY KEY (a, b))");
		}

		assertThatThrownBy(() -> this.client.save("form_link", Map.of("a", 1, "label", "missing-b")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("non-generated primary key 'b'");

		this.client.save("form_link", Map.of("a", 1, "b", 2, "label", "first"));
		MetaRow row = this.client.findByKey("form_link", Map.of("a", 1, "b", 2)).orElseThrow();
		assertThat(row.getString("label")).isEqualTo("first");

		assertThat(this.client.updateByKey("form_link", Map.of("a", 1, "b", 2),
				Map.of("label", "second"))).isEqualTo(1);
		assertThat(this.client.findByKey("form_link", Map.of("a", 1, "b", 2))
				.orElseThrow().getString("label")).isEqualTo("second");
	}

	@Test
	void namedStatementsGenerateTheMapperFileOnce() throws Exception {
		Path file = this.xmlDirectory.resolve("form_order.xml");
		this.client.save("form_order", Map.of("order_no", "G001", "status", 1));
		assertThat(file).doesNotExist();

		assertThat(this.client.selectCount("form_order", "selectCount", Map.of())).isEqualTo(1);

		String content = Files.readString(file);
		assertThat(content).contains("generated-by: autumn-data-meta-jdbc");
		assertThat(content).contains("id=\"selectList\"").contains("id=\"selectCount\"");
		assertThat(content).contains("id=\"insert\"").contains("id=\"updateByKey\"");
		assertThat(content).contains("#{status}").contains("<where>").contains("<trim");

		// 文件已经在了：改表 + refresh 只重读，不重写
		try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE form_order ADD COLUMN remark VARCHAR(64)");
		}
		this.client.refresh("form_order");

		assertThat(this.client.selectList("form_order", "selectList", Map.of("order_no", "G001")))
				.extracting(row -> row.getString("order_no")).containsExactly("G001");
		assertThat(Files.readString(file)).isEqualTo(content);
	}

	@Test
	void generatedStatementsBindValuesAndKeepHoldersInSql() {
		Object key = this.client.save("form_order", Map.of("order_no", "N001", "status", 1));

		SqlBuild sql = this.client.sqlFor("form_order", "selectList", Map.of("order_no", "N' OR '1'='1"));
		assertThat(sql.sql()).doesNotContain("N' OR '1'='1").contains("= ?");
		assertThat(sql.args()).containsExactly("N' OR '1'='1");
		assertThat(this.client.selectList("form_order", "selectList", Map.of("order_no", "N' OR '1'='1")))
				.isEmpty();

		MetaRow matched = this.client.selectOne("form_order", "selectList", Map.of("status", 1, "id", key))
				.orElseThrow();
		assertThat(matched.getString("order_no")).isEqualTo("N001");

		this.client.execute("form_order", "updateByKey", Map.of("id", key, "status", 2));
		assertThat(this.client.findById("form_order", key).orElseThrow().getInt("status")).isEqualTo(2);
	}

	@Test
	void executeRunsThePartialInsertFromTheMapperFile() {
		// 只给两个列：trim 把没传值的列整段剥掉，交给数据库默认值
		assertThat(this.client.execute("form_order", "insert", Map.of("order_no", "I001", "status", 1))).isEqualTo(1);

		MetaRow row = this.client.selectOne("form_order", "selectList", Map.of("order_no", "I001")).orElseThrow();
		assertThat(row.getInt("status")).isEqualTo(1);
		assertThat(row.getInt("deleted_flag")).isZero();
		assertThat(row.get("amount")).isNull();
	}

	@Test
	void customMapperFileWinsAndIsNeverRewritten() throws Exception {
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
		Path file = this.xmlDirectory.resolve("form_order.xml");
		Files.writeString(file, custom);

		Object first = this.client.save("form_order", Map.of("order_no", "C001", "status", 1, "amount", "5.00"));
		Object second = this.client.save("form_order", Map.of("order_no", "C002", "status", 2, "amount", "50.00"));

		assertThat(this.client.selectList("form_order", "byStatus", Map.of("status", 1)))
				.extracting(row -> row.getString("order_no")).containsExactly("C001");
		assertThat(this.client.selectList("form_order", "byStatus", Map.of("amount", "10")))
				.extracting(row -> row.getString("order_no")).containsExactly("C002");

		SqlBuild in = this.client.sqlFor("form_order", "orderNosByIds", Map.of("ids", List.of(first, second)));
		assertThat(in.sql()).contains("(?,?)");
		assertThat(in.args()).containsExactly(first, second);
		assertThat(this.client.selectList("form_order", "orderNosByIds", Map.of("ids", List.of(first, second))))
				.hasSize(2);

		assertThatThrownBy(() -> this.client.execute("form_order", "dropById", Map.of("id", first)))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("does not run physical deletes");
		assertThat(this.client.findById("form_order", first)).isPresent();

		assertThat(Files.readString(file)).isEqualTo(custom);
	}

	@Test
	void dollarInterpolationRejectsNonIdentifierValues() throws Exception {
		Files.writeString(this.xmlDirectory.resolve("form_order.xml"), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="custom.form_order">
				    <select id="rawNo">SELECT order_no FROM ${table} WHERE order_no = ${order_no}</select>
				</mapper>
				""");

		assertThatThrownBy(() -> this.client.sqlFor("form_order", "rawNo", Map.of("order_no", "A001' OR 1=1 --")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");

		// ${} 走拼接：过白名单的标识符照拼，这是写语句的人显式承担的风险面
		assertThat(this.client.sqlFor("form_order", "rawNo", Map.of("order_no", "A001")).sql())
				.contains("WHERE order_no = A001");
	}

	@Test
	void missingStatementReportsTheAvailableOnes() {
		assertThatThrownBy(() -> this.client.sqlFor("form_order", "noSuchStatement", Map.of()))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("noSuchStatement")
				.hasMessageContaining("selectList");
	}
}
