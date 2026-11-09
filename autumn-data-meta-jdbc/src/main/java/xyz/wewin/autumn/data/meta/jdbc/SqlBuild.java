package xyz.wewin.autumn.data.meta.jdbc;

/**
 * 动态 SQL 构建结果：SQL 字符串 + 绑定参数数组。
 *
 * <p>编译期生成的 Mapper 方法返回此对象，运行期交给 {@code AutumnJdbc} 执行。
 * 不可变 record，线程安全。</p>
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
