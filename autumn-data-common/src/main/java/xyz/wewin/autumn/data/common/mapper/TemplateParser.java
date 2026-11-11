package xyz.wewin.autumn.data.common.mapper;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 模板体解析器：把 XML 语句元素内的混合内容（文本 + 控制标签）解析成 {@link TemplateNode} AST。
 *
 * <p>编译期与动态运行期共用这一份解析。关键约束：<b>{@code #{...}} 在解析阶段就被摘成
 * {@link TemplateNode.Parameter} 节点</b>，绝不以文本形式留在 SQL 里，因此不存在
 * 「模板先渲染、参数后补」的二次解释顺序问题，也不会和任何模板语言的插值语法互相打断。</p>
 *
 * <p>支持的 XML 标签：</p>
 * <ul>
 *   <li>{@code <if test="expr">...</if>} — 条件块</li>
 *   <li>{@code <choose>/<when test="...">/<otherwise>} — 多分支</li>
 *   <li>{@code <foreach collection="..." item="..." separator="..." open="(" close=")">...</foreach>} — 循环</li>
 *   <li>{@code <where>...</where>} — 条件容器（{@code <trim prefix="WHERE " prefixOverrides="AND|OR">} 的语法糖）</li>
 *   <li>{@code <trim prefix="(" suffix=")" suffixOverrides=",">...</trim>} — 带前后缀的容器</li>
 * </ul>
 *
 * <p>文本内容中的插值语法：</p>
 * <ul>
 *   <li>{@code ${name}} — 变量插值（原样拼入 SQL，用于表名/列名/排序方向）</li>
 *   <li>{@code #{name}} — 参数占位符（生成 {@code ?}，运行期 JDBC 绑定）</li>
 * </ul>
 *
 * <p>注意：{@code <#if ...>}／{@code <#list ...>} 这类 FreeMarker 标签不是合法 XML
 * （标签名不能以 {@code #} 开头、表达式不是属性），一旦被 DOM 解析器看到就直接
 * {@code SAXParseException}；写进 CDATA 又会变成不可解析的纯文本。因此本方言只接受
 * MyBatis 式标签，FreeMarker 的兼容做在 {@code test} 表达式层
 * （{@code name??}、{@code name?has_content}、{@code !expr}）。</p>
 */
public final class TemplateParser {

	private TemplateParser() {
	}

	/**
	 * 解析一个语句元素（{@code <select>}/{@code <insert>} 等）的所有子节点为模板 AST。
	 */
	public static List<TemplateNode> parse(Element statementElement) {
		List<TemplateNode> nodes = new ArrayList<>();
		collectChildren(statementElement, nodes);
		return mergeAdjacentText(nodes);
	}

	private static void collectChildren(Element parent, List<TemplateNode> out) {
		for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
			switch (child.getNodeType()) {
				case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
					// 缩进和换行是 SQL 的分词，不能丢：相邻 <if> 之间的空白一旦被吃掉，
					// 渲染结果就是 "AND a = ?AND b = ?"。容器自己的 trim 负责首尾，中间的原样保留。
					String text = child.getTextContent();
					if (!text.isEmpty()) {
						out.addAll(parseInterpolation(text));
					}
				}
				case Node.ELEMENT_NODE -> parseElement((Element) child, out);
			}
		}
	}

	private static void parseElement(Element element, List<TemplateNode> out) {
		switch (element.getTagName()) {
			case "if" -> out.add(parseIf(element));
			case "choose" -> out.add(parseChoose(element));
			case "foreach" -> out.add(parseForEach(element));
			case "where" -> out.add(TemplateNode.Trim.where(collect(element)));
			case "trim" -> out.add(parseTrim(element));
			default -> throw new TemplateParseException(
					"unsupported template tag <" + element.getTagName()
							+ ">; supported: if, choose, foreach, where, trim");
		}
	}

	/** {@code <trim prefix suffix prefixOverrides suffixOverrides>}，Overrides 用 {@code |} 分隔。 */
	private static TemplateNode.Trim parseTrim(Element element) {
		return new TemplateNode.Trim(element.getAttribute("prefix"), element.getAttribute("suffix"),
				overrides(element.getAttribute("prefixOverrides")), overrides(element.getAttribute("suffixOverrides")),
				collect(element));
	}

	private static List<String> overrides(String attribute) {
		if (attribute.isBlank()) {
			return List.of();
		}
		List<String> tokens = new ArrayList<>();
		for (String token : attribute.split("\\|")) {
			String trimmed = token.trim();
			if (!trimmed.isEmpty()) {
				tokens.add(trimmed);
			}
		}
		return List.copyOf(tokens);
	}

	/**
	 * 扫描文本中的 {@code ${...}} 和 {@code #{...}} 插值，切分为 Text / Variable / Parameter 节点。
	 */
	static List<TemplateNode> parseInterpolation(String text) {
		List<TemplateNode> nodes = new ArrayList<>();
		int cursor = 0;
		while (cursor < text.length()) {
			int dollar = text.indexOf("${", cursor);
			int hash = text.indexOf("#{", cursor);
			int next;
			char kind;
			if (dollar >= 0 && (hash < 0 || dollar < hash)) {
				next = dollar;
				kind = '$';
			}
			else if (hash >= 0) {
				next = hash;
				kind = '#';
			}
			else {
				if (cursor < text.length()) {
					nodes.add(new TemplateNode.Text(text.substring(cursor)));
				}
				break;
			}
			if (next > cursor) {
				nodes.add(new TemplateNode.Text(text.substring(cursor, next)));
			}
			int close = text.indexOf('}', next + 2);
			if (close < 0) {
				throw new TemplateParseException(
						"unclosed " + kind + "{...} in template text near: " + text.substring(next));
			}
			String expression = text.substring(next + 2, close).trim();
			if (expression.isEmpty()) {
				throw new TemplateParseException("empty " + kind + "{} expression in template text");
			}
			if (kind == '$') {
				nodes.add(new TemplateNode.Variable(expression));
			}
			else {
				nodes.add(new TemplateNode.Parameter(parameterName(expression)));
			}
			cursor = close + 1;
		}
		return nodes;
	}

	/**
	 * {@code #{name}} 里允许跟 MyBatis 式属性（{@code #{name, jdbcType=VARCHAR}}），
	 * 解析阶段就截掉，只保留参数名 —— 两条执行路径都按名字取值。
	 */
	private static String parameterName(String expression) {
		int comma = expression.indexOf(',');
		String name = comma < 0 ? expression : expression.substring(0, comma);
		name = name.trim();
		if (name.isEmpty()) {
			throw new TemplateParseException("empty parameter name in #{" + expression + "}");
		}
		return name;
	}

	/** 解析一个容器的子节点并合并相邻文本。 */
	private static List<TemplateNode> collect(Element element) {
		List<TemplateNode> body = new ArrayList<>();
		collectChildren(element, body);
		return mergeAdjacentText(body);
	}

	private static TemplateNode.If parseIf(Element element) {
		String test = element.getAttribute("test");
		if (test.isEmpty()) {
			throw new TemplateParseException("<if> requires a 'test' attribute");
		}
		return new TemplateNode.If(test, collect(element));
	}

	private static TemplateNode.Choose parseChoose(Element element) {
		List<TemplateNode.Choose.WhenBranch> branches = new ArrayList<>();
		List<TemplateNode> otherwise = List.of();
		for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			Element childElement = (Element) child;
			switch (childElement.getTagName()) {
				case "when" -> {
					String test = childElement.getAttribute("test");
					if (test.isEmpty()) {
						throw new TemplateParseException("<when> requires a 'test' attribute");
					}
					List<TemplateNode> body = new ArrayList<>();
					collectChildren(childElement, body);
					branches.add(new TemplateNode.Choose.WhenBranch(test, mergeAdjacentText(body)));
				}
				case "otherwise" -> {
					List<TemplateNode> body = new ArrayList<>();
					collectChildren(childElement, body);
					otherwise = mergeAdjacentText(body);
				}
				default -> throw new TemplateParseException(
						"unexpected child <" + childElement.getTagName()
								+ "> inside <choose>; expected <when> or <otherwise>");
			}
		}
		if (branches.isEmpty()) {
			throw new TemplateParseException("<choose> must have at least one <when> branch");
		}
		return new TemplateNode.Choose(branches, otherwise);
	}

	private static TemplateNode.ForEach parseForEach(Element element) {
		String collection = element.getAttribute("collection");
		String item = element.getAttribute("item");
		String separator = element.getAttribute("separator");
		String open = element.getAttribute("open");
		String close = element.getAttribute("close");
		if (collection.isEmpty()) {
			throw new TemplateParseException("<foreach> requires a 'collection' attribute");
		}
		if (item.isEmpty()) {
			item = "item";
		}
		List<TemplateNode> body = new ArrayList<>();
		collectChildren(element, body);
		// open/close 属于整段，不属于循环体：塞进 body 会让每行都带上一遍括号
		return new TemplateNode.ForEach(item, collection, separator, open, close, mergeAdjacentText(body));
	}

	/**
	 * 合并相邻的 Text 节点，减少 AST 碎片。
	 */
	static List<TemplateNode> mergeAdjacentText(List<TemplateNode> nodes) {
		if (nodes.size() <= 1) {
			return List.copyOf(nodes);
		}
		List<TemplateNode> merged = new ArrayList<>();
		StringBuilder buffer = null;
		for (TemplateNode node : nodes) {
			if (node instanceof TemplateNode.Text text) {
				if (buffer == null) {
					buffer = new StringBuilder();
				}
				buffer.append(text.content());
			}
			else {
				if (buffer != null) {
					merged.add(new TemplateNode.Text(buffer.toString()));
					buffer = null;
				}
				merged.add(node);
			}
		}
		if (buffer != null) {
			merged.add(new TemplateNode.Text(buffer.toString()));
		}
		return merged;
	}

}
