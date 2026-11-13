package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;

/**
 * 把 {@link MetaTable} 直接拼成一份 Java 实体源码。
 *
 * <p>不再经过模板引擎：字段、getter/setter、逐列 {@code ResultSet} 读取、按列名 switch、
 * 元属性视图都是 Java 代码里写死的形状，标识符与字面量在进入 {@link StringBuilder} 之前
 * 就完成安全处理（Java 关键字、非法字符、引号、注释符），不存在「模板语法」这一层，
 * 也就没有模板文件缺失、模板被改坏、模板引擎版本漂移这几类运行期问题。</p>
 *
 * <p>结构指纹决定包名：表加了一列，指纹就变，生成的是另一个包下的新类——旧类还能被
 * 正在执行的查询继续使用，不会出现同名类被重定义或新旧实例互相 ClassCastException。</p>
 */
public final class MetaEntitySource {

	private static final String GENERATED_PACKAGE = "xyz.wewin.autumn.data.meta.jdbc.entity.generated";

	private static final Set<String> JAVA_KEYWORDS = Set.of(
			"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
			"continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
			"for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
			"new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
			"super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "true", "false", "null", "record", "sealed", "var", "yield", "permits");

	/**
	 * {@link xyz.wewin.autumn.data.common.meta.MetaClass} 已经占用的无参方法名。列名叫
	 * {@code name}/{@code properties}/{@code namespace} 时，按列名直出的
	 * {@code getName()}/{@code getProperties()} 会覆写基类契约：轻则
	 * {@code setName(TABLE_NAME)} 被劫持、表名写进业务列，重则因返回类型不兼容直接编译失败。
	 * 这些列名必须避让；{@code get(String)} / {@code set(String, Object)} 是刻意覆写，不在此列。
	 */
	private static final Set<String> BASE_CONTRACT_METHODS = Set.of(
			"getName", "setName", "getNamespace", "setNamespace",
			"getMetaType", "setMetaType", "getProperties", "setProperties",
			"toMap", "toJson", "toString", "hashCode", "getClass",
			"clone", "finalize", "notify", "notifyAll", "wait");

	/** 渲染结果：源码 + 它声明的类名（编译器按这个名字落盘与加载）。 */
	public record Source(String packageName, String className, String qualifiedName, String fingerprint,
			String java) {
	}

	/** 按表结构生成源码。 */
	public Source render(MetaTable table) {
		String fingerprint = table.fingerprint();
		String packageName = GENERATED_PACKAGE + ".v" + fingerprint;
		String className = className(table.name());
		List<Column> columns = columns(table.columns());
		StringBuilder java = new StringBuilder(4096 + columns.size() * 320);
		java.append("package ").append(packageName).append(";\n\n");
		java.append("// generated-by: autumn-data-meta-jdbc (MetaEntitySource + javax.tools.JavaCompiler)\n");
		java.append("// structure-id: ").append(fingerprint).append('\n');
		java.append("// source table: ").append(literal(table.name())).append('\n');
		java.append("// 本类由运行期表结构生成：字段/getter/setter/结果集映射全部是静态调用，逐行零反射。\n");
		java.append("import java.sql.ResultSet;\n");
		java.append("import java.sql.SQLException;\n");
		java.append("import java.util.ArrayList;\n");
		java.append("import java.util.List;\n");
		java.append("import java.util.Locale;\n");
		java.append("import java.util.Map;\n");
		java.append("import java.util.function.Supplier;\n\n");
		java.append("import xyz.wewin.autumn.data.common.meta.MetaClass;\n");
		java.append("import xyz.wewin.autumn.data.common.meta.MetaProperty;\n");
		java.append("import xyz.wewin.autumn.data.common.meta.MetaType;\n");
		java.append("import xyz.wewin.autumn.data.common.meta.MetaValueReader;\n");
		java.append("import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityColumns;\n");
		java.append("import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityReader;\n\n");
		java.append("public final class ").append(className).append(" extends MetaClass {\n\n");
		java.append("    public static final String TABLE_NAME = ").append(literal(table.name())).append(";\n");
		java.append("    public static final String TABLE_SCHEMA = ")
				.append(table.schema() == null ? "null" : literal(table.schema())).append(";\n");
		java.append("    public static final String STRUCTURE_ID = ").append(literal(fingerprint)).append(";\n");
		java.append("    public static final List<String> COLUMNS = List.of(\n");
		for (int i = 0; i < columns.size(); i++) {
			java.append("            ").append(literal(columns.get(i).name()));
			java.append(i < columns.size() - 1 ? ",\n" : "\n");
		}
		java.append("    );\n\n");
		java.append("    /** 编译期未知的结构在加载这一刻固化：之后每行数据只走调用，不查元数据、不走反射。 */\n");
		java.append("    public static final MetaEntityReader READER = ").append(className).append("::read;\n");
		java.append("    /** 空实体工厂：resultMap 逐行映射复用它，避免每行反射构造。 */\n");
		java.append("    public static final Supplier<MetaClass> NEW = ").append(className).append("::new;\n\n");
		for (Column column : columns) {
			java.append("    private ").append(column.javaType()).append(' ').append(column.field()).append(";\n");
		}
		java.append("\n    public ").append(className).append("() {\n");
		java.append("        setName(TABLE_NAME);\n");
		java.append("        setNamespace(TABLE_SCHEMA);\n");
		java.append("        setMetaType(MetaType.META_TYPE_ENTITY);\n");
		java.append("    }\n\n");
		for (Column column : columns) {
			java.append("    // ").append(literal(column.name())).append(" (").append(column.sqlTypeComment())
					.append(")\n");
			java.append("    public ").append(column.javaType()).append(' ').append(column.getter()).append("() {\n");
			java.append("        return this.").append(column.field()).append(";\n");
			java.append("    }\n\n");
			java.append("    public void ").append(column.setter()).append('(').append(column.javaType())
					.append(" value) {\n");
			java.append("        this.").append(column.field()).append(" = value;\n");
			java.append("        markChanged(").append(literal(column.name())).append(");\n");
			java.append("    }\n\n");
		}
		java.append("    /** 逐行读取：先建列标签索引，投影查询缺列时字段保持 null。直写字段不标脏——读出来的行是干净的。 */\n");
		java.append("    public static ").append(className).append(" read(ResultSet rs) throws SQLException {\n");
		java.append("        ").append(className).append(" entity = new ").append(className).append("();\n");
		java.append("        Map<String, Integer> cols = MetaEntityColumns.index(rs);\n");
		java.append("        Integer index;\n");
		java.append("        Object raw;\n");
		for (Column column : columns) {
			java.append("        index = cols.get(").append(literal(column.lower())).append(");\n");
			java.append("        if (index != null) {\n");
			java.append("            raw = rs.getObject(index);\n");
			java.append("            entity.").append(column.field()).append(" = raw == null ? null : MetaValueReader.")
					.append(column.reader()).append("(raw);\n");
			java.append("        }\n");
		}
		java.append("        return entity;\n");
		java.append("    }\n\n");
		java.append("    /** 按列名读取（大小写不敏感）：生成的 switch 直达字段，不经属性列表。 */\n");
		java.append("    @Override\n");
		java.append("    public Object get(String name) {\n");
		java.append("        if (name == null) {\n");
		java.append("            return null;\n");
		java.append("        }\n");
		java.append("        switch (name.toLowerCase(Locale.ROOT)) {\n");
		for (Column column : columns) {
			java.append("            case ").append(literal(column.lower())).append(":\n");
			java.append("                return this.").append(column.field()).append(";\n");
		}
		java.append("            default:\n");
		java.append("                return super.get(name);\n");
		java.append("        }\n");
		java.append("    }\n\n");
		java.append("    /** 按列名写入（大小写不敏感）：值按列的 Java 类型收口，写完标脏。 */\n");
		java.append("    @Override\n");
		java.append("    public void set(String name, Object value) {\n");
		java.append("        if (name == null) {\n");
		java.append("            return;\n");
		java.append("        }\n");
		java.append("        switch (name.toLowerCase(Locale.ROOT)) {\n");
		for (Column column : columns) {
			java.append("            case ").append(literal(column.lower())).append(":\n");
			java.append("                this.").append(column.field()).append(" = value == null ? null : MetaValueReader.")
					.append(column.reader()).append("(value);\n");
			java.append("                break;\n");
		}
		java.append("            default:\n");
		java.append("                super.set(name, value);\n");
		java.append("                return;\n");
		java.append("        }\n");
		java.append("        markChanged(name);\n");
		java.append("    }\n\n");
		java.append("    /** 元模型视图：把真实字段摊成 MetaProperty，列以外的动态属性（命名语句的参数）由基类补齐。 */\n");
		java.append("    @Override\n");
		java.append("    public List<MetaProperty> getProperties() {\n");
		java.append("        List<MetaProperty> properties = new ArrayList<>(").append(columns.size()).append(");\n");
		for (Column column : columns) {
			java.append("        properties.add(prop(").append(literal(column.name())).append(", ")
					.append(column.typeLiteral()).append(", ").append(column.jdbcType()).append(", ")
					.append(column.sqlTypeLiteral()).append(", ").append(column.nullable()).append(", ")
					.append(column.primaryKey()).append(", ").append(column.autoIncrement()).append(", ")
					.append(column.defaultLiteral()).append(", this.").append(column.field()).append("));\n");
		}
		java.append("        properties.addAll(super.dynamicProperties());\n");
		java.append("        return properties;\n");
		java.append("    }\n\n");
		java.append("    private static MetaProperty prop(String name, String type, int jdbcType, String sqlTypeName,\n");
		java.append("            boolean nullable, boolean primaryKey, boolean autoIncrement, String defaultValue, Object value) {\n");
		java.append("        MetaProperty property = new MetaProperty();\n");
		java.append("        property.setName(name);\n");
		java.append("        property.setType(type);\n");
		java.append("        property.setTypeName(type);\n");
		java.append("        property.setJdbcType(jdbcType);\n");
		java.append("        property.setSqlTypeName(sqlTypeName);\n");
		java.append("        property.setNullable(nullable);\n");
		java.append("        property.setPrimaryKey(primaryKey);\n");
		java.append("        property.setAutoIncrement(autoIncrement);\n");
		java.append("        property.setDefaultValue(defaultValue);\n");
		java.append("        property.setValue(value);\n");
		java.append("        return property;\n");
		java.append("    }\n\n");
		java.append("    @Override\n");
		java.append("    public String toString() {\n");
		java.append("        return ").append(literal(className + "{")).append(" + toMap() + \"}\";\n");
		java.append("    }\n");
		java.append("}\n");
		return new Source(packageName, className, packageName + "." + className, fingerprint, java.toString());
	}

	// ------------------------------------------------------------------
	// 列视图
	// ------------------------------------------------------------------

	private record Column(String name, String lower, String field, String getter, String setter, String javaType,
			String reader, String typeLiteral, String jdbcType, String sqlTypeLiteral, String sqlTypeComment,
			String defaultLiteral, String nullable, String primaryKey, String autoIncrement) {
	}

	private static List<Column> columns(List<MetaProperty> columns) {
		Set<String> usedFields = new HashSet<>();
		List<Column> result = new ArrayList<>(columns.size());
		for (MetaProperty column : columns) {
			String name = column.getName();
			String field = uniqueField(name, usedFields);
			String javaType = column.getType();
			String reader = readerOf(javaType);
			String fieldType = "asObject".equals(reader) ? "java.lang.Object" : javaType;
			result.add(new Column(
					name,
					name.toLowerCase(Locale.ROOT),
					field,
					"get" + capitalize(field),
					"set" + capitalize(field),
					fieldType,
					reader,
					literal(javaType == null ? "java.lang.Object" : javaType),
					Integer.toString(column.getJdbcType()),
					column.getSqlTypeName() == null ? "null" : literal(column.getSqlTypeName()),
					commentSafe(column.getSqlTypeName()),
					column.getDefaultValue() == null ? "null" : literal(column.getDefaultValue()),
					Boolean.toString(column.isNullable()),
					Boolean.toString(column.isPrimaryKey()),
					Boolean.toString(column.isAutoIncrement())));
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
