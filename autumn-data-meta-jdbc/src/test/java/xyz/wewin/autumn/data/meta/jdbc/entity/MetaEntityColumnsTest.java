package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 列索引「每结果集一次」的语义锁：逐行复用、换结果集重建、投影缺列保持原样。 */
class MetaEntityColumnsTest {

	private static final java.util.concurrent.atomic.AtomicInteger DB_SEQ =
			new java.util.concurrent.atomic.AtomicInteger();

	private JdbcDataSource dataSource;

	@BeforeEach
	void setUp() throws Exception {
		this.dataSource = new JdbcDataSource();
		this.dataSource.setURL("jdbc:h2:mem:meta_columns_" + DB_SEQ.incrementAndGet()
				+ ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		this.dataSource.setUser("sa");
		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE t (id BIGINT, order_no VARCHAR(32))");
			statement.execute("INSERT INTO t VALUES (1, 'A'), (2, 'B')");
		}
	}

	@Test
	void reusesIndexAcrossRowsOfSameResultSet() throws Exception {
		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT id, order_no FROM t")) {
			Map<String, Integer> first = MetaEntityColumns.index(rs);
			assertThat(first).containsEntry("id", 1).containsEntry("order_no", 2);
			while (rs.next()) {
				assertThat(MetaEntityColumns.index(rs)).isSameAs(first);
			}
		}
	}

	@Test
	void rebuildsIndexForNewResultSet() throws Exception {
		Map<String, Integer> first;
		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT id, order_no FROM t")) {
			first = MetaEntityColumns.index(rs);
		}
		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT order_no FROM t")) {
			Map<String, Integer> second = MetaEntityColumns.index(rs);
			assertThat(second).isNotSameAs(first).containsEntry("order_no", 1).doesNotContainKey("id");
		}
	}
}
