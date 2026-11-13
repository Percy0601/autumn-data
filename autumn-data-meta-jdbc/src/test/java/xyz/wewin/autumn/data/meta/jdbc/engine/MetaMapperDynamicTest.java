package xyz.wewin.autumn.data.meta.jdbc.engine;
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
import java.util.ArrayList;
import java.util.List;
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
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityColumns;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityModel;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntitySource;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaModelStore;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;
import xyz.wewin.autumn.data.meta.jdbc.sql.MarkedSql;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaPlanCache;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlBuilder;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaStatementStore;

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
		MetaStatementStore statementStore = new MetaStatementStore(this.xmlDirectory, true, new H2Dialect());
		this.client = new MetaMapperClient("test", jdbc, this.connectionSource, this.registry,
				new MetaSqlBuilder(new H2Dialect()), statementStore, ENTITIES, new MetaPlanCache(), 1000);
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
	void findListAppliesConditionsOrderAndLimit() {
		this.client.save(row("form_order", "order_no", "B001", "amount", "5.00", "status", 1));
		this.client.save(row("form_order", "order_no", "B002", "amount", "15.00", "status", 1));
		this.client.save(row("form_order", "order_no", "B003", "amount", "25.00", "status", 2));

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
		this.client.save(row("form_order",
				"order_no", "N001", "status", 1, "create_time", "2026-01-01 10:00:00"));
		this.client.save(row("form_order", "order_no", "N002", "status", 1));

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
			this.client.save(row("form_order", "order_no", "P00" + i, "status", 1, "amount", i * 10));
		}

		Page<MetaClass> page = this.client.findPage("form_order", MetaQuery.by("status", 1), Pageable.of(0, 2));

		assertThat(page.content()).hasSize(2);
		assertThat(page.total()).isEqualTo(3);
		assertThat(page.totalPages()).isEqualTo(2);
		assertThat(this.client.count("form_order", MetaQuery.by("status", 1))).isEqualTo(3);
		assertThat(this.client.exists("form_order", MetaQuery.by("order_no", "P002"))).isTrue();
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
		assertThat(this.client.findByKey(row("form_order", "id", saved.get(2).get("id")))
				.orElseThrow().getString("order_no")).isEqualTo("M003");
	}

	@Test
	void saveAllFallsBackToRowByRowWhenShapesDiffer() {
		List<MetaClass> saved = this.client.saveAll(List.of(
				row("form_order", "order_no", "S001", "status", 1),
				row("form_order", "order_no", "S002", "amount", "8.00")));

		assertThat(saved).allSatisfy(entity -> assertThat(entity.get("id")).isNotNull());
		MetaClass second = this.client.findOne("form_order", MetaQuery.by("order_no", "S002")).orElseThrow();
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
		assertThat(this.client.count("form_order", MetaQuery.all())).isZero();
	}

	@Test
	void unknownColumnAndEmptyUpdateAreRejected() {
		assertThatThrownBy(() -> this.client.findList("form_order", MetaQuery.by("missing_column", 1)))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("missing_column");

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
		MetaClass row = this.client.findByKey(key).orElseThrow();
		assertThat(row.getString("label")).isEqualTo("first");

		row.set("label", "second");
		assertThat(this.client.update(row)).isEqualTo(1);
		assertThat(this.client.findByKey(key).orElseThrow().getString("label")).isEqualTo("second");
	}

	@Test
	void namedStatementsGenerateTheMapperFileOnce() throws Exception {
		Path file = this.xmlDirectory.resolve("form_order.xml");
		this.client.save(row("form_order", "order_no", "G001", "status", 1));
		assertThat(file).doesNotExist();

		assertThat(this.client.selectCount("form_order", "selectCount", params())).isEqualTo(1);

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

		assertThat(this.client.selectList("form_order", "selectList", params("order_no", "G001")))
				.extracting(row -> row.getString("order_no")).containsExactly("G001");
		// 传实体也行：列名按元数据对上，其余的动态属性原样进模板作用域
		assertThat(this.client.selectList("form_order", "selectList", row("form_order", "order_no", "G001")))
				.extracting(row -> row.getString("order_no")).containsExactly("G001");
		assertThat(Files.readString(file)).isEqualTo(content);
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

		assertThatThrownBy(() -> this.client.execute("form_order", "dropById", params("id", first.get("id"))))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("does not run physical deletes");
		assertThat(this.client.findById("form_order", first.get("id"))).isPresent();

		assertThat(Files.readString(file)).isEqualTo(custom);
	}

	@Test
	void sqlFragmentsAreInlinedAndReusable() throws Exception {
		Files.writeString(this.xmlDirectory.resolve("form_order.xml"), """
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
		Files.writeString(this.xmlDirectory.resolve("form_order.xml"), """
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
		Files.writeString(this.xmlDirectory.resolve("form_order.xml"), """
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
		Files.writeString(this.xmlDirectory.resolve("form_order.xml"), """
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
		Files.writeString(this.xmlDirectory.resolve("form_order.xml"), """
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
		Files.writeString(this.xmlDirectory.resolve("form_order.xml"), """
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
		Files.writeString(this.xmlDirectory.resolve("form_order.xml"), """
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

	// ------------------------------------------------------------------
	// 动态生成的 MetaClass：真实字段类型 / 零反射映射 / 结构变化后重新生成
	// ------------------------------------------------------------------

	@Test
	void queryReturnsACompiledMetaClassWithRealFieldTypes() throws Exception {
		MetaClass inserted = this.client.save(row("form_order",
				"order_no", "T001", "amount", "12.50", "status", 1,
				"create_time", "2026-11-11 10:00:00"));
		assertThat(inserted.getClass()).isSameAs(this.client.entityType("form_order"));

		Class<? extends MetaClass> type = this.client.entityType("form_order");
		assertThat(type).isNotSameAs(MetaClass.class);
		assertThat(type.getSuperclass()).isEqualTo(MetaClass.class);
		assertThat(type.getSimpleName()).isEqualTo("FormOrder");
		assertThat(type.getMethod("getId").getReturnType()).isEqualTo(Long.class);
		assertThat(type.getMethod("getOrderNo").getReturnType()).isEqualTo(String.class);
		assertThat(type.getMethod("getAmount").getReturnType()).isEqualTo(BigDecimal.class);
		assertThat(type.getMethod("getStatus").getReturnType()).isEqualTo(Integer.class);
		assertThat(type.getMethod("getCreateTime").getReturnType()).isEqualTo(LocalDateTime.class);

		MetaClass row = this.client.findById("form_order", inserted.get("id")).orElseThrow();
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
		// 类型化 setter 同样标脏，否则这一次改动对更新层不可见
		type.getMethod("setAmount", BigDecimal.class).invoke(row, new BigDecimal("99.00"));
		assertThat(row.isChanged("amount")).isTrue();
		assertThat(this.client.update(row)).isEqualTo(1);
		assertThat(this.client.findById("form_order", inserted.get("id")).orElseThrow().getBigDecimal("amount"))
				.isEqualByComparingTo("99.00");
	}

	@Test
	void refreshGeneratesANewEntityClassForTheNewStructure() throws Exception {
		MetaClass saved = this.client.save(row("form_order", "order_no", "V001", "status", 1));
		Object key = saved.get("id");
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

		// 旧实例的生成类里没有 remark 字段，值落在动态属性上；更新层按元数据认名字，照样写得进去
		oldRow.set("remark", "补录");
		assertThat(this.client.update(oldRow)).isEqualTo(1);
		assertThat(this.client.findById("form_order", key).orElseThrow().getString("remark")).isEqualTo("补录");

		MetaClass newRow = this.client.findById("form_order", key).orElseThrow();
		assertThat(newRow.getClass()).isEqualTo(after);
		newRow.set("remark", "加急");
		assertThat(this.client.update(newRow)).isEqualTo(1);
		assertThat(this.client.findById("form_order", key).orElseThrow().getString("remark")).isEqualTo("加急");
	}

	@Test
	void generatedSourceIsReadableJavaWithoutReflectionAndCanBePersisted() throws Exception {
		Path sources = this.xmlDirectory.resolve("entities");
		MetaEntityRegistry registry = new MetaEntityRegistry(new MetaEntitySource(), sources, 16);
		MetaEntityModel model = registry.model(this.registry.require("form_order"));

		assertThat(model.entityType().getSimpleName()).isEqualTo("FormOrder");
		assertThat(model.reader()).isNotNull();
		assertThat(model.source()).contains("public final class FormOrder extends MetaClass")
				.contains("private java.lang.Long id;")
				.contains("private java.math.BigDecimal amount;")
				.contains("MetaValueReader.asBigDecimal(raw)")
				.contains("MetaEntityColumns.index(rs)")
				.doesNotContain("java.lang.reflect", "getDeclaredField", "Method.invoke");

		MetaEntitySource.Source generated = registry.source().render(this.registry.require("form_order"));
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

		MetaEntityRegistry entities = new MetaEntityRegistry(new MetaEntitySource(), null, 1);
		Class<? extends MetaClass> first = entities.entityType(x);
		assertThat(entities.size()).isEqualTo(1);
		entities.entityType(y);
		assertThat(entities.size()).isEqualTo(1);
		// 淘汰后重新生成：同名类、不同 ClassLoader，旧类仍可用
		Class<? extends MetaClass> reloaded = entities.entityType(x);
		assertThat(reloaded).isNotSameAs(first);
		assertThat(reloaded.getName()).isEqualTo(first.getName());
		assertThat(entities.size()).isEqualTo(1);

		MetaStatementStore statements = new MetaStatementStore(null, true, new H2Dialect(), 1);
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
		assertThat(new MetaStatementStore(null, true, new H2Dialect()).maximumSize()).isEqualTo(10_000);
		assertThat(new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", true, 7)
				.tableCacheSize()).isEqualTo(7);
		assertThatThrownBy(() -> new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", true, 0))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tableCacheSize");
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
	void columnsCollidingWithMetaClassMembersDoNotHijackBaseContract() throws Exception {
		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE form_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
					+ "name VARCHAR(32), namespace VARCHAR(32), properties INT)");
		}

		MetaClass item = this.client.save(row("form_item",
				"name", "苹果", "namespace", "ns-1", "properties", 7));
		MetaClass loaded = this.client.findById("form_item", item.get("id")).orElseThrow();

		// setName/getName 仍是元模型契约（表名），业务列 name 只能通过字符串读写碰
		assertThat(loaded.getName()).isEqualTo(this.registry.require("form_item").name());
		assertThat(loaded.getString("name")).isEqualTo("苹果");
		assertThat(loaded.getString("namespace")).isEqualTo("ns-1");
		assertThat(loaded.getInt("properties")).isEqualTo(7);
		assertThat(loaded.get("name")).isEqualTo("苹果");
		assertThat(loaded.property("properties")).isNotNull();
		assertThat(loaded.toMap()).containsEntry("name", "苹果").containsEntry("properties", 7);

		loaded.set("name", "梨子");
		assertThat(this.client.update(loaded)).isEqualTo(1);
		assertThat(this.client.findById("form_item", item.get("id")).orElseThrow().getString("name"))
				.isEqualTo("梨子");
	}

	@Test
	void reservedWordAndUnicodeColumnNamesStillCompile() throws Exception {
		MetaTable table = tableWithColumns("weird_form", "class", "名称", "order desc", "2fa_token");
		MetaEntityModel model = new MetaEntityRegistry().model(table);

		MetaClass instance = model.entityType().getConstructor().newInstance();
		instance.set("class", "A");
		instance.set("名称", "苹果");
		instance.set("order desc", "B");
		instance.set("2fa_token", "C");

		assertThat(instance.getString("名称")).isEqualTo("苹果");
		assertThat(instance.getString("class")).isEqualTo("A");
		assertThat(instance.toMap()).containsEntry("名称", "苹果").containsEntry("class", "A");
		assertThat(model.source()).contains("generated-by: autumn-data-meta-jdbc");
	}

	@Test
	void concurrentEntityGenerationIsSingleFlightAndReturnsOneType() throws Exception {
		createTable("concurrent_entity");
		MetaTable table = this.registry.require("concurrent_entity");
		MetaEntityRegistry entities = new MetaEntityRegistry(new MetaEntitySource(), null, 4);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Class<? extends MetaClass>>> futures = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				futures.add(pool.submit(() -> {
					start.await(5, TimeUnit.SECONDS);
					return entities.entityType(table);
				}));
			}
			start.countDown();
			Class<? extends MetaClass> expected = futures.get(0).get(20, TimeUnit.SECONDS);
			for (Future<Class<? extends MetaClass>> future : futures) {
				assertThat(future.get(20, TimeUnit.SECONDS)).isSameAs(expected);
			}
			assertThat(entities.size()).isEqualTo(1);
			assertThat(expected.getDeclaredFields()).isNotEmpty();
		}
		finally {
			pool.shutdownNow();
		}
	}

	/** 缓存上限压到 1，连着改三次表：每次都要重新生成类，旧实例仍必须能读。 */
	@Test
	void repeatedSchemaEvolutionWithTinyCachesKeepsOldRowsUsable() throws Exception {
		MetaMapperOptions options = new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(),
				this.xmlDirectory.toString(), true, 1);
		MetaMapperClient evolving = MetaMapperEngine
				.forDataSource("evolve", this.dataSource, options).defaultClient();

		MetaClass saved = evolving.save(fill(evolving, "form_order", "order_no", "EV1", "status", 1));
		Object key = saved.get("id");
		Class<? extends MetaClass> previous = evolving.entityType("form_order");
		List<MetaClass> snapshots = new ArrayList<>();

		for (int i = 1; i <= 3; i++) {
			try (Connection connection = this.dataSource.getConnection();
					Statement statement = connection.createStatement()) {
				statement.execute("ALTER TABLE form_order ADD COLUMN col_" + i + " VARCHAR(16)");
			}
			evolving.refresh("form_order");
			Class<? extends MetaClass> current = evolving.entityType("form_order");
			assertThat(current).isNotSameAs(previous);
			assertThat(current.getSimpleName()).isEqualTo(previous.getSimpleName());
			assertThat(current.getName()).isNotEqualTo(previous.getName()); // 指纹变了 → 新包

			MetaClass added = evolving.findById("form_order", key).orElseThrow();
			added.set("col_" + i, "v" + i);
			assertThat(evolving.update(added)).isEqualTo(1);
			assertThat(added.getClass()).isSameAs(current);
			assertThat(added.getString("col_" + i)).isEqualTo("v" + i);
			snapshots.add(added);
			previous = current;
		}

		for (int i = 0; i < snapshots.size(); i++) {
			assertThat(snapshots.get(i).getString("order_no")).isEqualTo("EV1");
			assertThat(snapshots.get(i).getString("col_" + (i + 1))).isEqualTo("v" + (i + 1));
		}
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
