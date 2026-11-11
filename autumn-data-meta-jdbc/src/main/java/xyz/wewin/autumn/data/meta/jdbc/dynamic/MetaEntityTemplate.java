package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 用 FreeMarker 把 {@link MetaTable} 渲染成一份 Java 实体源码。
 *
 * <p>模板只负责「形状」（字段、getter/setter、逐列 read、按列名 switch、元属性视图），
 * 所有标识符与字面量在这一层先做安全处理：Java 关键字、非法字符、引号、注释符都在进模板前收口，
 * 模板里不会出现需要二次拼装的代码片段。</p>
 *
 * <p>结构指纹决定包名：表加了一列，指纹就变，生成的是另一个包下的新类——旧类还能被
 * 正在执行的查询继续使用，不会出现同名类被重定义或新旧实例互相 ClassCastException。</p>
 */
public final class MetaEntityTemplate {

	private static final String TEMPLATE_NAME = "meta-class.ftl";

	private static final String TEMPLATE_BASE = "xyz/wewin/autumn/data/meta/jdbc/dynamic";

	private static final String GENERATED_PACKAGE = "xyz.wewin.autumn.data.meta.jdbc.generated";

	private static final Set<String> JAVA_KEYWORDS = Set.of(
			"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
			"continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
			"for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
			"new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
			"super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "true", "false", "null", "record", "sealed", "var", "yield", "permits");

	/**
	 * {@link MetaClass} 已经占用的无参方法名。列名叫 {@code name}/{@code properties}/{@code namespace}
	 * 时，按列名直出的 {@code getName()}/{@code getProperties()} 会覆写基类契约：轻则
	 * {@code setName(TABLE_NAME)} 被劫持、表名写进业务列，重则因返回类型不兼容直接编译失败。
	 * 这些列名必须避让；{@code get(String)} / {@code set(String, Object)} 是刻意覆写，不在此列。
	 */
	private static final Set<String> BASE_CONTRACT_METHODS = Set.of(
			"getName", "setName", "getNamespace", "setNamespace",
			"getMetaType", "setMetaType", "getProperties", "setProperties",
			"toMap", "toJson", "toString", "hashCode", "getClass",
			"clone", "finalize", "notify", "notifyAll", "wait");

	private static final Configuration FREEMARKER = configuration();

	private MetaEntityTemplate() {
	}

	/** 渲染结果：源码 + 它声明的类名（编译器按这个名字落盘与加载）。 */
	public record Source(String packageName, String className, String qualifiedName, String fingerprint,
			String java) {
	}

	/** 按表结构生成源码。 */
	public static Source render(MetaTable table) {
		String fingerprint = fingerprint(table);
		String packageName = GENERATED_PACKAGE + ".v" + fingerprint;
		String className = className(table.name());
		Map<String, Object> model = new LinkedHashMap<>();
		model.put("packageName", packageName);
		model.put("className", className);
		model.put("tableNameLiteral", literal(table.name()));
		model.put("schemaLiteral", table.schema() == null ? "null" : literal(table.schema()));
		model.put("fingerprint", fingerprint);
		model.put("fingerprintLiteral", literal(fingerprint));
		model.put("columnCount", table.columns().size());
		model.put("columns", columns(table.columns()));
		try {
			Template template = FREEMARKER.getTemplate(TEMPLATE_NAME);
			StringWriter out = new StringWriter(4096);
			template.process(model, out);
			return new Source(packageName, className, packageName + "." + className, fingerprint, out.toString());
		}
		catch (IOException | TemplateException ex) {
			throw new MetaSqlException("failed to render entity template for table '" + table.name() + "'", ex);
		}
	}

	private static Configuration configuration() {
		Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
		cfg.setClassLoaderForTemplateLoading(MetaEntityTemplate.class.getClassLoader(), TEMPLATE_BASE);
		cfg.setDefaultEncoding(StandardCharsets.UTF_8.name());
		cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
		cfg.setLogTemplateExceptions(false);
		cfg.setWrapUncheckedExceptions(true);
		return cfg;
	}

	// ------------------------------------------------------------------
	// 模板数据
	// ------------------------------------------------------------------

	private static List<Map<String, Object>> columns(List<MetaProperty> columns) {
		Set<String> usedFields = new HashSet<>();
		List<Map<String, Object>> result = new ArrayList<>(columns.size());
		for (int i = 0; i < columns.size(); i++) {
			MetaProperty column = columns.get(i);
			String name = column.getName();
			String field = uniqueField(name, usedFields);
			String javaType = column.getType();
			String reader = readerOf(javaType);
			String fieldType = "asObject".equals(reader) ? "java.lang.Object" : javaType;
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("nameLiteral", literal(name));
			item.put("lowerLiteral", literal(name.toLowerCase(Locale.ROOT)));
			item.put("field", field);
			item.put("getter", "get" + capitalize(field));
			item.put("setter", "set" + capitalize(field));
			item.put("javaType", fieldType);
			item.put("reader", reader);
			item.put("typeLiteral", literal(javaType == null ? "java.lang.Object" : javaType));
			item.put("jdbcType", Integer.toString(column.getJdbcType()));
			item.put("sqlTypeLiteral", column.getSqlTypeName() == null ? "null" : literal(column.getSqlTypeName()));
			item.put("sqlTypeNameForComment", commentSafe(column.getSqlTypeName()));
			item.put("defaultLiteral", column.getDefaultValue() == null ? "null" : literal(column.getDefaultValue()));
			item.put("nullable", Boolean.toString(column.isNullable()));
			item.put("primaryKey", Boolean.toString(column.isPrimaryKey()));
			item.put("autoIncrement", Boolean.toString(column.isAutoIncrement()));
			item.put("hasNext", i < columns.size() - 1);
			result.add(item);
		}
		return result;
	}

	/** 字段类型 → 读方向转换方法；未知类型不做强转，字段声明成 Object。 */
	private static String readerOf(String javaType) {
		if (javaType == null) {
			return "asObject";
		}
		return switch (javaType) {
			case "java.lang.Long" -> "asLong";
			case "java.lang.Integer" -> "asInt";
			case "java.math.BigDecimal" -> "asBigDecimal";
			case "java.lang.Boolean" -> "asBoolean";
			case "java.lang.Double" -> "asDouble";
			case "java.time.LocalDate" -> "asLocalDate";
			case "java.time.LocalTime" -> "asLocalTime";
			case "java.time.LocalDateTime" -> "asLocalDateTime";
			case "java.lang.String" -> "asString";
			default -> "asObject";
		};
	}

	/** 结构指纹：列顺序、名字、类型、主键/自增/可空都参与，改名不改结构也能复用旧类。 */
	static String fingerprint(MetaTable table) {
		StringBuilder raw = new StringBuilder(256);
		raw.append(table.name()).append('|').append(table.schema()).append('|');
		for (MetaProperty column : table.columns()) {
			raw.append(column.getName()).append(':')
					.append(column.getJdbcType()).append(':')
					.append(column.getType()).append(':')
					.append(column.getSqlTypeName()).append(':')
					.append(column.isNullable() ? '1' : '0').append(':')
					.append(column.isPrimaryKey() ? '1' : '0').append(':')
					.append(column.isAutoIncrement() ? '1' : '0').append(';');
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(24);
			for (int i = 0; i < 12; i++) {
				hex.append(Character.forDigit((digest[i] >>> 4) & 0xF, 16));
				hex.append(Character.forDigit(digest[i] & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new MetaSqlException("SHA-256 is unavailable", ex);
		}
	}

	// ------------------------------------------------------------------
	// 标识符与字面量
	// ------------------------------------------------------------------

	private static String className(String tableName) {
		StringBuilder name = new StringBuilder();
		boolean upperNext = true;
		for (int i = 0; i < tableName.length(); i++) {
			char c = tableName.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_') {
				if (c == '_') {
					upperNext = true;
					continue;
				}
				name.append(upperNext ? Character.toUpperCase(c) : c);
				upperNext = false;
			}
			else {
				upperNext = true;
			}
		}
		if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
			name.insert(0, "T");
		}
		String candidate = name.toString();
		return JAVA_KEYWORDS.contains(candidate) ? candidate + "Entity" : candidate;
	}

	private static String uniqueField(String column, Set<String> used) {
		StringBuilder name = new StringBuilder();
		boolean upperNext = false;
		for (int i = 0; i < column.length(); i++) {
			char c = column.charAt(i);
			if (Character.isLetterOrDigit(c) || c == '_') {
				if (c == '_') {
					upperNext = true;
					continue;
				}
				if (name.isEmpty()) {
					name.append(Character.toLowerCase(c));
				}
				else {
					name.append(upperNext ? Character.toUpperCase(c) : c);
				}
				upperNext = false;
			}
			else {
				upperNext = true;
			}
		}
		if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
			name.insert(0, '_');
		}
		String base = JAVA_KEYWORDS.contains(name.toString()) ? name + "_" : name.toString();
		// 撞上 MetaClass 契约的列改叫 xxxColumn：按列名读写仍走 switch，业务语义不变，
		// 只是类型化 getter 换个名字，避免覆写基类方法。
		String candidate = collidesWithBaseContract(base) ? base + "Column" : base;
		int suffix = 2;
		while (collidesWithBaseContract(candidate) || used.contains(candidate)) {
			candidate = base + "Column" + suffix++;
		}
		used.add(candidate);
		return candidate;
	}

	private static boolean collidesWithBaseContract(String field) {
		String cap = capitalize(field);
		return BASE_CONTRACT_METHODS.contains("get" + cap)
				|| BASE_CONTRACT_METHODS.contains("set" + cap)
				|| BASE_CONTRACT_METHODS.contains(field);
	}

	private static String capitalize(String name) {
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	/** Java 字符串字面量（含引号），转义反斜杠、引号、换行与控制字符。 */
	private static String literal(String value) {
		StringBuilder out = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

	/** 注释内容不能带换行或注释结束符，否则手写的类型名能把生成源码截断。 */
	private static String commentSafe(String value) {
		if (value == null) {
			return "unknown";
		}
		return value.replaceAll("[^A-Za-z0-9_ ()\\-.,]", "_");
	}
}
