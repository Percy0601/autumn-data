package xyz.wewin.autumn.data.jdbc.compiler.mapper;

import java.util.List;

import xyz.wewin.autumn.data.common.mapper.MapperDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementType;
import xyz.wewin.autumn.data.common.mapper.TemplateNode;

/**
 * 模板代码生成器：把 {@link MapperDefinition} 翻译成 Java 源码。
 *
 * <p>生成的类包含一组静态方法，每个方法对应 XML 里的一条语句（{@code <select>}/{@code <insert>} 等）。
 * 方法签名统一为 {@code static SqlBuild xxx(Map<String, Object> params)}，内部用 {@code StringBuilder}
 * 拼 SQL、{@code List<Object>} 收集绑定参数。</p>
 *
 * <p>生成代码示例（对应 {@code <if test="name != null">AND name LIKE #{name}</if>}）：</p>
 * <pre>{@code
 * if (params.get("name") != null) {
 *     sql.append(" AND name LIKE ?");
 *     args.add(params.get("name"));
 * }
 * }</pre>
 */
public final class TemplateCodeGenerator {

	private static final String SQL_BUILD_FQN = "xyz.wewin.autumn.data.meta.jdbc.SqlBuild";

	/**
	 * 生成类自带的结构辅助：{@code <where>} 去掉打头的 AND/OR。
	 * 动态层解释器用同样的规则。
	 */
	private static final String HELPERS = """
			private static void applyTrim(StringBuilder sql, List<Object> args, int textMark, int argMark,
					String prefix, String suffix, String[] prefixOverrides, String[] suffixOverrides) {
				String body = stripLeading(sql.substring(textMark).trim(), prefixOverrides);
				body = stripTrailing(body, suffixOverrides);
				sql.setLength(textMark);
				if (body.isEmpty()) {
					args.subList(argMark, args.size()).clear();
					return;
				}
				sql.append(prefix).append(body).append(suffix);
			}

			private static String stripLeading(String body, String[] overrides) {
				for (String token : overrides) {
					if (startsWithWord(body, token)) {
						return body.substring(token.length()).trim();
					}
				}
				return body;
			}

			private static String stripTrailing(String body, String[] overrides) {
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

			""";

	private TemplateCodeGenerator() {
	}

	/**
	 * 为一个 Mapper XML 生成完整的 Java 源码。
	 *
	 * @param mapper 解析后的 Mapper 定义
	 * @param packageName 生成类所在的包名
	 * @return Java 源码字符串
	 */
	public static String generate(MapperDefinition mapper, String packageName) {
		StringBuilder out = new StringBuilder(1024);
		String simpleClassName = simpleClassName(mapper.namespace());
		String qualifiedName = packageName.isEmpty() ? simpleClassName : packageName + "." + simpleClassName;

		if (!packageName.isEmpty()) {
			out.append("package ").append(packageName).append(";\n\n");
		}
		out.append("import java.util.ArrayList;\n");
		out.append("import java.util.Collection;\n");
		out.append("import java.util.List;\n");
		out.append("import java.util.Map;\n\n");
		out.append("import ").append(SQL_BUILD_FQN).append(";\n\n");
		out.append("/**\n");
		out.append(" * 编译期由 autumn-data-jdbc-compiler 从 Mapper XML 自动生成。\n");
		out.append(" * <p>源文件：{@code ").append(mapper.namespace()).append("}</p>\n");
		out.append(" */\n");
		out.append("public final class ").append(simpleClassName).append(" {\n\n");
		out.append("\tprivate ").append(simpleClassName).append("() {}\n");
		out.append(HELPERS);

		for (StatementDefinition statement : mapper.statements()) {
			out.append('\n');
			generateMethod(out, statement);
		}

		out.append("\n}\n");
		return out.toString();
	}

	private static void generateMethod(StringBuilder out, StatementDefinition statement) {
		String methodName = statement.id();
		out.append("\tpublic static ").append(SQL_BUILD_FQN).append(' ').append(methodName);
		out.append("(Map<String, Object> params) {\n");
		out.append("\t\tStringBuilder sql = new StringBuilder();\n");
		out.append("\t\tList<Object> args = new ArrayList<>();\n");

		for (TemplateNode node : statement.template()) {
			generateNode(out, node, "\t\t", new int[1]);
		}

		out.append("\t\treturn new ").append(SQL_BUILD_FQN).append("(sql.toString(), args.toArray());\n");
		out.append("\t}\n");
	}

	/** 语句体生成：{@code seq} 只用于给 {@code <where>} 的临时局部变量取不重名的标识。 */
	private static void generateNode(StringBuilder out, TemplateNode node, String indent, int[] seq) {
		switch (node) {
			case TemplateNode.Text text -> generateText(out, text, indent);
			case TemplateNode.Variable variable -> generateVariable(out, variable, indent);
			case TemplateNode.Parameter parameter -> generateParameter(out, parameter, indent);
			case TemplateNode.If ifNode -> generateIf(out, ifNode, indent, seq);
			case TemplateNode.Choose choose -> generateChoose(out, choose, indent, seq);
			case TemplateNode.ForEach forEach -> generateForEach(out, forEach, indent, seq);
			case TemplateNode.Trim trim -> generateTrim(out, trim, indent, seq);
		}
	}

	private static void generateText(StringBuilder out, TemplateNode.Text text, String indent) {
		String escaped = escapeJava(text.content());
		out.append(indent).append("sql.append(\"").append(escaped).append("\");\n");
	}

	private static void generateVariable(StringBuilder out, TemplateNode.Variable variable, String indent) {
		// ${expr}：编译期已知值直接内联到 SQL（表名、列名等）
		String expr = variable.expression();
		out.append(indent).append("sql.append(String.valueOf(params.get(\"").append(escapeJava(expr));
		out.append("\")));\n");
	}

	private static void generateParameter(StringBuilder out, TemplateNode.Parameter parameter, String indent) {
		// #{name}：运行期绑定参数，生成 ? 占位符
		String name = parameter.name();
		out.append(indent).append("sql.append(\"?\");\n");
		out.append(indent).append("args.add(params.get(\"").append(escapeJava(name)).append("\"));\n");
	}

	private static void generateIf(StringBuilder out, TemplateNode.If ifNode, String indent, int[] seq) {
		String condition = translateCondition(ifNode.expression());
		out.append(indent).append("if (").append(condition).append(") {\n");
		for (TemplateNode child : ifNode.body()) {
			generateNode(out, child, indent + "\t", seq);
		}
		out.append(indent).append("}\n");
	}

	/**
	 * {@code <where>}：子节点先照常写进 {@code sql}，再把这段文本取回、截断，
	 * 非空时补 {@code WHERE} 并去掉打头的 AND/OR。参数的入列顺序不受影响。
	 */
	private static void generateTrim(StringBuilder out, TemplateNode.Trim trim, String indent, int[] seq) {
		int index = seq[0]++;
		String mark = "__trimMark" + index;
		String argMark = "__trimArgMark" + index;
		out.append(indent).append("int ").append(mark).append(" = sql.length();\n");
		out.append(indent).append("int ").append(argMark).append(" = args.size();\n");
		for (TemplateNode child : trim.body()) {
			generateNode(out, child, indent, seq);
		}
		out.append(indent).append("applyTrim(sql, args, ").append(mark).append(", ").append(argMark);
		out.append(", \"").append(escapeJava(trim.prefix())).append("\", \"")
				.append(escapeJava(trim.suffix())).append("\"");
		out.append(", ").append(overrideArray(trim.prefixOverrides()));
		out.append(", ").append(overrideArray(trim.suffixOverrides())).append(");\n");
	}

	private static String overrideArray(List<String> overrides) {
		if (overrides.isEmpty()) {
			return "new String[0]";
		}
		StringBuilder out = new StringBuilder("new String[] { ");
		for (int i = 0; i < overrides.size(); i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append('"').append(escapeJava(overrides.get(i))).append('"');
		}
		return out.append(" }").toString();
	}

	private static void generateChoose(StringBuilder out, TemplateNode.Choose choose, String indent, int[] seq) {
		for (int i = 0; i < choose.branches().size(); i++) {
			TemplateNode.Choose.WhenBranch branch = choose.branches().get(i);
			String condition = translateCondition(branch.expression());
			String keyword = (i == 0) ? "if" : "else if";
			out.append(indent).append(keyword).append(" (").append(condition).append(") {\n");
			for (TemplateNode child : branch.body()) {
				generateNode(out, child, indent + "\t", seq);
			}
			out.append(indent).append("}\n");
		}
		if (!choose.otherwise().isEmpty()) {
			out.append(indent).append("else {\n");
			for (TemplateNode child : choose.otherwise()) {
				generateNode(out, child, indent + "\t", seq);
			}
			out.append(indent).append("}\n");
		}
	}

	private static void generateForEach(StringBuilder out, TemplateNode.ForEach forEach, String indent, int[] seq) {
		String collection = forEach.collectionExpression();
		String item = forEach.itemName();
		String separator = forEach.separator();

		// 生成循环代码：先取集合，判空，遍历拼 SQL
		String collVar = "__" + collection + seq[0];
		String indexVar = "__i" + seq[0]++;

		out.append(indent).append("Collection<?> ").append(collVar);
		out.append(" = (Collection<?>) params.get(\"").append(escapeJava(collection)).append("\");\n");
		out.append(indent).append("if (").append(collVar).append(" != null && !").append(collVar).append(".isEmpty()) {\n");

		String innerIndent = indent + "\t";
		// open/close 包住整段，只在集合非空时各出现一次
		if (!forEach.open().isEmpty()) {
			out.append(innerIndent).append("sql.append(\"").append(escapeJava(forEach.open())).append("\");\n");
		}
		if (!separator.isEmpty()) {
			out.append(innerIndent).append("int ").append(indexVar).append(" = 0;\n");
		}
		out.append(innerIndent).append("for (Object ").append(item).append(" : ").append(collVar).append(") {\n");

		String bodyIndent = innerIndent + "\t";
		if (!separator.isEmpty()) {
			out.append(bodyIndent).append("if (").append(indexVar).append(" > 0) {\n");
			out.append(bodyIndent).append("\tsql.append(\"").append(escapeJava(separator)).append("\");\n");
			out.append(bodyIndent).append("}\n");
			out.append(bodyIndent).append(indexVar).append("++;\n");
		}

		// 循环体：把 item 变量临时注入 params，生成子节点代码
		// 简化处理：循环体内只支持 #{itemName} 和 ${itemName}
		for (TemplateNode child : forEach.body()) {
			generateForEachBodyNode(out, child, bodyIndent, item, seq);
		}

		out.append(innerIndent).append("}\n");
		if (!forEach.close().isEmpty()) {
			out.append(innerIndent).append("sql.append(\"").append(escapeJava(forEach.close())).append("\");\n");
		}
		out.append(indent).append("}\n");
	}

	/**
	 * foreach 循环体内的节点生成：把对 item 变量的引用翻译为直接取值。
	 */
	private static void generateForEachBodyNode(StringBuilder out, TemplateNode node, String indent, String itemName,
			int[] seq) {
		switch (node) {
			case TemplateNode.Text text -> generateText(out, text, indent);
			case TemplateNode.Variable variable -> {
				if (variable.expression().equals(itemName)) {
					out.append(indent).append("sql.append(String.valueOf(").append(itemName).append("));\n");
				}
				else {
					generateVariable(out, variable, indent);
				}
			}
			case TemplateNode.Parameter parameter -> {
				if (parameter.name().equals(itemName)) {
					out.append(indent).append("sql.append(\"?\");\n");
					out.append(indent).append("args.add(").append(itemName).append(");\n");
				}
				else {
					generateParameter(out, parameter, indent);
				}
			}
			case TemplateNode.If ifNode -> generateIf(out, ifNode, indent, seq);
			case TemplateNode.Choose choose -> generateChoose(out, choose, indent, seq);
			case TemplateNode.ForEach forEach -> generateForEach(out, forEach, indent, seq);
			case TemplateNode.Trim trim -> generateTrim(out, trim, indent, seq);
		}
	}

	/**
	 * 把模板条件表达式翻译成 Java 布尔表达式。
	 *
	 * <p>支持的语法：</p>
	 * <ul>
	 *   <li>{@code name != null} → {@code params.get("name") != null}</li>
	 *   <li>{@code name == null} → {@code params.get("name") == null}</li>
	 *   <li>{@code name == 'value'} → {@code "value".equals(params.get("name"))}</li>
	 *   <li>{@code name != ''} → {@code params.get("name") != null && !params.get("name").toString().isEmpty()}</li>
	 *   <li>其他 → 原样输出（用户自行保证合法性）</li>
	 * </ul>
	 */
	static String translateCondition(String expression) {
		String trimmed = expression.trim();

		// name != null / name == null
		if (trimmed.contains("!= null")) {
			String var = trimmed.replace("!= null", "").trim();
			return "params.get(\"" + escapeJava(var) + "\") != null";
		}
		if (trimmed.contains("== null")) {
			String var = trimmed.replace("== null", "").trim();
			return "params.get(\"" + escapeJava(var) + "\") == null";
		}

		// name != '' → 非空串
		if (trimmed.contains("!= ''") || trimmed.contains("!= \"\"")) {
			String var = trimmed.replaceAll("!=\\s*['\"]{2}", "").trim();
			return "params.get(\"" + escapeJava(var)
					+ "\") != null && !params.get(\"" + escapeJava(var) + "\").toString().isEmpty()";
		}

		// name == 'value' → 等值比较
		int eqIdx = trimmed.indexOf("==");
		if (eqIdx > 0) {
			String var = trimmed.substring(0, eqIdx).trim();
			String value = trimmed.substring(eqIdx + 2).trim();
			if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
				String literal = value.substring(1, value.length() - 1);
				return "\"" + escapeJava(literal) + "\".equals(params.get(\"" + escapeJava(var) + "\"))";
			}
		}

		// 兜底：原样输出，把裸变量名替换为 params.get(...)
		return translateBareExpression(trimmed);
	}

	/**
	 * 兜底翻译：把表达式中的裸变量名替换为 {@code params.get("var")}。
	 * 简单实现——只处理 {@code var} 和 {@code !var} 两种形态。
	 */
	private static String translateBareExpression(String expression) {
		if (expression.startsWith("!")) {
			String var = expression.substring(1).trim();
			return "Boolean.FALSE.equals(params.get(\"" + escapeJava(var) + "\"))";
		}
		return "params.get(\"" + escapeJava(expression) + "\") != null";
	}

	private static String simpleClassName(String namespace) {
		int lastDot = namespace.lastIndexOf('.');
		String base = (lastDot >= 0) ? namespace.substring(lastDot + 1) : namespace;
		return base + "Sql";
	}

	static String escapeJava(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '"' -> sb.append("\\\"");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}

}
