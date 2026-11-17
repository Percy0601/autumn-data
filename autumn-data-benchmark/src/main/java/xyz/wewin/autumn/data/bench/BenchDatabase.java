package xyz.wewin.autumn.data.bench;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 基准用的数据库骨架：H2 内存库 + 单一 HikariCP 连接池，双方（meta-jdbc 与 MyBatis）
 * 共用同一个 {@link DataSource}，这样"连接池借还"这一项对两边完全一样。
 *
 * <p>两张表：{@code bench_order} 只读（预灌 {@value #SEED_ROWS} 行），
 * {@code bench_order_insert} 只写（每个迭代开始前清空，避免表无限长大让 B 树成本漂移）。</p>
 */
public final class BenchDatabase {

	/** 读表名。 */
	public static final String READ_TABLE = "bench_order";

	/** 写表名。 */
	public static final String WRITE_TABLE = "bench_order_insert";

	/** 每次查询取回的行数。 */
	public static final int LIST_ROWS = 100;

	/** 预灌行数。 */
	public static final int SEED_ROWS = 200;

	/** 列顺序：两边 SQL 完全一致，结果集形状也就一致。 */
	public static final String COLUMNS = "id, user_id, order_no, amount, status, create_time";

	/** 注册语句用的 SQL 模板（{@code ${table}} / {@code #{id}} 是 meta-jdbc 的占位写法）。 */
	public static final String SELECT_ONE_TEMPLATE = "SELECT " + COLUMNS + " FROM ${table} WHERE id = #{id}";

	private static volatile HikariDataSource dataSource;

	private BenchDatabase() {
	}

	public static HikariDataSource dataSource() {
		HikariDataSource local = dataSource;
		if (local == null) {
			synchronized (BenchDatabase.class) {
				local = dataSource;
				if (local == null) {
					local = createDataSource();
					dataSource = local;
				}
			}
		}
		return local;
	}

	private static HikariDataSource createDataSource() {
		HikariConfig config = new HikariConfig();
		config.setPoolName("bench-pool");
		config.setJdbcUrl("jdbc:h2:mem:autumn-bench;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
		config.setUsername("sa");
		config.setPassword("");
		config.setMaximumPoolSize(8);
		config.setMinimumIdle(2);
		config.setAutoCommit(true);
		return new HikariDataSource(config);
	}

	/** 建表 + 灌数（幂等：每次都先 drop，JMH 每个 fork 各建一份）。 */
	public static void ensureSchema() {
		try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE IF EXISTS " + READ_TABLE);
			statement.execute("DROP TABLE IF EXISTS " + WRITE_TABLE);
			statement.execute(ddl(READ_TABLE));
			statement.execute(ddl(WRITE_TABLE));
		}
		catch (SQLException ex) {
			throw new IllegalStateException("failed to create bench schema", ex);
		}
		seed(SEED_ROWS);
	}

	private static String ddl(String table) {
		return "CREATE TABLE " + table + " ("
				+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
				+ "user_id BIGINT, "
				+ "order_no VARCHAR(32) NOT NULL, "
				+ "amount DECIMAL(12,2), "
				+ "status INT, "
				+ "create_time TIMESTAMP)";
	}

	private static void seed(int rows) {
		try (Connection connection = dataSource().getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO " + READ_TABLE + " (user_id, order_no, amount, status, create_time) "
								+ "VALUES (?, ?, ?, ?, ?)")) {
			for (int i = 0; i < rows; i++) {
				statement.setLong(1, 1000L + i);
				statement.setString(2, "ORD-" + i);
				statement.setBigDecimal(3, new BigDecimal("12.34"));
				statement.setInt(4, i % 4);
				statement.setObject(5, LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(i));
				statement.addBatch();
			}
			statement.executeBatch();
		}
		catch (SQLException ex) {
			throw new IllegalStateException("failed to seed bench data", ex);
		}
	}

	/** 清空写表，回到空表状态（每迭代一次）。 */
	public static void resetWriteTable() {
		try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("TRUNCATE TABLE " + WRITE_TABLE);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("failed to truncate " + WRITE_TABLE, ex);
		}
	}

	public static String selectOneSql() {
		return "SELECT " + COLUMNS + " FROM " + READ_TABLE + " WHERE id = ?";
	}

	public static String selectListSql() {
		return "SELECT " + COLUMNS + " FROM " + READ_TABLE + " ORDER BY id LIMIT " + LIST_ROWS;
	}

	public static String selectListParameterizedSql() {
		return "SELECT " + COLUMNS + " FROM " + READ_TABLE + " ORDER BY id LIMIT ?";
	}

	/** 复用同一个 DataSource 读一份可重复遍历的结果集，用于"只测映射、不测数据库"的基准。 */
	public static List<BenchOrder> fetchOrders(DataSource dataSource, int rows) {
		List<BenchOrder> orders = new ArrayList<>(rows);
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(selectListParameterizedSql())) {
			statement.setInt(1, rows);
			try (java.sql.ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					BenchOrder order = new BenchOrder();
					order.setId(resultSet.getLong(1));
					order.setUserId(resultSet.getLong(2));
					order.setOrderNo(resultSet.getString(3));
					order.setAmount(resultSet.getBigDecimal(4));
					order.setStatus(resultSet.getInt(5));
					order.setCreateTime(resultSet.getObject(6, LocalDateTime.class));
					orders.add(order);
				}
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException("failed to fetch bench rows", ex);
		}
		return orders;
	}

}
