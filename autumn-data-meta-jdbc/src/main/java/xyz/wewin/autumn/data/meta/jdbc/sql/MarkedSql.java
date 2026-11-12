package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.util.ArrayList;
import java.util.Collections;
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
		// 实参里 null 是合法值（显式把一列写成 NULL），所以只能拷贝后包一层，不能用 List.copyOf
		values = values == null ? List.of()
				: Collections.unmodifiableList(new ArrayList<>(values));
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

	/** 占位符个数：只数单引号字面量之外的 {@code ?}，模板里写死的 {@code '?'} 不算槽位。 */
	static int placeholders(String sql) {
		int count = 0;
		int index = 0;
		while (index < sql.length()) {
			char c = sql.charAt(index);
			if (c == '\'') {
				index = endOfLiteral(sql, index);
				continue;
			}
			if (c == '?') {
				count++;
			}
			index++;
		}
		return count;
	}

	/** 跳过一段单引号字面量（{@code ''} 表示引号本身），返回结束位置的下一个下标。 */
	private static int endOfLiteral(String sql, int openQuote) {
		int index = openQuote + 1;
		while (index < sql.length()) {
			if (sql.charAt(index) != '\'') {
				index++;
				continue;
			}
			if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
				index += 2;
				continue;
			}
			return index + 1;
		}
		return index;
	}
}
