package xyz.wewin.autumn.data.common.mapper;

/**
 * 编译期 Mapper XML 生成方法的返回类型：SQL 字符串 + 绑定参数数组。
 *
 * <p>只承载结果，不参与 SQL 生成，也不持有任何运行期状态。使用方是编译期那一侧：
 * {@code autumn-data-jdbc-compiler} 生成的 Mapper 方法统一返回本类型。运行时动态层
 * （{@code MetaSqlFileStore} / {@code SqlTemplate}）产出的是 {@code MarkedSql}，
 * 因为它还需要记录每个 {@code ?} 引用的是哪一列。</p>
 *
 * @param sql 带 {@code ?} 占位符的 SQL
 * @param args 按占位符顺序排列的参数值
 */
public record SqlBuild(String sql, Object[] args) {

	public SqlBuild {
		if (sql == null) {
			throw new IllegalArgumentException("sql must not be null");
		}
		if (args == null) {
			args = new Object[0];
		}
	}

}
