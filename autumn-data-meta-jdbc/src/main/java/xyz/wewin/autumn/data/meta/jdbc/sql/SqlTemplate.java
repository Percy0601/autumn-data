package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import xyz.wewin.autumn.data.common.mapper.MapperDefinition;
import xyz.wewin.autumn.data.common.mapper.TemplateNode;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 动态表的 Mapper 语句模板：一条 XML 语句的 AST + 运行期解释执行。
 *
 * <p>和编译期的 {@code TemplateCodeGenerator} 吃同一棵 {@link TemplateNode} 树、同一套语义，
 * 区别只是「生成 Java」换成了「遍历求值」，所以静态实体和运行期建出来的表用的是
 * <b>同一种 XML 语言</b>，同一段 XML 在两条路径上产出同样的 SQL 文本：</p>
 *
 * <ul>
 *   <li>{@code #{name}} —— 解析阶段就摘成 {@link TemplateNode.Parameter} 节点，解释时一律输出
 *       {@code ?}，值另走 JDBC 绑定，永远不进 SQL 文本，因此没有注入面；</li>
 *   <li>{@code ${name}} —— 只用来展开标识符片段，展开结果必须先过
 *       {@link #isIdentifierFragment(String)} 白名单，引号、分号、注释符、比较符一律拒绝，
 *       所以拼接这条路也挡得住入参劫持；</li>
 *   <li>{@code <if>/<choose>/<where>/<foreach>} —— 条件写在 XML 里，测试表达式既认 MyBatis 的
 *       {@code name != null}，也认 FreeMarker 的 {@code name?? / name?has_content / name?size}
 *       （FreeMarker 的 {@code <#if>} 标签本身不是合法 XML，不兼容）。</li>
 * </ul>
 *
 * <p>关键结构约束：SQL 文本和绑定参数在同一次遍历里按同一顺序产出。写坏模板最多让占位符个数
 * 和条件对不上（渲染结果自洽），不会出现「文本来自模板、参数来自另一处」的静默错位绑定。</p>
 */
public final class SqlTemplate {

	private final String id;

	private final MapperDefinition.StatementType type;

	private final List<TemplateNode> nodes;

	SqlTemplate(MapperDefinition.StatementDefinition statement) {
		this.id = statement.id();
		this.type = statement.type();
		this.nodes = List.copyOf(statement.template());
	}

	/** 语句 id，与 XML 里的 {@code id} 属性一致。 */
	public String id() {
		return this.id;
	}

	/** 语句类型，决定走查询还是更新执行。 */
	public MapperDefinition.StatementType type() {
		return this.type;
	}

	/**
	 * 抽取：解释模板，产出带 {@code ?} 的 SQL、每个占位符引用的参数名与这次的实参。
	 *
	 * <p>这里<b>不做类型转换</b>：值按列类型的收口由 {@link MetaPlanCompiler} 编进槽位，
	 * 同一段模板分支每次执行只解释一次模板、只编译一次形状。</p>
	 *
	 * @param params 模板可见的参数（含 {@code table}/{@code columns} 这类标识符片段）
	 */
	public MarkedSql render(Map<String, ?> params) {
		StringBuilder sql = new StringBuilder();
		RenderContext context = new RenderContext(this.id, params);
		renderNodes(this.nodes, sql, context);
		return new MarkedSql(sql.toString(), context.refs, context.values);
	}

	// ------------------------------------------------------------------
	// 遍历求值
	// ------------------------------------------------------------------

	private static void renderNodes(List<TemplateNode> nodes, StringBuilder out, RenderContext context) {
		for (TemplateNode node : nodes) {
			renderNode(node, out, context);
		}
	}

	private static void renderNode(TemplateNode node, StringBuilder out, RenderContext context) {
		switch (node) {
			case TemplateNode.Text text -> out.append(text.content());
			case TemplateNode.Variable variable -> out.append(variable(variable, context));
			case TemplateNode.Parameter parameter -> {
				out.append('?');
				context.bind(parameter.name());
			}
			case TemplateNode.If ifNode -> {
				if (evaluate(ifNode.expression(), context.params)) {
					renderNodes(ifNode.body(), out, context);
				}
			}
			case TemplateNode.Choose choose -> renderChoose(choose, out, context);
			case TemplateNode.ForEach forEach -> renderForEach(forEach, out, context);
			case TemplateNode.Trim trim -> renderTrim(trim, out, context);
		}
	}

	private static void renderChoose(TemplateNode.Choose choose, StringBuilder out, RenderContext context) {
		for (TemplateNode.Choose.WhenBranch branch : choose.branches()) {
			if (evaluate(branch.expression(), context.params)) {
				renderNodes(branch.body(), out, context);
				return;
			}
		}
		renderNodes(choose.otherwise(), out, context);
	}

	private static void renderForEach(TemplateNode.ForEach forEach, StringBuilder out, RenderContext context) {
		List<?> items = asList(context.params.get(forEach.collectionExpression()));
		if (items.isEmpty()) {
			return;
		}
		out.append(forEach.open());
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) {
				out.append(forEach.separator());
			}
			// 循环变量只在这一层生效：覆盖同名键，渲染完恢复
			Object previous = context.scope(forEach.itemName(), items.get(i));
			renderNodes(forEach.body(), out, context);
			context.restore(forEach.itemName(), previous);
		}
		out.append(forEach.close());
	}

	/** {@code <trim>}：内容剥完首尾冗余词后为空则整段不出现（连同它的槽位一起退回），否则补前后缀。 */
	private static void renderTrim(TemplateNode.Trim trim, StringBuilder out, RenderContext context) {
		int textMark = out.length();
		int slotMark = context.refs.size();
		renderNodes(trim.body(), out, context);
		String body = stripLeading(out.substring(textMark).trim(), trim.prefixOverrides());
		body = stripTrailing(body, trim.suffixOverrides());
		out.setLength(textMark);
		if (body.isEmpty()) {
			context.dropSlots(slotMark);
			return;
		}
		out.append(trim.prefix()).append(body).append(trim.suffix());
	}

	private static String stripLeading(String body, List<String> overrides) {
		for (String token : overrides) {
			if (startsWithWord(body, token)) {
				return body.substring(token.length()).trim();
			}
		}
		return body;
	}

	private static String stripTrailing(String body, List<String> overrides) {
		for (String token : overrides) {
			int at = body.length() - token.length();
			if (at >= 0 && body.regionMatches(true, at, token, 0, token.length())) {
				return body.substring(0, at).trim();
			}
		}
		return body;
	}

	private static boolean startsWithWord(String body, String token) {
		if (!body.regionMatches(true, 0, token, 0, token.length())) {
			return false;
		}
		int after = token.length();
		if (after >= body.length()) {
			return true;
		}
		char next = body.charAt(after);
		return !Character.isLetterOrDigit(next) && next != '_';
	}

	private static String variable(TemplateNode.Variable variable, RenderContext context) {
		String name = variable.expression();
		if (!context.params.containsKey(name)) {
			throw new MetaSqlException("statement '" + context.statementId + "' uses ${" + name
					+ "} but no such value is provided; ${} is only for identifier fragments (available: "
					+ context.params.keySet() + ")");
		}
		Object raw = context.params.get(name);
		String value = raw == null ? "" : String.valueOf(raw);
		if (!isIdentifierFragment(value)) {
			throw new MetaSqlException("${" + name + "} expanded to something that is not an identifier fragment;"
					+ " ${} concatenation only accepts metadata identifiers, got: " + value);
		}
		return value;
	}

	// ------------------------------------------------------------------
	// 标识符白名单
	// ------------------------------------------------------------------

	/**
	 * {@code ${}} 的展开结果必须只由标识符（可带方言引用符）与其列表/分组标点组成。
	 * 引号、分号、注释、比较符、模板标记等一律不合格，因此手写的 {@code ${用户输入}} 打不开。
	 */
	static boolean isIdentifierFragment(String value) {
		if (value.isBlank()) {
			return false;
		}
		boolean identifier = false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
				identifier = true;
				continue;
			}
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == '.' || c == '(' || c == ')'
					|| c == '"' || c == '`' || c == '[' || c == ']') {
				continue;
			}
			return false;
		}
		return identifier;
	}

	// ------------------------------------------------------------------
	// 条件表达式（MyBatis test 写法 + FreeMarker 表达式写法都认）
	// ------------------------------------------------------------------

	static boolean evaluate(String expression, Map<String, ?> params) {
		return evaluate(expression, new ScopedParams(params));
	}

	private static boolean evaluate(String expression, ScopedParams params) {
		String trimmed = expression.trim();
		int split = topLevelOperator(trimmed, "||");
		if (split >= 0) {
			return evaluate(trimmed.substring(0, split), params)
					|| evaluate(trimmed.substring(split + 2), params);
		}
		split = topLevelOperator(trimmed, "&&");
		if (split >= 0) {
			return evaluate(trimmed.substring(0, split), params)
					&& evaluate(trimmed.substring(split + 2), params);
		}
		return evaluateAtom(trimmed, params);
	}

	private static boolean evaluateAtom(String expression, ScopedParams params) {
		String text = expression.trim();
		if (text.startsWith("!")) {
			return !evaluate(text.substring(1), params);
		}
		if (text.startsWith("(") && text.endsWith(")")) {
			return evaluate(text.substring(1, text.length() - 1), params);
		}
		for (String op : new String[] { "==", "!=", ">=", "<=", ">", "<" }) {
			int at = indexOfOutsideQuotes(text, op);
			if (at > 0) {
				return compare(text.substring(0, at).trim(), text.substring(at + op.length()).trim(), op, params);
			}
		}
		return truthy(resolve(text, params));
	}

	private static boolean compare(String left, String right, String op, ScopedParams params) {
		Object a = resolve(left, params);
		Object b = resolve(right, params);
		if (a == null || b == null) {
			return switch (op) {
				case "==" -> a == null && b == null;
				case "!=" -> !(a == null && b == null);
				default -> false;
			};
		}
		if (">=".equals(op) || "<=".equals(op) || ">".equals(op) || "<".equals(op)) {
			int c = compareNumbers(a, b);
			return switch (op) {
				case ">" -> c > 0;
				case ">=" -> c >= 0;
				case "<" -> c < 0;
				default -> c <= 0;
			};
		}
		boolean equal = equalsLoosely(a, b);
		return "==".equals(op) ? equal : !equal;
	}

	private static Object resolve(String token, ScopedParams params) {
		String text = token.trim();
		if (text.isEmpty() || "null".equals(text)) {
			return null;
		}
		if (isQuoted(text)) {
			return text.substring(1, text.length() - 1);
		}
		if (text.endsWith("??")) {
			return params.get(cut(text, "??")) != null;
		}
		if (text.endsWith("?has_content")) {
			return hasContent(params.get(cut(text, "?has_content")));
		}
		if (text.endsWith("?size")) {
			return sizeOf(params.get(cut(text, "?size")));
		}
		if (isNumber(text)) {
			return new BigDecimal(text);
		}
		if (isBooleanLiteral(text)) {
			return Boolean.valueOf(text);
		}
		return params.get(text);
	}

	private static String cut(String text, String suffix) {
		return text.substring(0, text.length() - suffix.length()).trim();
	}

	private static boolean truthy(Object value) {
		if (value == null) {
			return false;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.doubleValue() != 0d;
		}
		if (value instanceof Collection<?> list) {
			return !list.isEmpty();
		}
		return !String.valueOf(value).isEmpty();
	}

	private static boolean hasContent(Object value) {
		return value != null && (!"".equals(String.valueOf(value)))
				&& !(value instanceof Collection<?> list && list.isEmpty());
	}

	private static boolean equalsLoosely(Object a, Object b) {
		if (a instanceof Number && b instanceof Number) {
			return compareNumbers(a, b) == 0;
		}
		return String.valueOf(a).equals(String.valueOf(b));
	}

	private static int compareNumbers(Object a, Object b) {
		BigDecimal x = toNumber(a);
		BigDecimal y = toNumber(b);
		if (x == null || y == null) {
			return String.valueOf(a).compareTo(String.valueOf(b));
		}
		return x.compareTo(y);
	}

	private static BigDecimal toNumber(Object value) {
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
		}
		String text = String.valueOf(value);
		return isNumber(text) ? new BigDecimal(text) : null;
	}

	private static int sizeOf(Object value) {
		if (value instanceof Collection<?> list) {
			return list.size();
		}
		return value == null ? 0 : String.valueOf(value).length();
	}

	/** 返回分隔符下标；在引号或括号内不算顶层。 */
	private static int topLevelOperator(String text, String operator) {
		int depth = 0;
		char quote = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quote != 0) {
				if (c == quote) {
					quote = 0;
				}
				continue;
			}
			if (c == '\'' || c == '"') {
				quote = c;
			}
			else if (c == '(') {
				depth++;
			}
			else if (c == ')') {
				depth--;
			}
			else if (depth == 0 && text.startsWith(operator, i)) {
				return i;
			}
		}
		return -1;
	}

	private static int indexOfOutsideQuotes(String text, String token) {
		int quoteBalance = 0;
		for (int i = 0; i + token.length() <= text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\'' || c == '"') {
				quoteBalance = quoteBalance == 0 ? 1 : 0;
				continue;
			}
			if (quoteBalance == 0 && text.startsWith(token, i)) {
				// "!=" 里的 "==" 不算，"==" 也不会截到 "!=" 的第二个字符
				if ("==".equals(token) && i > 0 && text.charAt(i - 1) == '!') {
					continue;
				}
				return i;
			}
		}
		return -1;
	}

	private static boolean isQuoted(String text) {
		return text.length() >= 2 && ((text.charAt(0) == '\'' && text.endsWith("'"))
				|| (text.charAt(0) == '"' && text.endsWith("\"")));
	}

	private static boolean isNumber(String text) {
		try {
			new BigDecimal(text);
			return true;
		}
		catch (NumberFormatException ex) {
			return false;
		}
	}

	private static boolean isBooleanLiteral(String text) {
		return "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text);
	}

	private static List<?> asList(Object raw) {
		if (raw instanceof Collection<?> list) {
			return List.copyOf(list);
		}
		if (raw instanceof Object[] array) {
			return List.of(array);
		}
		return List.of();
	}

	/** 一次抽取的可变状态：参数视图，以及与 {@code ?} 同序产出的引用名和实参。 */
	private static final class RenderContext {

		private final String statementId;

		private final ScopedParams params;

		private final List<String> refs = new ArrayList<>();

		private final List<Object> values = new ArrayList<>();

		RenderContext(String statementId, Map<String, ?> params) {
			this.statementId = statementId;
			this.params = new ScopedParams(params);
		}

		/** 记一个槽：引用名交给编译器判定它是不是列，实参按原样留给绑定。 */
		void bind(String name) {
			this.refs.add(name);
			this.values.add(this.params.get(name));
		}

		/** 退回本段内容产生的槽位（{@code <trim>} 剥空时用）。 */
		void dropSlots(int mark) {
			this.refs.subList(mark, this.refs.size()).clear();
			this.values.subList(mark, this.values.size()).clear();
		}

		Object scope(String name, Object value) {
			return this.params.put(name, value);
		}

		void restore(String name, Object previous) {
			this.params.restore(name, previous);
		}
	}

	/** 允许 {@code <foreach>} 临时覆盖同名键的参数视图（大小写不敏感，与列名习惯一致）。 */
	private static final class ScopedParams {

		private final Map<String, ?> source;

		private final Map<String, Object> overrides = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

		ScopedParams(Map<String, ?> source) {
			this.source = source;
		}

		boolean containsKey(String key) {
			return this.overrides.containsKey(key) || this.source.containsKey(key);
		}

		Object get(String key) {
			return this.overrides.containsKey(key) ? this.overrides.get(key) : this.source.get(key);
		}

		Object put(String key, Object value) {
			Object previous = get(key);
			this.overrides.put(key, value);
			return previous;
		}

		void restore(String key, Object previous) {
			this.overrides.put(key, previous);
		}

		Set<String> keySet() {
			LinkedHashSet<String> keys = new LinkedHashSet<>(this.source.keySet());
			keys.addAll(this.overrides.keySet());
			return keys;
		}
	}

}
