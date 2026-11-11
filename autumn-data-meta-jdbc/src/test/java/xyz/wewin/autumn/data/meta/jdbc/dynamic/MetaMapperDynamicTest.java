package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

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
		MetaTableRegistry registry = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of("deleted_flag"));
		this.client = new MetaMapperClient("test", jdbc, this.connectionSource, registry,
				new MetaSqlBuilder(new H2Dialect()), 1000);
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
				.hasMessageContaining("no columns provided");
	}

	@Test
	void staticFacadeUsesDefaultPoolAndBoundHandle() {
		MetaMapper.install(MetaMapperEngine.forDataSource("default", this.dataSource));

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
}
