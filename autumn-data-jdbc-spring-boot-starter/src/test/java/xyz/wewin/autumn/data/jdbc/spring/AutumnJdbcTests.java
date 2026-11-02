package xyz.wewin.autumn.data.jdbc.spring;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import xyz.wewin.autumn.common.jdbc.dialect.H2Dialect;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行期门面验证（真连 H2）：方言分页片段、批量插入按序取回生成键、
 * 一对多加载上限（默认 1000、可配）、批量分块纯函数。
 */
class AutumnJdbcTests {

	private static final String INSERT_ITEM = "INSERT INTO demo_item (name, owner_id) VALUES (?, ?)";

	private static final RowMapper<String> NAME_MAPPER = (rs, rowNum) -> rs.getString("name");

	private JdbcTemplate jdbcTemplate;

	private AutumnJdbc autumn;

	@BeforeEach
	void setUp() {
		DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:autumn-jdbc-facade;DB_CLOSE_DELAY=-1", "sa",
				"");
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.jdbcTemplate.execute("DROP TABLE IF EXISTS demo_item");
		this.jdbcTemplate
			.execute("CREATE TABLE demo_item (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64) NOT NULL, owner_id BIGINT)");
		this.autumn = new AutumnJdbc(new JdbcConnectionSource(dataSource), new H2Dialect(), FetchPolicy.defaults());
	}

	@Test
	void partitionIsOrderPreservingAndChunkedBySize() {
		List<Object[]> rows = java.util.stream.IntStream.range(0, 10).mapToObj(i -> (Object[]) new Object[] { i })
			.toList();
		List<List<Object[]>> chunks = AutumnJdbc.partition(rows, 4);
		assertThat(chunks).hasSize(3);
		assertThat(chunks).extracting(List::size).containsExactly(4, 4, 2);
		assertThat(chunks.getFirst().getFirst()[0]).isEqualTo(0);
		assertThat(chunks.getLast().getLast()[0]).isEqualTo(9);
		assertThat(AutumnJdbc.partition(List.of(), 4)).isEmpty();
	}

	@Test
	void pageAppliesDialectPagingAndCountsTotal() {
		insertNames(10);
		Page<String> first = this.autumn.page("SELECT name FROM demo_item ORDER BY id",
				"SELECT COUNT(*) FROM demo_item", NAME_MAPPER, Pageable.of(0, 4));
		assertThat(first.content()).containsExactly("n0", "n1", "n2", "n3");
		assertThat(first.total()).isEqualTo(10L);
		assertThat(first.totalPages()).isEqualTo(3);
		assertThat(first.hasNext()).isTrue();

		Page<String> last = this.autumn.page("SELECT name FROM demo_item ORDER BY id",
				"SELECT COUNT(*) FROM demo_item", NAME_MAPPER, Pageable.of(2, 4));
		assertThat(last.content()).containsExactly("n8", "n9");
		assertThat(last.hasNext()).isFalse();
	}

	@Test
	void batchInsertReturnsGeneratedKeysInOrder() {
		List<Number> keys = this.autumn.batchInsertReturningKeys(INSERT_ITEM, "id",
				List.of(new Object[] { "a", 1L }, new Object[] { "b", 1L }, new Object[] { "c", 1L }));
		assertThat(keys).hasSize(3);
		assertThat(this.autumn.count("SELECT COUNT(*) FROM demo_item")).isEqualTo(3L);
		List<Long> ids = keys.stream().map(Number::longValue).toList();
		for (int i = 1; i < ids.size(); i++) {
			assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
		}
	}

	@Test
	void batchInsertBackfillsAcrossChunks() {
		AutumnJdbc chunked = new AutumnJdbc(new JdbcConnectionSource(this.jdbcTemplate.getDataSource()),
				new H2Dialect(), new FetchPolicy(1000, 2));
		List<Object[]> rows = List.of(new Object[] { "a", 1L }, new Object[] { "b", 1L }, new Object[] { "c", 1L },
				new Object[] { "d", 1L }, new Object[] { "e", 1L });
		assertThat(chunked.batchInsertReturningKeys(INSERT_ITEM, "id", rows)).hasSize(5);
		assertThat(chunked.count("SELECT COUNT(*) FROM demo_item")).isEqualTo(5L);
	}

	@Test
	void batchUpdateWithoutKeysAffectsEveryRow() {
		int[] affected = this.autumn.batchUpdate(INSERT_ITEM,
				List.of(new Object[] { "x", 2L }, new Object[] { "y", 2L }, new Object[] { "z", 2L }));
		assertThat(affected).hasSize(3);
		assertThat(this.autumn.count("SELECT COUNT(*) FROM demo_item WHERE owner_id = ?", 2L)).isEqualTo(3L);
	}

	@Test
	void associationLimitCapsCollectionFetchAndDefaultsToOneThousand() {
		insertNames(10);
		AutumnJdbc capped = new AutumnJdbc(new JdbcConnectionSource(this.jdbcTemplate.getDataSource()), new H2Dialect(),
				new FetchPolicy(3, 100));
		assertThat(capped.queryLimited("SELECT name FROM demo_item ORDER BY id", NAME_MAPPER)).hasSize(3);
		assertThat(this.autumn.queryLimited("SELECT name FROM demo_item ORDER BY id", NAME_MAPPER)).hasSize(10);
	}

	@Test
	void singleRowRoundTrip() {
		Number key = this.autumn.insertReturningKey(INSERT_ITEM, "id", "solo", 7L);
		assertThat(key).isNotNull();
		assertThat(this.autumn.update("UPDATE demo_item SET name = ? WHERE id = ?", "renamed", key)).isEqualTo(1);
		assertThat(this.autumn.queryOptional("SELECT name FROM demo_item WHERE id = ?", NAME_MAPPER, key))
			.contains("renamed");
	}

	private void insertNames(int count) {
		for (int i = 0; i < count; i++) {
			this.jdbcTemplate.update(INSERT_ITEM, "n" + i, 1L);
		}
	}
}
