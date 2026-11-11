package xyz.wewin.autumn.data.meta.jdbc;

/**
 * 动态 SQL 构建结果：SQL 字符串 + 绑定参数数组。
 *
 * <p>静态侧（{@code autumn-data-jdbc-compiler} 编译期生成的 Mapper）与动态侧
 * （{@code MetaSqlBuilder} / {@code SqlTemplate} 运行期解释执行）共用这一份不可变结果；
 * 它只承载结果，不参与 SQL 生成，也不持有任何运行期状态。</p>
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
