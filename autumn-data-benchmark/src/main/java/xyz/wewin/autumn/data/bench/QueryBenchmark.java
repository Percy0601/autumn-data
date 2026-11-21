package xyz.wewin.autumn.data.bench;

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
 * 单行点查：{@code SELECT ... WHERE id = ?}，六列一行。
 *
 * <ul>
 *   <li>{@code jdbc}：原生 JDBC + 手写 setter，性能地板；</li>
 *   <li>{@code mybatis-per-call}：每次调用 openSession + getMapper，等价 Spring
 *       SqlSessionTemplate 的用法（每次一条连接）；</li>
 *   <li>{@code mybatis-shared}：长会话复用 SqlSession 与 Mapper，MyBatis 的最佳情形；</li>
 *   <li>{@code meta-code}：findById，动态拼 SQL + 每次操作借还连接 + 生成实体逐行读取；</li>
 *   <li>{@code meta-code-statement}：预注册语句路径，跳过动态 SQL 组装；</li>
 *   <li>{@code meta-generic}：GENERIC 实体（Native Image 默认档），逐行写通用属性列表。</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class QueryBenchmark {

	@Param({ "jdbc", "mybatis-per-call", "mybatis-shared", "meta-code", "meta-code-statement", "meta-generic" })
	public String impl;

	public DataSource dataSource;

	public SqlSessionFactory sessionFactory;

	public SqlSession sharedSession;

	public BenchMapper sharedMapper;

	public MetaMapperClient metaClient;

	public MetaClass params;

	public String sql;

	public OneOp op;

	@FunctionalInterface
	public interface OneOp {

		Object call() throws SQLException;

	}

	@Setup
	public void setUp() throws Exception {
		BenchDatabase.ensureSchema();
		this.dataSource = BenchDatabase.dataSource();
		this.sessionFactory = MyBatisBench.factory();
		this.sharedSession = this.sessionFactory.openSession(true);
		this.sharedMapper = this.sharedSession.getMapper(BenchMapper.class);
		this.sql = BenchDatabase.selectOneSql();
		if (this.impl.startsWith("meta")) {
			this.metaClient = "meta-generic".equals(this.impl) ? BenchMeta.generic() : BenchMeta.code();
			// 表快照 + 实体生成/编译 + 执行计划编译都是一次性成本，先在 setup 里付掉
			this.metaClient.findById(BenchDatabase.READ_TABLE, 1L).orElseThrow();
			this.params = this.metaClient.newEntity(BenchDatabase.READ_TABLE);
			this.params.set("id", 1L);
		}
		this.op = switch (this.impl) {
			case "jdbc" -> this::jdbcQuery;
			case "mybatis-per-call" -> this::mybatisPerCall;
			case "mybatis-shared" -> this::mybatisShared;
			case "meta-code", "meta-generic" -> this::dynamicFindById;
			case "meta-code-statement" -> this::metaStatement;
			default -> throw new IllegalArgumentException("unknown impl: " + this.impl);
		};
	}

	@Benchmark
	public Object selectOne() throws SQLException {
		return this.op.call();
	}

	private Object jdbcQuery() throws SQLException {
		try (Connection connection = this.dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(this.sql)) {
			statement.setLong(1, 1L);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					throw new IllegalStateException("bench row 1 is missing");
				}
				BenchOrder order = new BenchOrder();
				order.setId(resultSet.getLong(1));
				order.setUserId(resultSet.getLong(2));
				order.setOrderNo(resultSet.getString(3));
				order.setAmount(resultSet.getBigDecimal(4));
				order.setStatus(resultSet.getInt(5));
				order.setCreateTime(resultSet.getObject(6, LocalDateTime.class));
				return order;
			}
		}
	}

	private Object mybatisPerCall() {
		try (SqlSession session = this.sessionFactory.openSession(true)) {
			return session.getMapper(BenchMapper.class).selectOne(1L);
		}
	}

	private Object mybatisShared() {
		return this.sharedMapper.selectOne(1L);
	}

	private Object dynamicFindById() {
		return this.metaClient.findById(BenchDatabase.READ_TABLE, 1L).orElseThrow();
	}

	private Object metaStatement() {
		// 命名语句入口：SQL 同样来自语句 XML（这里用默认生成的 findById），只是不走专用方法。
		return this.metaClient.selectOne(BenchDatabase.READ_TABLE, "findById", this.params).orElseThrow();
	}

}
