package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Types;
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
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
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

	/** 同构的表只编译一次：所有用例共享注册表，避免每个用例都跑一遍 javac。 */
	private static final MetaEntityRegistry ENTITIES = new MetaEntityRegistry();

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
				new MetaSqlBuilder(new H2Dialect()), statementStore, ENTITIES, 1000);
	}

	@Test
	void saveCoercesFormValuesAndReturnsGeneratedKey() {
		Object key = this.client.save("form_order", Map.of(
				"order_no", "A001",
				"amount", "12.50",
				"status", "1",
				"create_time", "2026-11-11 10:00:00"));

		assertThat(key).isInstanceOf(Number.class);
		MetaClass row = this.client.findById("form_order", key).orElseThrow();
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
		MetaClass row = this.client.findById("form_order", key).orElseThrow();
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

		List<MetaClass> rows = this.client.findList("form_order",
				MetaQuery.by("status", 1).and("amount", MetaQuery.Op.GE, "10")
						.orderBy("id", MetaQuery.Direction.DESC));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getString("order_no")).isEqualTo("B002");

		List<MetaClass> limited = this.client.findList("form_order", MetaQuery.all(), 2);
		assertThat(limited).hasSize(2);
	}

	@Test
	void nullChecksUseTheirOwnBuilders() {
		this.client.save("form_order",
				Map.of("order_no", "N001", "status", 1, "create_time", "2026-01-01 10:00:00"));
		this.client.save("form_order", Map.of("order_no", "N002", "status", 1));

		assertThat(this.client.findList("form_order", MetaQuery.by("status", 1).isNotNull("create_time")))
				.extracting(row -> row.getString("order_no")).containsExactly("N001");
		assertThat(this.client.findList("form_order", MetaQuery.by("status", 1).isNull("create_time")))
				.extracting(row -> row.getString("order_no")).containsExactly("N002");

		// 把 Op 当条件值传会选中 and(String, Object) 重载：拒绝掉，而不是把枚举绑进 SQL
		assertThatThrownBy(() -> MetaQuery.by("status", MetaQuery.Op.IS_NULL))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("isNull");
		assertThatThrownBy(() -> MetaQuery.all().and("status", MetaQuery.Op.IS_NOT_NULL))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("isNotNull");
	}

	@Test
	void findPageReturnsContentAndTotal() {
		for (int i = 1; i <= 3; i++) {
			this.client.save("form_order", Map.of("order_no", "P00" + i, "status", 1, "amount", i * 10));
		}

		Page<MetaClass> page = this.client.findPage("form_order", MetaQuery.by("status", 1), Pageable.of(0, 2));

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
		MetaClass row = this.client.findByKey("form_link", Map.of("a", 1, "b", 2)).orElseThrow();
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

		MetaClass matched = this.client.selectOne("form_order", "selectList", Map.of("status", 1, "id", key))
				.orElseThrow();
		assertThat(matched.getString("order_no")).isEqualTo("N001");

		this.client.execute("form_order", "updateByKey", Map.of("id", key, "status", 2));
		assertThat(this.client.findById("form_order", key).orElseThrow().getInt("status")).isEqualTo(2);
	}

	@Test
	void executeRunsThePartialInsertFromTheMapperFile() {
		// 只给两个列：trim 把没传值的列整段剥掉，交给数据库默认值
		assertThat(this.client.execute("form_order", "insert", Map.of("order_no", "I001", "status", 1))).isEqualTo(1);

		MetaClass row = this.client.selectOne("form_order", "selectList", Map.of("order_no", "I001")).orElseThrow();
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
	void statementIdentifiersMatchColumnsRegardlessOfCase() throws Exception {
		// 元数据列名是小写（H2 DATABASE_TO_LOWER），手写文件常按 MySQL/Oracle 习惯写大写：两边必须对上，
		// 否则 #{ORDER_NO} 会静默绑成 null
		Files.writeString(this.xmlDirectory.resolve("form_order.xml"), """
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
		this.client.save("form_order", Map.of("order_no", "U001", "status", 1));
		this.client.save("form_order", Map.of("order_no", "U002", "status", 2));

		assertThat(this.client.selectList("form_order", "byStatus", Map.of("status", 1)))
				.extracting(row -> row.getString("order_no")).containsExactly("U001");
		// 调用方传大写键也一样：参数键先按元数据归一，再大小写不敏感地查表
		assertThat(this.client.selectList("form_order", "byStatus", Map.of("STATUS", 2)))
				.extracting(row -> row.getString("order_no")).containsExactly("U002");
		assertThat(this.client.sqlFor("form_order", "byStatus", Map.of()).sql()).doesNotContain("WHERE");
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

	// ------------------------------------------------------------------
	// 动态生成的 MetaClass：真实字段类型 / 零反射映射 / 结构变化后重新生成
	// ------------------------------------------------------------------

	@Test
	void queryReturnsACompiledMetaClassWithRealFieldTypes() throws Exception {
		Object key = this.client.save("form_order", Map.of(
				"order_no", "T001", "amount", "12.50", "status", 1,
				"create_time", "2026-11-11 10:00:00"));

		Class<? extends MetaClass> type = this.client.entityType("form_order");
		assertThat(type).isNotSameAs(MetaClass.class);
		assertThat(type.getSuperclass()).isEqualTo(MetaClass.class);
		assertThat(type.getSimpleName()).isEqualTo("FormOrder");
		assertThat(type.getMethod("getId").getReturnType()).isEqualTo(Long.class);
		assertThat(type.getMethod("getOrderNo").getReturnType()).isEqualTo(String.class);
		assertThat(type.getMethod("getAmount").getReturnType()).isEqualTo(BigDecimal.class);
		assertThat(type.getMethod("getStatus").getReturnType()).isEqualTo(Integer.class);
		assertThat(type.getMethod("getCreateTime").getReturnType()).isEqualTo(LocalDateTime.class);

		MetaClass row = this.client.findById("form_order", key).orElseThrow();
		assertThat(row.getClass()).isEqualTo(type);
		assertThat(row.getName()).isEqualTo(this.registry.require("form_order").name());
		assertThat(row.get("amount")).isInstanceOf(BigDecimal.class);
		assertThat(row.getBigDecimal("amount")).isEqualByComparingTo("12.50");
		assertThat(row.getLocalDateTime("create_time")).isEqualTo(LocalDateTime.of(2026, 11, 11, 10, 0));
		assertThat(row.toMap()).containsKeys("id", "order_no", "amount", "status", "create_time", "deleted_flag");
		assertThat(row.toJson()).contains("T001");

		// 类型信息留在元模型里：后续校验/转换/序列化不用面对一个没类型的 Map
		MetaProperty amount = row.property("amount");
		assertThat(amount).isNotNull();
		assertThat(amount.getType()).isEqualTo(BigDecimal.class.getName());
		assertThat(amount.getJdbcType()).isEqualTo(Types.DECIMAL);
		MetaProperty primaryKey = row.property("ID");
		assertThat(primaryKey).isNotNull();
		assertThat(primaryKey.isPrimaryKey()).isTrue();
		assertThat(primaryKey.isAutoIncrement()).isTrue();

		// 按列名写入走生成类的 switch，大小写不敏感
		row.set("STATUS", 9);
		assertThat(row.getInt("status")).isEqualTo(9);
	}

	@Test
	void refreshGeneratesANewEntityClassForTheNewStructure() throws Exception {
		Object key = this.client.save("form_order", Map.of("order_no", "V001", "status", 1));
		Class<? extends MetaClass> before = this.client.entityType("form_order");
		MetaClass oldRow = this.client.findById("form_order", key).orElseThrow();
		assertThat(oldRow.getClass()).isEqualTo(before);
		assertThat(before.getMethods()).extracting(Method::getName).doesNotContain("getRemark");

		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE form_order ADD COLUMN remark VARCHAR(64)");
		}
		this.client.refresh("form_order");

		Class<? extends MetaClass> after = this.client.entityType("form_order");
		assertThat(after).isNotEqualTo(before);
		assertThat(after.getSimpleName()).isEqualTo(before.getSimpleName());
		assertThat(after.getMethod("getRemark").getReturnType()).isEqualTo(String.class);
		// 旧类没被重定义，已取到的旧实例仍然可用
		assertThat(before.getMethods()).extracting(Method::getName).doesNotContain("getRemark");
		assertThat(oldRow.getString("order_no")).isEqualTo("V001");

		assertThat(this.client.updateById("form_order", key, Map.of("remark", "加急"))).isEqualTo(1);
		MetaClass newRow = this.client.findById("form_order", key).orElseThrow();
		assertThat(newRow.getClass()).isEqualTo(after);
		assertThat(newRow.getString("remark")).isEqualTo("加急");
	}

	@Test
	void generatedSourceIsReadableJavaWithoutReflectionAndCanBePersisted() throws Exception {
		Path sources = this.xmlDirectory.resolve("entities");
		MetaEntityRegistry registry = new MetaEntityRegistry(sources.toString());
		MetaEntityModel model = registry.model(this.registry.require("form_order"));

		assertThat(model.entityType().getSimpleName()).isEqualTo("FormOrder");
		assertThat(model.reader()).isNotNull();
		assertThat(model.source()).contains("public final class FormOrder extends MetaClass")
				.contains("private java.lang.Long id;")
				.contains("private java.math.BigDecimal amount;")
				.contains("MetaValueReader.asBigDecimal(raw)")
				.contains("MetaEntityColumns.index(rs)")
				.doesNotContain("java.lang.reflect", "getDeclaredField", "Method.invoke");

		MetaEntityTemplate.Source generated = MetaEntityTemplate.render(this.registry.require("form_order"));
		Path file = sources.resolve(generated.packageName().replace('.', '/'))
				.resolve(generated.className() + ".java");
		assertThat(file).isRegularFile();
		assertThat(Files.readString(file)).contains("generated-by: autumn-data-meta-jdbc");
	}

	@Test
	void projectionQuerySkipsMissingColumnsInsteadOfFailing() throws Exception {
		Files.writeString(this.xmlDirectory.resolve("form_order.xml"), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="projection.form_order">
				    <select id="onlyOrderNo">SELECT order_no FROM ${table} WHERE deleted_flag = 0</select>
				</mapper>
				""");
		this.client.save("form_order", Map.of("order_no", "PRJ-1", "status", 1));

		MetaClass row = this.client.selectOne("form_order", "onlyOrderNo", Map.of()).orElseThrow();
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
		MetaTableRegistry bounded = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of(), 2);

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
		MetaTableRegistry bounded = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of(), 1);
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

	@Test
	void runtimeCompilationIsInMemoryAndLeavesNoTempFiles() throws Exception {
		createTable("no_temp_dir_table");
		MetaTable table = this.registry.require("no_temp_dir_table");
		Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
		long before = tempEntityDirs(tmp);

		Class<? extends MetaClass> type = new MetaEntityRegistry().entityType(table);

		assertThat(type.getClassLoader()).isNotInstanceOf(URLClassLoader.class);
		assertThat(type.getSuperclass()).isEqualTo(MetaClass.class);
		assertThat(type.getDeclaredFields()).isNotEmpty(); // 真实类型字段确实编译出来了
		assertThat(tempEntityDirs(tmp)).isEqualTo(before); // 不再产生 autumn-meta-entity-* 临时目录
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

		MetaEntityRegistry entities = new MetaEntityRegistry(null, 1);
		Class<? extends MetaClass> first = entities.entityType(x);
		assertThat(entities.size()).isEqualTo(1);
		entities.entityType(y);
		assertThat(entities.size()).isEqualTo(1);
		// 淘汰后重新生成：同名类、不同 ClassLoader，旧类仍可用
		Class<? extends MetaClass> reloaded = entities.entityType(x);
		assertThat(reloaded).isNotSameAs(first);
		assertThat(reloaded.getName()).isEqualTo(first.getName());
		assertThat(entities.size()).isEqualTo(1);

		MetaStatementStore statements = new MetaStatementStore("", true, new H2Dialect(), 1);
		assertThat(statements.statements(x)).containsKey("selectList");
		statements.statements(y);
		assertThat(statements.size()).isEqualTo(1);
		// XML 文件归使用方所有：淘汰后重读/重新在内存生成，不会重写文件
		assertThat(statements.statements(x)).containsKey("selectList");
		assertThat(statements.size()).isEqualTo(1);
	}

	@Test
	void tableCacheSizeDefaultsToTenThousandAndIsConfigurable() {
		assertThat(MetaMapperOptions.defaults().tableCacheSize()).isEqualTo(10_000);
		assertThat(this.registry.maximumSize()).isEqualTo(10_000);
		assertThat(new MetaEntityRegistry().maximumSize()).isEqualTo(10_000);
		assertThat(new MetaStatementStore("", true, new H2Dialect()).maximumSize()).isEqualTo(10_000);
		assertThat(new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", true, "", 7)
				.tableCacheSize()).isEqualTo(7);
		assertThatThrownBy(() -> new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", true, "", 0))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tableCacheSize");
	}

	private void createTable(String name) throws Exception {
		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE " + name + " (id BIGINT AUTO_INCREMENT PRIMARY KEY, label VARCHAR(32))");
		}
	}
}
