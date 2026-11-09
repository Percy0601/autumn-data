package xyz.wewin.autumn.data.jdbc.compiler.mapper;

import java.util.List;

import xyz.wewin.autumn.data.jdbc.compiler.mapper.MapperDefinition.StatementDefinition;
import xyz.wewin.autumn.data.jdbc.compiler.mapper.MapperDefinition.StatementType;
import xyz.wewin.autumn.data.jdbc.compiler.template.TemplateNode;

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
			generateNode(out, node, "\t\t");
		}

		out.append("\t\treturn new ").append(SQL_BUILD_FQN).append("(sql.toString(), args.toArray());\n");
		out.append("\t}\n");
	}

	private static void generateNode(StringBuilder out, TemplateNode node, String indent) {
		switch (node) {
			case TemplateNode.Text text -> generateText(out, text, indent);
			case TemplateNode.Variable variable -> generateVariable(out, variable, indent);
			case TemplateNode.Parameter parameter -> generateParameter(out, parameter, indent);
			case TemplateNode.If ifNode -> generateIf(out, ifNode, indent);
			case TemplateNode.Choose choose -> generateChoose(out, choose, indent);
			case TemplateNode.ForEach forEach -> generateForEach(out, forEach, indent);
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

	private static void generateIf(StringBuilder out, TemplateNode.If ifNode, String indent) {
		String condition = translateCondition(ifNode.expression());
		out.append(indent).append("if (").append(condition).append(") {\n");
		for (TemplateNode child : ifNode.body()) {
			generateNode(out, child, indent + "\t");
		}
		out.append(indent).append("}\n");
	}

	private static void generateChoose(StringBuilder out, TemplateNode.Choose choose, String indent) {
		for (int i = 0; i < choose.branches().size(); i++) {
			TemplateNode.Choose.WhenBranch branch = choose.branches().get(i);
			String condition = translateCondition(branch.expression());
			String keyword = (i == 0) ? "if" : "else if";
			out.append(indent).append(keyword).append(" (").append(condition).append(") {\n");
			for (TemplateNode child : branch.body()) {
				generateNode(out, child, indent + "\t");
			}
			out.append(indent).append("}\n");
		}
		if (!choose.otherwise().isEmpty()) {
			out.append(indent).append("else {\n");
			for (TemplateNode child : choose.otherwise()) {
				generateNode(out, child, indent + "\t");
			}
			out.append(indent).append("}\n");
		}
	}

	private static void generateForEach(StringBuilder out, TemplateNode.ForEach forEach, String indent) {
		String collection = forEach.collectionExpression();
		String item = forEach.itemName();
		String separator = forEach.separator();

		// 生成循环代码：先取集合，判空，遍历拼 SQL
		String collVar = "__" + collection;
		String indexVar = "__i";

		out.append(indent).append("Collection<?> ").append(collVar);
		out.append(" = (Collection<?>) params.get(\"").append(escapeJava(collection)).append("\");\n");
		out.append(indent).append("if (").append(collVar).append(" != null && !").append(collVar).append(".isEmpty()) {\n");

		String innerIndent = indent + "\t";
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
			generateForEachBodyNode(out, child, bodyIndent, item);
		}

		out.append(innerIndent).append("}\n");
		out.append(indent).append("}\n");
	}

	/**
	 * foreach 循环体内的节点生成：把对 item 变量的引用翻译为直接取值。
	 */
	private static void generateForEachBodyNode(StringBuilder out, TemplateNode node, String indent, String itemName) {
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
			case TemplateNode.If ifNode -> generateIf(out, ifNode, indent);
			case TemplateNode.Choose choose -> generateChoose(out, choose, indent);
			case TemplateNode.ForEach forEach -> generateForEach(out, forEach, indent);
		}
	}

	/**
	 * 把模板条件表达式翻译成 Java 布尔表达式。
	 *
	 * <p>支持的语法：</p>
	 * <ul>
	 *   <li>{@code name != null} → {@code params.get("name") != null}</li>
	 *   <li>{@code name == null} → {@code params.get("name") == null}</li>
	 *   <li>{@code name??}（FreeMarker 风格）→ {@code params.get("name") != null}</li>
	 *   <li>{@code name == 'value'} → {@code "value".equals(params.get("name"))}</li>
	 *   <li>{@code name != ''} → {@code params.get("name") != null && !params.get("name").toString().isEmpty()}</li>
	 *   <li>其他 → 原样输出（用户自行保证合法性）</li>
	 * </ul>
	 */
	static String translateCondition(String expression) {
		String trimmed = expression.trim();

		// FreeMarker 风格：name?? → name != null
		if (trimmed.endsWith("??")) {
			String var = trimmed.substring(0, trimmed.length() - 2).trim();
			return "params.get(\"" + escapeJava(var) + "\") != null";
		}

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
