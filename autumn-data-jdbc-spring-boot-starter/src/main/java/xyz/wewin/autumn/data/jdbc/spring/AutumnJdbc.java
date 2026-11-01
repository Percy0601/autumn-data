package xyz.wewin.autumn.data.jdbc.spring;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import xyz.wewin.autumn.data.jdbc.dialect.Dialect;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;

/**
 * 编译期生成的仓储实现类唯一的运行期协作者：把 JDBC 客户端、方言与取数上限收在一个对象里，
 * 生成的方法体因此只有一行调用。
 *
 * <p>分层：核心模块 {@code autumn-data-jdbc} 保持零 Spring，所有 Spring JDBC 形态集中在
 * 本 starter（见 docs/dependency-layering.md）。</p>
 *
 * <p>事务：全部走 Spring 的 {@code DataSourceUtils} 取连接，因此调用方标了
 * {@code @Transactional} 就自动在事务里，生成代码不感知事务。</p>
 */
public class AutumnJdbc {

	private final JdbcClient jdbcClient;

	private final JdbcOperations jdbcOperations;

	private final Dialect dialect;

	private final FetchPolicy fetchPolicy;

	public AutumnJdbc(JdbcOperations jdbcOperations, Dialect dialect, FetchPolicy fetchPolicy) {
		this.jdbcOperations = jdbcOperations;
		this.jdbcClient = JdbcClient.create(jdbcOperations);
		this.dialect = dialect;
		this.fetchPolicy = fetchPolicy;
	}

	public FetchPolicy fetchPolicy() {
		return this.fetchPolicy;
	}

	public Dialect dialect() {
		return this.dialect;
	}

	/** 查询多行。 */
	public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
		return this.jdbcClient.sql(sql).params(args).query(mapper).list();
	}

	/** 查询单行，不存在返回空。 */
	public <T> Optional<T> queryOptional(String sql, RowMapper<T> mapper, Object... args) {
		return this.jdbcClient.sql(sql).params(args).query(mapper).optional();
	}

	/**
	 * 按 {@link FetchPolicy#associationLimit()} 限制行数的集合查询，
	 * 用于一对多的子表批量加载：再多也只取回上限行，避免一次加载打爆内存。
	 */
	public <T> List<T> queryLimited(String sql, RowMapper<T> mapper, Object... args) {
		String limited = sql + this.dialect.limitOffset(this.fetchPolicy.associationLimit(), 0);
		return query(limited, mapper, args);
	}

	/** 分页：先按方言拼分页片段取一页，再回查总行数。 */
	public <T> Page<T> page(String selectSql, String countSql, RowMapper<T> mapper, Pageable pageable) {
		String paged = selectSql + this.dialect.limitOffset(pageable.limit(), pageable.offset());
		List<T> content = query(paged, mapper);
		return Page.of(content, pageable, count(countSql));
	}

	/** 计数。 */
	public long count(String sql, Object... args) {
		Long total = this.jdbcClient.sql(sql).params(args).query(Long.class).single();
		return (total == null) ? 0L : total;
	}

	/** 单条写入（INSERT/UPDATE/DELETE），返回影响行数。 */
	public int update(String sql, Object... args) {
		return this.jdbcClient.sql(sql).params(args).update();
	}

	/** 插入并取回生成键（显式点名键列，否则部分数据库会把带默认值的列一起返回）。 */
	public Number insertReturningKey(String sql, String keyColumn, Object... args) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		this.jdbcClient.sql(sql).params(args).update(keyHolder, keyColumn);
		return keyHolder.getKey();
	}

	/** 批量写入，无生成键需求；按 {@link FetchPolicy#batchSize()} 分块提交。 */
	public int[] batchUpdate(String sql, List<Object[]> rows) {
		int[] affected = new int[rows.size()];
		int index = 0;
		for (List<Object[]> chunk : partition(rows, this.fetchPolicy.batchSize())) {
			int[] chunkResult = this.jdbcOperations.batchUpdate(sql, chunk);
			System.arraycopy(chunkResult, 0, affected, index, chunkResult.length);
			index += chunkResult.length;
		}
		return affected;
	}

	/**
	 * 批量插入并按顺序取回生成键。
	 *
	 * <p>返回的键数量与入参行数一致时才能逐个回填；数据库只回吐汇总键时返回空列表，
	 * 调用方保留原实体即可，不会误填。</p>
	 */
	public List<Number> batchInsertReturningKeys(String sql, String keyColumn, List<Object[]> rows) {
		List<Number> keys = new ArrayList<>(rows.size());
		for (List<Object[]> chunk : partition(rows, this.fetchPolicy.batchSize())) {
			KeyHolder keyHolder = new GeneratedKeyHolder();
			PreparedStatementCreator creator = connection -> connection
				.prepareStatement(sql, new String[] { keyColumn });
			this.jdbcOperations.batchUpdate(creator, new KeyedBatchSetter(chunk), keyHolder);
			for (Map<String, Object> row : keyHolder.getKeyList()) {
				Number key = extractKey(row, keyColumn);
				if (key != null) {
					keys.add(key);
				}
			}
		}
		return keys;
	}

	private static void bind(Object[] row, PreparedStatement ps) throws SQLException {
		for (int i = 0; i < row.length; i++) {
			StatementCreatorUtils.setParameterValue(ps, i + 1, SqlTypeValue.TYPE_UNKNOWN, row[i]);
		}
	}

	/** 按分块大小切片，保持行序 —— 批量插入的生成键要靠顺序回填。 */
	static List<List<Object[]>> partition(List<Object[]> rows, int batchSize) {
		List<List<Object[]>> chunks = new ArrayList<>((rows.size() + batchSize) / batchSize);
		for (int from = 0; from < rows.size(); from += batchSize) {
			chunks.add(rows.subList(from, Math.min(from + batchSize, rows.size())));
		}
		return chunks;
	}

	private static Number extractKey(Map<String, Object> keys, String keyColumn) {
		Object value = keys.get(keyColumn);
		if (value == null) {
			for (Map.Entry<String, Object> entry : keys.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(keyColumn)) {
					value = entry.getValue();
					break;
				}
			}
		}
		if (value == null && keys.size() == 1) {
			value = keys.values().iterator().next();
		}
		return (value instanceof Number number) ? number : null;
	}

	/** 把本批参数绑到 PreparedStatement 上，索引按块内行号。 */
	private final class KeyedBatchSetter implements BatchPreparedStatementSetter {

		private final List<Object[]> rows;

		private KeyedBatchSetter(List<Object[]> rows) {
			this.rows = rows;
		}

		@Override
		public void setValues(PreparedStatement ps, int index) throws SQLException {
			bind(this.rows.get(index), ps);
		}

		@Override
		public int getBatchSize() {
			return this.rows.size();
		}
	}
}
