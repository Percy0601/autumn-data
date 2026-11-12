package xyz.wewin.autumn.data.jdbc.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.dialect.LimitClause;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;

/**
 * 编译期生成的仓储实现类唯一的运行期门面：SQL 执行 + 方言分页 + 取数上限。
 *
 * <p>执行层只用 {@code java.sql}，不依赖 spring-jdbc，保持核心零 Spring。连接经由
 * {@link ConnectionSource} 取得，因此无 Spring 也能跑；在 Spring 环境下注入
 * {@code SpringConnectionSource} 即自动加入 {@code @Transactional}。</p>
 */
public class AutumnJdbc {

	private final ConnectionSource connectionSource;

	private final Dialect dialect;

	private final FetchPolicy fetchPolicy;

	public AutumnJdbc(ConnectionSource connectionSource, Dialect dialect, FetchPolicy fetchPolicy) {
		this.connectionSource = connectionSource;
		this.dialect = dialect;
		this.fetchPolicy = fetchPolicy;
	}

	public FetchPolicy fetchPolicy() {
		return this.fetchPolicy;
	}

	public Dialect dialect() {
		return this.dialect;
	}

	public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
		try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
			List<T> rows = new ArrayList<>();
			int rowNum = 0;
			while (rs.next()) {
				rows.add(mapper.mapRow(rs, rowNum++));
			}
			return rows;
		}
		catch (SQLException ex) {
			throw new SqlException("query failed: " + sql, ex);
		}
	}

	public <T> Optional<T> queryOptional(String sql, RowMapper<T> mapper, Object... args) {
		List<T> rows = query(sql, mapper, args);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	/**
	 * 带行数上限的查询：把上限交给方言拼成 {@code LIMIT}，等价于 {@link #queryLimited} 但上限由调用方指定
	 * （派生查询的 {@code First/Top N} 在编译期就把数字内联到首个参数）。
	 * 上限放在首位，避免与 {@link #query(String, RowMapper, Object...)} 在数值实参上产生重载歧义。
	 */
	public <T> List<T> query(long limit, String sql, RowMapper<T> mapper, Object... args) {
		return query(sql + this.dialect.limitOffset(limit, 0), mapper, args);
	}

	/**
	 * 带行数上限的单行查询：取满足条件的前若干行里的第一行（{@code findFirst/Top} 返回 Optional 时用到）。
	 */
	public <T> Optional<T> queryOptional(long limit, String sql, RowMapper<T> mapper, Object... args) {
		List<T> rows = query(sql + this.dialect.limitOffset(limit, 0), mapper, args);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	public <T> List<T> queryLimited(String sql, RowMapper<T> mapper, Object... args) {
		String limited = sql + this.dialect.limitOffset(this.fetchPolicy.associationLimit(), 0);
		return query(limited, mapper, args);
	}

	public <T> Page<T> page(String selectSql, String countSql, RowMapper<T> mapper, Pageable pageable) {
		String paged = selectSql + this.dialect.limitOffset(pageable.limit(), pageable.offset());
		List<T> content = query(paged, mapper);
		long total = count(countSql);
		return Page.of(content, pageable, total);
	}

	/**
	 * 带谓词参数的分页：{@code selectSql} 已含编译期映射好的 {@code ORDER BY}（列名安全），
	 * {@code countSql} 是同一 WHERE 的计数语句（不带排序），两者共用同一组 {@code args}。
	 */
	public <T> Page<T> page(String selectSql, String countSql, RowMapper<T> mapper, Pageable pageable,
			Object... args) {
		String paged = selectSql + this.dialect.limitOffset(pageable.limit(), pageable.offset());
		List<T> content = query(paged, mapper, args);
		long total = count(countSql, args);
		return Page.of(content, pageable, total);
	}

	public long count(String sql, Object... args) {
		try (PreparedStatement ps = prepare(sql, args); ResultSet rs = ps.executeQuery()) {
			if (rs.next()) {
				long value = rs.getLong(1);
				return rs.wasNull() ? 0L : value;
			}
			return 0L;
		}
		catch (SQLException ex) {
			throw new SqlException("count failed: " + sql, ex);
		}
	}

	public int update(String sql, Object... args) {
		try (PreparedStatement ps = prepare(sql, args)) {
			return ps.executeUpdate();
		}
		catch (SQLException ex) {
			throw new SqlException("update failed: " + sql, ex);
		}
	}

	public Number insertReturningKey(String sql, String keyColumn, Object... args) {
		Connection conn = connection();
		try (PreparedStatement ps = conn.prepareStatement(sql, new String[] { keyColumn })) {
			setParams(ps, args);
			ps.executeUpdate();
			try (ResultSet keys = ps.getGeneratedKeys()) {
				if (keys.next()) {
					return (Number) keys.getObject(1);
				}
				return null;
			}
		}
		catch (SQLException ex) {
			throw new SqlException("insert failed: " + sql, ex);
		}
	}

	public int[] batchUpdate(String sql, List<Object[]> rows) {
		int[] total = new int[rows.size()];
		int cursor = 0;
		for (List<Object[]> chunk : partition(rows, this.fetchPolicy.batchSize())) {
			try (PreparedStatement ps = connection().prepareStatement(sql)) {
				for (Object[] row : chunk) {
					setParams(ps, row);
					ps.addBatch();
				}
				int[] result = ps.executeBatch();
				System.arraycopy(result, 0, total, cursor, result.length);
				cursor += result.length;
			}
			catch (SQLException ex) {
				throw new SqlException("batchUpdate failed: " + sql, ex);
			}
		}
		return total;
	}

	public List<Number> batchInsertReturningKeys(String sql, String keyColumn, List<Object[]> rows) {
		List<Number> keys = new ArrayList<>(rows.size());
		for (List<Object[]> chunk : partition(rows, this.fetchPolicy.batchSize())) {
			try (PreparedStatement ps = connection().prepareStatement(sql, new String[] { keyColumn })) {
				for (Object[] row : chunk) {
					setParams(ps, row);
					ps.addBatch();
				}
				ps.executeBatch();
				try (ResultSet rs = ps.getGeneratedKeys()) {
					while (rs.next()) {
						keys.add((Number) rs.getObject(1));
					}
				}
			}
			catch (SQLException ex) {
				throw new SqlException("batchInsert failed: " + sql, ex);
			}
		}
		return keys;
	}

	// ------------------------------------------------------------------
	// 预编译执行计划：#{} 语义 → PreparedStatement；${}/字面量语义 → Statement
	// ------------------------------------------------------------------

	/**
	 * 执行预编译计划取列表。
	 *
	 * <p>{@link PreparedPlan#parameterCount()} 为 0 时 SQL 文本已是成品，直接走
	 * {@link java.sql.Statement}；大于 0 时走 {@link PreparedStatement}，由计划自己完成类型化绑定。</p>
	 */
	public <A, T> List<T> query(PreparedPlan<A> plan, A args, RowMapper<T> mapper) {
		return query(plan, args, mapper, 0L, 0L);
	}

	/** 执行预编译计划取列表并限制行数。 */
	public <A, T> List<T> query(PreparedPlan<A> plan, A args, RowMapper<T> mapper, long limit) {
		return query(plan, args, mapper, limit, 0L);
	}

	/** 执行预编译计划并分页（{@code limit<=0} 表示不分页）。 */
	public <A, T> List<T> query(PreparedPlan<A> plan, A args, RowMapper<T> mapper, long limit, long offset) {
		return execute(plan, args, limit, offset, false, null, execution -> {
			try (ResultSet rs = execution.query()) {
				List<T> rows = new ArrayList<>();
				int rowNum = 0;
				while (rs.next()) {
					rows.add(mapper.mapRow(rs, rowNum++));
				}
				return rows;
			}
		});
	}

	/** 取一行；没有结果返回 {@link Optional#empty()}。 */
	public <A, T> Optional<T> queryOptional(PreparedPlan<A> plan, A args, RowMapper<T> mapper, long limit) {
		List<T> rows = query(plan, args, mapper, limit);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	/** 执行计数计划（返回单个数字的 SELECT）。 */
	public <A> long count(PreparedPlan<A> plan, A args) {
		return execute(plan, args, 0L, 0L, false, null, execution -> {
			try (ResultSet rs = execution.query()) {
				if (rs.next()) {
					long value = rs.getLong(1);
					return rs.wasNull() ? 0L : value;
				}
				return 0L;
			}
		});
	}

	/** 执行 INSERT / UPDATE，返回影响行数。 */
	public <A> int update(PreparedPlan<A> plan, A args) {
		return execute(plan, args, 0L, 0L, false, null, execution -> execution.modify(false));
	}

	/** 执行 INSERT 并取回指定主键列的生成值。 */
	public <A> Number insertReturningKey(PreparedPlan<A> plan, A args, String keyColumn) {
		return execute(plan, args, 0L, 0L, true, keyColumn, execution -> {
			execution.modify(true);
			try (ResultSet generated = execution.statement().getGeneratedKeys()) {
				if (generated.next() && generated.getObject(1) instanceof Number number) {
					return number;
				}
				return null;
			}
		});
	}

	/**
	 * 批量执行同一计划：语句只准备一次，逐行绑定后 {@code addBatch}，按 {@code batchSize} 分块。
	 *
	 * <p>零绑定计划（成品 SQL）退化为逐条执行——没有可绑的差异，重复同一条语句没有意义。</p>
	 */
	public <A> int[] batchUpdate(PreparedPlan<A> plan, List<A> rows) {
		int n = rows.size();
		if (plan.parameterCount() == 0) {
			int[] single = new int[n];
			for (int i = 0; i < n; i++) {
				single[i] = update(plan, rows.get(i));
			}
			return single;
		}
		int[] total = new int[n];
		int index = 0;
		for (List<A> chunk : partitionArgs(rows, this.fetchPolicy.batchSize())) {
			try (PreparedStatement ps = connection().prepareStatement(plan.sql())) {
				for (A row : chunk) {
					plan.bind(ps, row);
					ps.addBatch();
				}
				int[] result = ps.executeBatch();
				System.arraycopy(result, 0, total, index, result.length);
				index += result.length;
			}
			catch (SQLException ex) {
				throw new SqlException("batchUpdate failed: " + plan.sql(), ex);
			}
		}
		return total;
	}

	/** 批量 INSERT 并按入参顺序取回生成主键。 */
	public <A> List<Number> batchInsertReturningKeys(PreparedPlan<A> plan, List<A> rows, String keyColumn) {
		List<Number> keys = new ArrayList<>(rows.size());
		if (plan.parameterCount() == 0) {
			for (A row : rows) {
				keys.add(insertReturningKey(plan, row, keyColumn));
			}
			return keys;
		}
		for (List<A> chunk : partitionArgs(rows, this.fetchPolicy.batchSize())) {
			try (PreparedStatement ps = connection().prepareStatement(plan.sql(), new String[] { keyColumn })) {
				for (A row : chunk) {
					plan.bind(ps, row);
					ps.addBatch();
				}
				ps.executeBatch();
				try (ResultSet rs = ps.getGeneratedKeys()) {
					while (rs.next() && rs.getObject(1) instanceof Number number) {
						keys.add(number);
					}
				}
			}
			catch (SQLException ex) {
				throw new SqlException("batchInsert failed: " + plan.sql(), ex);
			}
		}
		return keys;
	}

	/**
	 * 分页执行：内容查询带参数化 LIMIT/OFFSET，计数查询用同一实参，两条计划共用一次绑定语义。
	 */
	public <A, T> Page<T> page(PreparedPlan<A> select, PreparedPlan<A> count, A args, RowMapper<T> mapper,
			Pageable pageable) {
		List<T> content = query(select, args, mapper, pageable.limit(), pageable.offset());
		long total = count(count, args);
		return Page.of(content, pageable, total);
	}

	/**
	 * 计划执行分流：{@code parameterCount()==0} 的成品 SQL 走 {@link Statement}（分页片段由方言内联），
	 * 否则走 {@link PreparedStatement}（分页片段带 {@code ?}，续在业务参数之后绑定）。
	 */
	private <A, T> T execute(PreparedPlan<A> plan, A args, long limit, long offset, boolean returnKeys,
			String keyColumn, PlanAction<T> action) {
		boolean paged = limit > 0 || offset > 0;
		if (plan.parameterCount() == 0) {
			String sql = paged ? plan.sql() + this.dialect.limitOffset(limit, offset) : plan.sql();
			try (Statement st = connection().createStatement()) {
				return action.apply(new SqlExecution(st, sql));
			}
			catch (SQLException ex) {
				throw new SqlException("statement failed: " + sql, ex);
			}
		}
		LimitClause clause = this.dialect.limitClause();
		String sql = paged ? plan.sql() + clause.fragment() : plan.sql();
		try (PreparedStatement ps = returnKeys ? connection().prepareStatement(sql, new String[] { keyColumn })
				: connection().prepareStatement(sql)) {
			plan.bind(ps, args);
			if (paged) {
				long[] bound = clause.bind(limit, offset);
				ps.setLong(plan.parameterCount() + 1, bound[0]);
				ps.setLong(plan.parameterCount() + 2, bound[1]);
			}
			return action.apply(new SqlExecution(ps, sql));
		}
		catch (SQLException ex) {
			throw new SqlException("prepared statement failed: " + sql, ex);
		}
	}

	/** 语句与分页片段都就绪后的执行动作。 */
	private interface PlanAction<T> {

		T apply(SqlExecution execution) throws SQLException;
	}

	/**
	 * 抹平 {@link Statement} 与 {@link PreparedStatement} 的执行入口差异：前者在 execute 时才交文本、
	 * 自增键也只能在这里声明，后者文本已在 prepare 阶段交出、再传文本属于误用。
	 */
	private record SqlExecution(Statement statement, String sql) {

		ResultSet query() throws SQLException {
			if (this.statement instanceof PreparedStatement prepared) {
				return prepared.executeQuery();
			}
			return this.statement.executeQuery(this.sql);
		}

		int modify(boolean returnGeneratedKeys) throws SQLException {
			if (this.statement instanceof PreparedStatement prepared) {
				return prepared.executeUpdate();
			}
			return this.statement.executeUpdate(this.sql,
					returnGeneratedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
		}
	}

	private <A> List<List<A>> partitionArgs(List<A> rows, int batchSize) {
		List<List<A>> chunks = new ArrayList<>((rows.size() + batchSize) / batchSize);
		for (int from = 0; from < rows.size(); from += batchSize) {
			chunks.add(rows.subList(from, Math.min(from + batchSize, rows.size())));
		}
		return chunks;
	}

	private Connection connection() {
		try {
			return this.connectionSource.getConnection();
		}
		catch (SQLException ex) {
			throw new SqlException("failed to obtain connection", ex);
		}
	}

	private PreparedStatement prepare(String sql, Object... args) throws SQLException {
		PreparedStatement ps = connection().prepareStatement(sql);
		setParams(ps, args);
		return ps;
	}

	private static void setParams(PreparedStatement ps, Object... args) throws SQLException {
		for (int i = 0; i < args.length; i++) {
			Object value = args[i];
			if (value == null) {
				ps.setNull(i + 1, Types.NULL);
			}
			else if (value instanceof byte[] bytes) {
				ps.setBytes(i + 1, bytes);
			}
			else {
				ps.setObject(i + 1, value);
			}
		}
	}

	/** 按分块大小切片，保持行序 —— 批量插入的生成键要靠顺序回填。纯函数。 */
	public static List<List<Object[]>> partition(List<Object[]> rows, int batchSize) {
		List<List<Object[]>> chunks = new ArrayList<>((rows.size() + batchSize) / batchSize);
		for (int from = 0; from < rows.size(); from += batchSize) {
			chunks.add(rows.subList(from, Math.min(from + batchSize, rows.size())));
		}
		return chunks;
	}

	/** 运行期 SQL 执行异常，包装 {@link SQLException} 避免散落各处。 */
	public static class SqlException extends RuntimeException {

		public SqlException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
