package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.util.List;

import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 抽取阶段的产物：SQL 已经成形（占位符是 {@code ?}），但<b>还没编译成执行计划</b>。
 *
 * <p>三段一起产出，缺一不可：</p>
 * <ul>
 *   <li>{@code sql} —— 只含标识符与 {@code ?}，不含任何业务值；同一种形状每次调用都完全相同，
 *       因此既能让数据库复用游标/执行计划，也能当缓存键用；</li>
 *   <li>{@code refs} —— 每个 {@code ?} 对应的元数据引用（列名或语句参数名），
 *       是「这一槽该按什么类型绑」的唯一依据，属于形状而非数据；</li>
 *   <li>{@code values} —— 这一次调用的实参值，逐槽对应 {@code refs}。</li>
 * </ul>
 *
 * <p>把形状（{@code sql}+{@code refs}）和数据（{@code values}）分开，才能把类型判断、列查找、
 * setter 选择全部挪到编译期一次做完，运行期只剩「取值 + 类型化 setter」。</p>
 *
 * <p>{@code refs} 为空即 {@code ${}} / 字面量语义：SQL 文本已是成品，
 * {@link MetaPlanCompiler} 会编出 RAW 计划，执行层走 {@link java.sql.Statement}。</p>
 */
public record MarkedSql(String sql, List<String> refs, List<Object> values) {

	public MarkedSql {
		if (sql == null) {
			throw new IllegalArgumentException("sql must not be null");
		}
		refs = refs == null ? List.of() : List.copyOf(refs);
		values = values == null ? List.of() : List.copyOf(values);
		if (refs.size() != values.size()) {
			throw new MetaSqlException("marked SQL has " + placeholders(sql) + " placeholders but produced "
					+ refs.size() + " refs and " + values.size() + " values: " + sql);
		}
		if (refs.size() != placeholders(sql)) {
			throw new MetaSqlException("marked SQL has " + placeholders(sql) + " '?' placeholders but "
					+ refs.size() + " bind slots; template is broken: " + sql);
		}
	}

	/** 没有任何绑定槽：文本就是成品，走 {@link java.sql.Statement}。 */
	public boolean literalOnly() {
		return this.refs.isEmpty();
	}

	/** 占位符个数（{@code ?} 只会由抽取阶段自己写入，字面量里没有游离问号）。 */
	static int placeholders(String sql) {
		int count = 0;
		for (int i = 0; i < sql.length(); i++) {
			if (sql.charAt(i) == '?') {
				count++;
			}
		}
		return count;
	}
}
