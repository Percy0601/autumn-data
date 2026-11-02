package xyz.wewin.autumn.data.jdbc.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import xyz.wewin.autumn.data.common.dialect.Dialect;
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
