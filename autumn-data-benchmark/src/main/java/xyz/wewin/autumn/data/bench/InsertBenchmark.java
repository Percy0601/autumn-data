package xyz.wewin.autumn.data.bench;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;

/**
 * 单行插入：五行参数 + 取回自增主键。
 *
 * <p>三条路都包含「构造实体并赋值」这一步（mybatis 建 POJO 调 setter，meta 建生成实体调 setter），
 * 区别只在持久化链路。写表每迭代清空，避免表无限长大让 B 树成本漂移。</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class InsertBenchmark {

	@Param({ "jdbc", "mybatis-per-call", "mybatis-shared", "meta-code", "meta-generic" })
	public String impl;

	private static final long USER_ID = 2001L;

	private static final String ORDER_NO = "BENCH-INSERT-0001";

	private static final BigDecimal AMOUNT = new BigDecimal("12.34");

	private static final int STATUS = 1;

	private static final LocalDateTime CREATE_TIME = LocalDateTime.of(2026, 1, 1, 8, 0);

	public DataSource dataSource;

	public SqlSessionFactory sessionFactory;

	public SqlSession sharedSession;

	public BenchMapper sharedMapper;

	public MetaMapperClient metaClient;

	public String sql;

	public InsertOp op;

	@FunctionalInterface
	public interface InsertOp {

		Object call() throws SQLException;

	}

	@Setup(Level.Trial)
	public void setUp() throws Exception {
		BenchDatabase.ensureSchema();
		this.dataSource = BenchDatabase.dataSource();
		this.sessionFactory = MyBatisBench.factory();
		this.sharedSession = this.sessionFactory.openSession(true);
		this.sharedMapper = this.sharedSession.getMapper(BenchMapper.class);
		this.sql = "INSERT INTO " + BenchDatabase.WRITE_TABLE
				+ " (user_id, order_no, amount, status, create_time) VALUES (?, ?, ?, ?, ?)";
		if (this.impl.startsWith("meta")) {
			this.metaClient = "meta-generic".equals(this.impl) ? BenchMeta.generic() : BenchMeta.code();
			this.metaClient.newEntity(BenchDatabase.WRITE_TABLE);
		}
		this.op = switch (this.impl) {
			case "jdbc" -> this::jdbcInsert;
			case "mybatis-per-call" -> this::mybatisPerCall;
			case "mybatis-shared" -> this::mybatisShared;
			case "meta-code", "meta-generic" -> this::metaSave;
			default -> throw new IllegalArgumentException("unknown impl: " + this.impl);
		};
	}

	/** 每个迭代都从空表开始，插入成本不随累计行数漂移。 */
	@Setup(Level.Iteration)
	public void resetTable() {
		BenchDatabase.resetWriteTable();
	}

	@Benchmark
	public Object insert() throws SQLException {
		return this.op.call();
	}

	private Object jdbcInsert() throws SQLException {
		BenchOrder order = newOrder();
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(this.sql, new String[] { "id" })) {
			statement.setLong(1, order.getUserId());
			statement.setString(2, order.getOrderNo());
			statement.setBigDecimal(3, order.getAmount());
			statement.setInt(4, order.getStatus());
			statement.setObject(5, order.getCreateTime());
			statement.executeUpdate();
			try (ResultSet keys = statement.getGeneratedKeys()) {
				if (keys.next()) {
					order.setId(keys.getLong(1));
				}
			}
			return order;
		}
	}

	private Object mybatisPerCall() {
		try (SqlSession session = this.sessionFactory.openSession(true)) {
			return session.getMapper(BenchMapper.class).insert(newOrder()) == 0 ? null : "inserted";
		}
	}

	private Object mybatisShared() {
		return this.sharedMapper.insert(newOrder());
	}

	private Object metaSave() {
		MetaClass entity = this.metaClient.newEntity(BenchDatabase.WRITE_TABLE);
		entity.set("user_id", USER_ID);
		entity.set("order_no", ORDER_NO);
		entity.set("amount", AMOUNT);
		entity.set("status", STATUS);
		entity.set("create_time", CREATE_TIME);
		return this.metaClient.save(entity);
	}

	private static BenchOrder newOrder() {
		BenchOrder order = new BenchOrder();
		order.setUserId(USER_ID);
		order.setOrderNo(ORDER_NO);
		order.setAmount(AMOUNT);
		order.setStatus(STATUS);
		order.setCreateTime(CREATE_TIME);
		return order;
	}

}
