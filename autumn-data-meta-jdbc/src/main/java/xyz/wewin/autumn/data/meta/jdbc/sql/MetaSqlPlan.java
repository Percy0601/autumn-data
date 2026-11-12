package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import xyz.wewin.autumn.data.jdbc.runtime.PreparedPlan;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 再转换的产物：一段形状固定的 SQL，加上每个 {@code ?} 的编译期绑定知识。
 *
 * <p>它实现 {@link PreparedPlan}，因此执行层（{@code AutumnJdbc}）不需要知道动态层的存在：
 * {@link #parameterCount()} 为 0 就是 {@code ${}} / 字面量语义，走 {@link java.sql.Statement}；
 * 大于 0 就是 {@code #{}} 语义，走 {@link PreparedStatement} 并由本类逐槽绑定。</p>
 *
 * <p>实参是<b>按槽位顺序排列的值数组</b>，不是 {@code Map}：顺序由 {@link MarkedSql} 产出时就是唯一的
 * 对齐依据，把它保留成位置参数，绑定时既没有哈希查找也没有键名大小写问题；
 * {@code Map} 只出现在调用方边界上，不进入这一层。</p>
 *
 * <p>同一个实例可被任意多个连接、任意多个线程复用：状态只有不可变的 {@code sql} 与 {@code slots}，
 * 每次执行新 {@code prepareStatement} 后再 {@code bind}。</p>
 */
public final class MetaSqlPlan implements PreparedPlan<Object[]> {

	private final String sql;

	private final List<MetaBindSlot> slots;

	MetaSqlPlan(String sql, List<MetaBindSlot> slots) {
		this.sql = sql;
		this.slots = List.copyOf(slots);
	}

	@Override
	public String sql() {
		return this.sql;
	}

	@Override
	public int parameterCount() {
		return this.slots.size();
	}

	/** 编译期定死的槽位（诊断与测试断言用）。 */
	public List<MetaBindSlot> slots() {
		return this.slots;
	}

	/**
	 * 按槽位顺序绑定实参。
	 *
	 * <p>长度不匹配是抽取阶段的 bug（模板分支与实参来源不一致），宁可报错也不要静默少绑：
	 * 少绑一个 {@code ?} 在数据库端是语法错误，多绑一个则完全无声。</p>
	 */
	@Override
	public void bind(PreparedStatement statement, Object[] args) throws SQLException {
		Object[] values = args == null ? new Object[0] : args;
		if (values.length != this.slots.size()) {
			throw new MetaSqlException("plan has " + this.slots.size() + " bind slots but got " + values.length
					+ " values: " + this.sql);
		}
		for (int i = 0; i < this.slots.size(); i++) {
			this.slots.get(i).bind(statement, i + 1, values[i]);
		}
	}
}
