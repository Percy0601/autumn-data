package xyz.wewin.autumn.data.jdbc.runtime;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 预编译执行计划：一段可直接执行的 SQL，加上「怎么把实参绑进去」这件事的<b>已编译</b>知识。
 *
 * <p>动态层（{@code autumn-data-meta-jdbc}）把元数据驱动或模板产出的 SQL 编译成本接口的实现：
 * 参数槽位、槽位对应的列与 JDBC 类型都在编译期定死，运行期只剩「取值 + 类型化 setter」，
 * 不再拼字符串、不再查元数据、不再猜类型。编译结果按形状缓存，同一形状只编译一次。</p>
 *
 * <p><b>执行方式由 {@link #parameterCount()} 决定</b>，和 MyBatis 的两个占位符语义一一对应：</p>
 * <ul>
 *   <li>{@code 0} —— SQL 文本已经完整（全部内容来自 {@code ${}} 拼接或纯字面量），
 *       执行层直接用 {@link java.sql.Statement}，没有东西可绑；</li>
 *   <li>{@code > 0} —— SQL 文本里是 {@code ?} 占位，执行层走 {@link PreparedStatement}，
 *       按 {@link #bind} 逐个绑定。值永远进不了 SQL 文本，因此没有注入面。</li>
 * </ul>
 *
 * @param <A> 执行期实参类型（动态层用自身的类型化载体，不是 {@code Map}）
 */
public interface PreparedPlan<A> {

	/** 可执行 SQL：RAW 计划是成品文本，预编译计划是带 {@code ?} 的文本。 */
	String sql();

	/** 需要绑定的参数个数；{@code 0} 表示走 {@link java.sql.Statement}。 */
	int parameterCount();

	/**
	 * 把实参绑定到语句上。
	 *
	 * <p>只在 {@link #parameterCount()} &gt; 0 时被调用；实现应使用类型化 setter
	 * （{@code setLong} / {@code setBigDecimal} / {@code setNull(idx, jdbcType)} 之类），
	 * 避免把类型判断留给驱动。</p>
	 */
	void bind(PreparedStatement statement, A args) throws SQLException;
}
