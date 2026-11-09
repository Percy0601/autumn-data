package xyz.wewin.autumn.data.jdbc.compiler.template;

import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 模板体解析器：把 XML 语句元素内的混合内容（文本 + 控制标签）解析成 {@link TemplateNode} AST。
 *
 * <p>支持的 XML 标签：</p>
 * <ul>
 *   <li>{@code <if test="expr">...</if>} — 条件块</li>
 *   <li>{@code <choose>/<when test="...">/<otherwise>} — 多分支</li>
 *   <li>{@code <foreach collection="..." item="..." separator="..." open="(" close=")">...</foreach>} — 循环</li>
 * </ul>
 *
 * <p>文本内容中的插值语法：</p>
 * <ul>
 *   <li>{@code ${name}} — 变量插值（原样拼入 SQL，用于表名/列名/排序方向）</li>
 *   <li>{@code #{name}} — 参数占位符（生成 {@code ?}，运行期 JDBC 绑定）</li>
 * </ul>
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
					String text = child.getTextContent();
					if (!text.isBlank()) {
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
			default -> throw new TemplateParseException(
					"unsupported template tag <" + element.getTagName()
							+ ">; supported: if, choose, foreach");
		}
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
				nodes.add(new TemplateNode.Parameter(expression));
			}
			cursor = close + 1;
		}
		return nodes;
	}

	private static TemplateNode.If parseIf(Element element) {
		String test = element.getAttribute("test");
		if (test.isEmpty()) {
			throw new TemplateParseException("<if> requires a 'test' attribute");
		}
		List<TemplateNode> body = new ArrayList<>();
		collectChildren(element, body);
		return new TemplateNode.If(test, mergeAdjacentText(body));
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
		List<TemplateNode> mergedBody = mergeAdjacentText(body);
		if (!open.isEmpty()) {
			mergedBody = new ArrayList<>(mergedBody);
			mergedBody.addFirst(new TemplateNode.Text(open));
		}
		if (!close.isEmpty()) {
			mergedBody = new ArrayList<>(mergedBody);
			mergedBody.addLast(new TemplateNode.Text(close));
		}
		return new TemplateNode.ForEach(item, collection, separator, mergedBody);
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
