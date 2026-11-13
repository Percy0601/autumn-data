package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.PerThreadConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「抽取 → 再转换 → 按形状缓存」这条底座的针对性测试。
 *
 * <p>它盯的是两件事：形状相同的语句只编译一次（编译是这条链路唯一的额外成本，只要每执行一次编一遍
 * 就变成负收益），以及走 {@code PreparedStatement} 还是 {@code Statement} 只由有没有绑定槽决定。</p>
 */
class MetaSqlPlanTest {

	private static final AtomicInteger DB_SEQ = new AtomicInteger();

	private JdbcDataSource dataSource;

	private MetaMapperClient client;

	@TempDir
	Path workDirectory;

	@BeforeEach
	void setUp() throws Exception {
		this.dataSource = new JdbcDataSource();
		this.dataSource.setURL("jdbc:h2:mem:meta_plan_" + DB_SEQ.incrementAndGet()
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
		ConnectionSource connectionSource = new PerThreadConnectionSource(
				() -> new JdbcConnectionSource(this.dataSource));
		AutumnJdbc jdbc = new AutumnJdbc(connectionSource, new H2Dialect(), FetchPolicy.defaults());
		MetaTableRegistry registry = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of("deleted_flag"));
		MetaStatementStore statementStore = new MetaStatementStore(this.workDirectory, true, new H2Dialect());
		this.client = new MetaMapperClient("test", jdbc, connectionSource, registry,
				new MetaSqlBuilder(new H2Dialect()), statementStore, new MetaEntityRegistry(), new MetaPlanCache(),
				1000);
	}

	/** 只有形状进缓存：换值是换数据，不是换形状，不该再编一次。 */
	@Test
	void eachShapeIsCompiledOnceAndReusedAcrossCalls() {
		this.client.save(row("form_order", "order_no", "P1", "status", 1));
		assertThat(this.client.compiledShapes()).isEqualTo(1);
		this.client.save(row("form_order", "order_no", "P2", "status", 2));
		assertThat(this.client.compiledShapes()).isEqualTo(1); // 同样的列集合 = 同样的形状

		this.client.findList("form_order", MetaQuery.by("status", 1));
		assertThat(this.client.compiledShapes()).isEqualTo(2);

		this.client.findList("form_order", MetaQuery.by("status", 1));
		this.client.findList("form_order", MetaQuery.by("status", 2));
		this.client.findList("form_order", MetaQuery.by("status", 3).contains("order_no", "P"));
		assertThat(this.client.compiledShapes()).isEqualTo(3);

		// IN 的长度参与文本，因而是另一个形状
		this.client.findList("form_order", MetaQuery.by("status", MetaQuery.Op.IN, 1, 2));
		this.client.findList("form_order", MetaQuery.by("status", MetaQuery.Op.IN, 3, 4));
		assertThat(this.client.compiledShapes()).isEqualTo(4);
	}

	/** 改表之后旧形状的槽位已经对着不存在的列，必须整体作废而不是继续复用。 */
	@Test
	void schemaChangeInvalidatesCompiledShapes() throws Exception {
		this.client.findList("form_order", MetaQuery.by("status", 1));
		assertThat(this.client.compiledShapes()).isEqualTo(1);

		try (Connection connection = this.dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE form_order ADD COLUMN remark VARCHAR(64)");
		}
		this.client.refresh("form_order");
		assertThat(this.client.compiledShapes()).isZero();

		assertThat(this.client.findList("form_order", MetaQuery.by("status", 1))).isEmpty();
		assertThat(this.client.compiledShapes()).isEqualTo(1);
	}

	/** 全 {@code ${}} 的语句没有槽位：按 MyBatis 语义就该走 {@link java.sql.Statement}，而不是空绑定的 prepared。 */
	@Test
	void placeholderFreeStatementRunsThroughPlainStatement() throws Exception {
		Files.writeString(this.workDirectory.resolve("form_order.xml"), """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="raw.form_order">
				    <select id="allOrderNos">SELECT order_no FROM ${table}</select>
				</mapper>
				""");
		this.client.save(row("form_order", "order_no", "RAW1", "status", 1));

		MarkedSql marked = this.client.sqlFor("form_order", "allOrderNos", new MetaClass());
		assertThat(marked.literalOnly()).isTrue();
		assertThat(marked.sql()).doesNotContain("?");

		assertThat(this.client.selectList("form_order", "allOrderNos", new MetaClass()))
				.extracting(row -> row.getString("order_no")).containsExactly("RAW1");
		// 一条 insert 的形状 + 这条零槽形状
		assertThat(this.client.compiledShapes()).isEqualTo(2);
	}

	/** 槽位带的是列的知识：类型、setter 策略、NULL 上报的 JDBC 类型，都在这里定完。 */
	@Test
	void compilerPutsColumnTypeIntoEverySlot() {
		MetaTable table = typedTable();
		MarkedSql marked = new MarkedSql("SELECT ?, ?, ? FROM x",
				List.of("amount", "create_time", "keyword"), List.of("12.50", "2026-11-11 10:00:00", "abc"));

		MetaSqlPlan plan = MetaPlanCompiler.compile(table, marked);

		assertThat(plan.parameterCount()).isEqualTo(3);
		assertThat(plan.slots().get(0).isColumn()).isTrue();
		assertThat(plan.slots().get(0).kind()).isEqualTo(MetaBindSlot.Kind.DECIMAL);
		assertThat(plan.slots().get(0).jdbcType()).isEqualTo(Types.DECIMAL);
		assertThat(plan.slots().get(1).kind()).isEqualTo(MetaBindSlot.Kind.TIMESTAMP);
		// 引用不是列（业务参数、循环值）：原样交给驱动，不猜类型
		assertThat(plan.slots().get(2).isColumn()).isFalse();
		assertThat(plan.slots().get(2).kind()).isEqualTo(MetaBindSlot.Kind.OBJECT);
	}

	/** 字面量里的问号不是槽位：{@code '?'} 不能把抽取阶段的自校验打成「模板坏了」。 */
	@Test
	void questionMarkInsideLiteralIsNotASlot() {
		MarkedSql marked = new MarkedSql("SELECT '?' FROM x WHERE id = ?", List.of("id"), List.of(7));
		assertThat(marked.values()).hasSize(1);
		assertThat(MetaPlanCompiler.compile(typedTable(), marked).parameterCount()).isEqualTo(1);
	}

	/** 实体上写过的列决定 INSERT 形状：列集合一变就是另一个形状，没写的列根本不进语句。 */
	@Test
	void changedColumnSubsetDrivesTheInsertShape() {
		MetaClass both = this.client.save(row("form_order", "order_no", "S1", "status", 1));
		assertThat(this.client.compiledShapes()).isEqualTo(1);

		MetaClass single = this.client.save(row("form_order", "order_no", "S2"));
		assertThat(this.client.compiledShapes()).isEqualTo(2);
		// 没写过的列不进语句：交给数据库默认，而不是被写成 NULL
		assertThat(this.client.findById("form_order", single.get("id")).orElseThrow().get("status")).isNull();
		assertThat(this.client.findById("form_order", both.get("id")).orElseThrow().getInt("status")).isEqualTo(1);
	}

	private MetaClass row(String table, Object... nameValues) {
		MetaClass entity = this.client.newEntity(table);
		for (int i = 0; i < nameValues.length; i += 2) {
			entity.set((String) nameValues[i], nameValues[i + 1]);
		}
		return entity;
	}

	private static MetaTable typedTable() {
		MetaClass meta = new MetaClass();
		meta.setName("form_order");
		meta.setProperties(List.of(column("id", Types.BIGINT, Long.class.getName(), true),
				column("order_no", Types.VARCHAR, String.class.getName(), false),
				column("amount", Types.DECIMAL, BigDecimal.class.getName(), false),
				column("create_time", Types.TIMESTAMP, java.time.LocalDateTime.class.getName(), false)));
		return new MetaTable(meta, null);
	}

	private static MetaProperty column(String name, int jdbcType, String javaType, boolean primaryKey) {
		MetaProperty property = new MetaProperty();
		property.setName(name);
		property.setJdbcType(jdbcType);
		property.setType(javaType);
		property.setPrimaryKey(primaryKey);
		return property;
	}
}
