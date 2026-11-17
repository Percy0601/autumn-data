package xyz.wewin.autumn.data.bench;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;

/**
 * 列表查询：{@code SELECT ... ORDER BY id LIMIT 100}，一次返回 100 行。
 *
 * <p>这一组把「逐行映射成本」按 100 倍放大，是反射 vs 零反射最容易分出高下的场景。</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class ListQueryBenchmark {

	@Param({ "jdbc", "mybatis-per-call", "mybatis-shared", "meta-code", "meta-generic" })
	public String impl;

	public DataSource dataSource;

	public SqlSessionFactory sessionFactory;

	public SqlSession sharedSession;

	public BenchMapper sharedMapper;

	public MetaMapperClient metaClient;

	public String sql;

	public ListOp op;

	@FunctionalInterface
	public interface ListOp {

		List<?> call() throws SQLException;

	}

	@Setup
	public void setUp() throws Exception {
		BenchDatabase.ensureSchema();
		this.dataSource = BenchDatabase.dataSource();
		this.sessionFactory = MyBatisBench.factory();
		this.sharedSession = this.sessionFactory.openSession(true);
		this.sharedMapper = this.sharedSession.getMapper(BenchMapper.class);
		this.sql = BenchDatabase.selectListSql();
		if (this.impl.startsWith("meta")) {
			this.metaClient = "meta-generic".equals(this.impl) ? BenchMeta.generic() : BenchMeta.code();
			// 表快照 + 实体生成/编译 + 计划编译在 setup 付掉
			this.metaClient.findList(BenchDatabase.READ_TABLE, ORDER_BY_ID, BenchDatabase.LIST_ROWS);
		}
		this.op = switch (this.impl) {
			case "jdbc" -> this::jdbcQuery;
			case "mybatis-per-call" -> this::mybatisPerCall;
			case "mybatis-shared" -> this::mybatisShared;
			case "meta-code", "meta-generic" -> this::metaFindList;
			default -> throw new IllegalArgumentException("unknown impl: " + this.impl);
		};
	}

	@Benchmark
	public List<?> selectList() throws SQLException {
		return this.op.call();
	}

	private List<BenchOrder> jdbcQuery() throws SQLException {
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(this.sql);
				ResultSet resultSet = statement.executeQuery()) {
			List<BenchOrder> orders = new ArrayList<>(BenchDatabase.LIST_ROWS);
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
			return orders;
		}
	}

	private List<BenchOrder> mybatisPerCall() {
		try (SqlSession session = this.sessionFactory.openSession(true)) {
			return session.getMapper(BenchMapper.class).selectList(BenchDatabase.LIST_ROWS);
		}
	}

	private List<BenchOrder> mybatisShared() {
		return this.sharedMapper.selectList(BenchDatabase.LIST_ROWS);
	}

	private List<?> metaFindList() {
		return this.metaClient.findList(BenchDatabase.READ_TABLE, ORDER_BY_ID, BenchDatabase.LIST_ROWS);
	}

	private static final MetaQuery ORDER_BY_ID = MetaQuery.all().orderBy("id");

}
