package xyz.wewin.autumn.data.meta.jdbc;

/**
 * 动态 SQL 构建结果：SQL 字符串 + 绑定参数数组。
 *
 * <p>只承载结果，不参与 SQL 生成，也不持有任何运行期状态。使用方是编译期那一侧：
 * {@code autumn-data-jdbc-compiler} 生成的 Mapper 方法统一返回本类型。</p>
 *
 * <p>动态侧（{@code MetaSqlBuilder} / {@code SqlTemplate}）不再产出它，而是产出
 * {@code MarkedSql} —— 光有「文本 + 值」不足以预编译，还需要每个 {@code ?} 引用的是哪一列，
 * 那份知识见 {@code MetaBindSlot} / {@code MetaSqlPlan}。</p>
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
