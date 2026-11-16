package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import xyz.wewin.autumn.data.common.mapper.TemplateNode;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 手写 SQL 文本 → {@link TemplateNode} 模板。
 *
 * <p>XML Mapper 是「文件 + 标签」的写法，低代码平台很多场景（设计器里临时拼一条查询、业务侧想
 * 直接在代码里写 SQL 而懒得建文件）只需要一段纯文本。这里补的正是那条最短路径：</p>
 *
 * <ul>
 *   <li>{@code #{name}} —— 参数占位，运行期变成 {@code ?} 并走 JDBC 绑定，值永远不进 SQL 文本；</li>
 *   <li>{@code ${name}} —— 标识符片段（表名、列清单、排序方向），展开结果仍过
 *       {@link SqlTemplate#isIdentifierFragment(String)} 白名单；{@code ${table}} 由客户端按方言
 *       自动填成引号后的表名；</li>
 *   <li><b>没有 XML 标签</b>：{@code <if>} / {@code <foreach>} 这类分支写在 Java 侧，
 *       或者改用 Mapper 文件。手写 SQL 与文件走同一个 {@link SqlTemplate} 解释器，因此绑定、类型
 *       收口、计划缓存完全一致。</li>
 * </ul>
 *
 * <p>两条刻意的约束：</p>
 * <ol>
 *   <li><b>不接受裸 {@code ?} 占位符</b>。{@code ?} 没有名字，类型收口与计划缓存都需要「这一槽引用
 *       哪一列」，裸问号只能原样交给驱动、把类型判断丢回运行期。与其到执行时才报「占位符个数对不上」，
 *       不如在解析阶段直接提示改写 {@code #{name}}。</li>
 *   <li><b>注释与单引号字面量里的 {@code #{...}} 不解析</b>：{@code -- 说明 #{id}} 不应该被当成一个
 *       绑定槽，{@code WHERE tag = '#foo'} 也不该被截断。</li>
 * </ol>
 */
public final class SqlTextParser {

	private SqlTextParser() {
	}

	/** 解析一段手写 SQL：产出文本 / {@code #{}} / {@code ${}} 三类节点。 */
	public static List<TemplateNode> parse(String sql) {
		if (sql == null || sql.isBlank()) {
			throw new MetaSqlException("raw SQL must not be blank");
		}
		List<TemplateNode> nodes = new ArrayList<>();
		StringBuilder text = new StringBuilder(sql.length());
		int index = 0;
		while (index < sql.length()) {
			char current = sql.charAt(index);
			if (current == '\'') {
				int end = endOfLiteral(sql, index);
				text.append(sql, index, end);
				index = end;
				continue;
			}
			if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
				int end = sql.indexOf('\n', index);
				end = end < 0 ? sql.length() : end;
				text.append(sql, index, end);
				index = end;
				continue;
			}
			if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
				int end = sql.indexOf("*/", index + 2);
				end = end < 0 ? sql.length() : end + 2;
				text.append(sql, index, end);
				index = end;
				continue;
			}
			if ((current == '#' || current == '$') && index + 1 < sql.length()
					&& sql.charAt(index + 1) == '{') {
				char marker = current;
				int close = sql.indexOf('}', index + 2);
				if (close < 0) {
					throw new MetaSqlException("unclosed " + marker + "{...} placeholder in raw SQL at offset "
							+ index + ": " + excerpt(sql, index));
				}
				String name = sql.substring(index + 2, close).trim();
				requireName(name, marker, sql, index);
				flush(text, nodes);
				nodes.add(marker == '#'
						? new TemplateNode.Parameter(name)
						: new TemplateNode.Variable(name));
				index = close + 1;
				continue;
			}
			if (current == '?') {
				throw new MetaSqlException("raw SQL uses a bare '?' placeholder at offset " + index
						+ "; write #{name} instead so the value is bound and typed: " + excerpt(sql, index));
			}
			text.append(current);
			index++;
		}
		flush(text, nodes);
		if (nodes.isEmpty()) {
			throw new MetaSqlException("raw SQL has no content");
		}
		return List.copyOf(nodes);
	}

	/**
	 * 开头关键字（{@code SELECT} / {@code INSERT} / {@code UPDATE} / {@code DELETE} / {@code WITH}），
	 * 用来在手写 SQL 入口上做一次类型校验；跳过前导空白与注释，没有关键字时返回空串。
	 */
	public static String leadingKeyword(String sql) {
		if (sql == null) {
			return "";
		}
		int index = 0;
		while (index < sql.length()) {
			char current = sql.charAt(index);
			if (Character.isWhitespace(current)) {
				index++;
				continue;
			}
			if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
				int end = sql.indexOf('\n', index);
				index = end < 0 ? sql.length() : end + 1;
				continue;
			}
			if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
				int end = sql.indexOf("*/", index + 2);
				index = end < 0 ? sql.length() : end + 2;
				continue;
			}
			break;
		}
		int start = index;
		while (index < sql.length() && Character.isLetter(sql.charAt(index))) {
			index++;
		}
		return sql.substring(start, index).toUpperCase(Locale.ROOT);
	}

	private static void flush(StringBuilder text, List<TemplateNode> nodes) {
		if (text.isEmpty()) {
			return;
		}
		nodes.add(new TemplateNode.Text(text.toString()));
		text.setLength(0);
	}

	/** 单引号字面量：{@code ''} 表示引号本身，所以不能简单找下一个引号。 */
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

	private static void requireName(String name, char marker, String sql, int offset) {
		if (name.isEmpty()) {
			throw new MetaSqlException("empty " + marker + "{} placeholder in raw SQL at offset " + offset);
		}
		for (int i = 0; i < name.length(); i++) {
			char current = name.charAt(i);
			if (Character.isLetterOrDigit(current) || current == '_' || current == '.' || current == '$') {
				continue;
			}
			throw new MetaSqlException("invalid " + marker + "{" + name + "} placeholder in raw SQL: names only "
					+ "accept letters, digits, '_', '.' and '$'");
		}
	}

	private static String excerpt(String sql, int offset) {
		int from = Math.max(0, offset - 10);
		int to = Math.min(sql.length(), offset + 40);
		String text = sql.substring(from, to).replace('\n', ' ').strip();
		return (from > 0 ? "…" : "") + text + (to < sql.length() ? "…" : "");
	}
}
