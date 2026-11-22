package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;

/**
 * 把一条自定义结果形状语句的列结构拼成一份 Java 源码：一条语句一个具体类。
 *
 * <p>和 {@link MetaEntitySource} 的区别只在输入：实体源拿整张表的列，这里拿 SELECT 实际返回的列
 * （可能是 JOIN 出来的、聚合出来的，锚定表根本没有）。生成物仍是 {@link xyz.wewin.autumn.data.common.meta.MetaClass}
 * 子类，但类名、包名、字段类型都是具体的：</p>
 *
 * <pre>
 * package xyz.wewin.autumn.data.meta.jdbc.shape.generated.default.meta_demo_form.v1a2b3c4d5e6f;
 * public final class FormWithCustomer extends MetaClass {
 *     private Long id;
 *     private String customerName;
 *     private BigDecimal totalAmount;
 *     ...
 * }
 * </pre>
 *
 * <p>包名带 {@code v<指纹>}：SELECT 的列顺序/名字/类型一变就换一个包，旧类还能被正在执行的结果集继续使用，
 * 不会同名重定义；没有变化的语句复用同一个类。类名直接由语句 id 派生，
 * 所以 {@code formWithCustomer} 生成的就是 {@code FormWithCustomer}。</p>
 */
public final class MetaShapeSource {

	private static final String GENERATED_PACKAGE = "xyz.wewin.autumn.data.meta.jdbc.shape.generated";

	private MetaShapeSource() {
	}

	/** 一条语句 + 一版列形状对应的源码；指纹只由列结构决定，与数据无关。 */
	public static MetaEntitySource.Source render(MetaTable table, String statementId, List<MetaShapeColumn> shape) {
		String fingerprint = fingerprint(table, statementId, shape);
		String packageName = GENERATED_PACKAGE + '.' + packageSegment(table.schema(), "default") + '.'
				+ packageSegment(table.name(), "shape") + ".v" + fingerprint;
		String className = MetaEntitySource.className(statementId);
		List<Column> columns = columns(shape);
		StringBuilder java = new StringBuilder(2048 + columns.size() * 280);
		java.append("package ").append(packageName).append(";\n\n");
		java.append("// generated-by: autumn-data-meta-jdbc (MetaShapeSource + javax.tools.JavaCompiler)\n");
		java.append("// source table: ").append(MetaEntitySource.literal(table.name())).append('\n');
		java.append("// statement: ").append(MetaEntitySource.literal(statementId)).append('\n');
		java.append("// shape-id: ").append(fingerprint).append('\n');
		java.append("// 本类由一条 SELECT 的结果列生成：字段/typed getter/结果集读取都是静态调用。\n");
		java.append("import java.sql.ResultSet;\n");
		java.append("import java.sql.SQLException;\n");
		java.append("import java.util.ArrayList;\n");
		java.append("import java.util.List;\n");
		java.append("import java.util.Locale;\n");
		java.append("import java.util.function.Supplier;\n\n");
		java.append("import xyz.wewin.autumn.data.common.meta.MetaClass;\n");
		java.append("import xyz.wewin.autumn.data.common.meta.MetaProperty;\n");
		java.append("import xyz.wewin.autumn.data.common.meta.MetaType;\n");
		java.append("import xyz.wewin.autumn.data.common.meta.MetaValueReader;\n");
		java.append("import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityReader;\n\n");
		java.append("public final class ").append(className).append(" extends MetaClass {\n\n");
		java.append("    public static final String SOURCE_TABLE = ").append(MetaEntitySource.literal(table.name()))
				.append(";\n");
		java.append("    public static final String SOURCE_SCHEMA = ")
				.append(table.schema() == null ? "null" : MetaEntitySource.literal(table.schema())).append(";\n");
		java.append("    public static final String STATEMENT_ID = ")
				.append(MetaEntitySource.literal(statementId)).append(";\n");
		java.append("    public static final String PACKAGE_NAME = ")
				.append(MetaEntitySource.literal(packageName)).append(";\n");
		java.append("    public static final String CLASS_NAME = ")
				.append(MetaEntitySource.literal(className)).append(";\n");
		java.append("    public static final String SHAPE_ID = ").append(MetaEntitySource.literal(fingerprint))
				.append(";\n");
		java.append("    public static final List<String> COLUMNS = List.of(\n");
		for (int i = 0; i < columns.size(); i++) {
			java.append("            ").append(MetaEntitySource.literal(columns.get(i).name()));
			java.append(i < columns.size() - 1 ? ",\n" : "\n");
		}
		java.append("    );\n\n");
		java.append("    /** 这一版形状的逐行读取器：按 ResultSet 的列序号直写字段，零反射。 */\n");
		java.append("    public static final MetaEntityReader READER = ").append(className).append("::read;\n");
		java.append("    public static final Supplier<MetaClass> NEW = ").append(className).append("::new;\n");
		java.append("    public static final Class<? extends MetaClass> TYPE = ").append(className)
				.append(".class;\n\n");
		for (Column column : columns) {
			java.append("    private ").append(column.fieldType()).append(' ').append(column.field()).append(";\n");
		}
		java.append("\n    public ").append(className).append("() {\n");
		java.append("        setName(SOURCE_TABLE);\n");
		java.append("        setNamespace(SOURCE_SCHEMA);\n");
		java.append("        setMetaType(MetaType.META_TYPE_VO);\n");
		java.append("    }\n\n");
		for (Column column : columns) {
			java.append("    // ").append(MetaEntitySource.literal(column.name())).append(" (")
					.append(column.sqlTypeComment()).append(")\n");
			java.append("    public ").append(column.fieldType()).append(' ').append(column.getter()).append("() {\n");
			java.append("        return this.").append(column.field()).append(";\n");
			java.append("    }\n\n");
			java.append("    public void ").append(column.setter()).append('(').append(column.fieldType())
					.append(" value) {\n");
			java.append("        this.").append(column.field()).append(" = value;\n");
			java.append("        markChanged(").append(MetaEntitySource.literal(column.name())).append(");\n");
			java.append("    }\n\n");
		}
		java.append("    /** 逐行读取：列顺序由生成时固化，直接按序号取值，不建列名索引。 */\n");
		java.append("    public static ").append(className).append(" read(ResultSet rs) throws SQLException {\n");
		java.append("        ").append(className).append(" row = new ").append(className).append("();\n");
		java.append("        Object raw;\n");
		for (Column column : columns) {
			java.append("        raw = rs.getObject(").append(column.index()).append(");\n");
			java.append("        row.").append(column.field()).append(" = raw == null ? null : MetaValueReader.")
					.append(column.reader()).append("(raw);\n");
		}
		java.append("        return row;\n");
		java.append("    }\n\n");
		java.append("    @Override\n");
		java.append("    public Object get(String name) {\n");
		java.append("        if (name == null) {\n");
		java.append("            return null;\n");
		java.append("        }\n");
		java.append("        switch (name.toLowerCase(Locale.ROOT)) {\n");
		for (Column column : columns) {
			java.append("            case ").append(MetaEntitySource.literal(column.lower())).append(":\n");
			java.append("                return this.").append(column.field()).append(";\n");
		}
		java.append("            default:\n");
		java.append("                return super.get(name);\n");
		java.append("        }\n");
		java.append("    }\n\n");
		java.append("    @Override\n");
		java.append("    public void set(String name, Object value) {\n");
		java.append("        if (name == null) {\n");
		java.append("            return;\n");
		java.append("        }\n");
		java.append("        switch (name.toLowerCase(Locale.ROOT)) {\n");
		for (Column column : columns) {
			java.append("            case ").append(MetaEntitySource.literal(column.lower())).append(":\n");
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
		java.append("    @Override\n");
		java.append("    public List<MetaProperty> getProperties() {\n");
		java.append("        List<MetaProperty> properties = new ArrayList<>(").append(columns.size()).append(");\n");
		for (Column column : columns) {
			java.append("        properties.add(prop(").append(MetaEntitySource.literal(column.name())).append(", ")
					.append(column.typeLiteral()).append(", ").append(column.jdbcType()).append(", ")
					.append(column.sqlTypeLiteral()).append(", ").append(column.nullable())
					.append(", this.").append(column.field()).append("));\n");
		}
		java.append("        properties.addAll(super.dynamicProperties());\n");
		java.append("        return properties;\n");
		java.append("    }\n\n");
		java.append("    private static MetaProperty prop(String name, String type, int jdbcType,")
				.append(" String sqlTypeName, boolean nullable, Object value) {\n");
		java.append("        MetaProperty property = new MetaProperty();\n");
		java.append("        property.setName(name);\n");
		java.append("        property.setType(type);\n");
		java.append("        property.setTypeName(type);\n");
		java.append("        property.setJdbcType(jdbcType);\n");
		java.append("        property.setSqlTypeName(sqlTypeName);\n");
		java.append("        property.setNullable(nullable);\n");
		java.append("        property.setValue(value);\n");
		java.append("        return property;\n");
		java.append("    }\n\n");
		java.append("    @Override\n");
		java.append("    public String toString() {\n");
		java.append("        return ").append(MetaEntitySource.literal(className + "{")).append(" + toMap() + \"}\";\n");
		java.append("    }\n");
		java.append("}\n");
		return new MetaEntitySource.Source(packageName, className, packageName + '.' + className, fingerprint,
				java.toString());
	}

	/** 列顺序 + 列名 + JDBC 类型 + 可空 + SQL 类型名；只描述形状，不含数据。 */
	static String fingerprint(MetaTable table, String statementId, List<MetaShapeColumn> shape) {
		StringBuilder raw = new StringBuilder(256);
		raw.append(table.name()).append('|').append(table.schema()).append('|').append(statementId).append('|');
		for (MetaShapeColumn column : shape) {
			raw.append(column.name()).append(':').append(column.jdbcType()).append(':')
					.append(column.javaType()).append(':').append(column.sqlTypeName()).append(':')
					.append(column.nullable() ? '1' : '0').append(';');
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.toString().getBytes(StandardCharsets.UTF_8));
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
		catch (RuntimeException ex) {
			throw new MetaSqlException("cannot fingerprint result shape for '" + statementId + "'", ex);
		}
	}

	/** 包名的一段：小写、非标识符字符压成下划线、避开 Java 关键字与数字开头。 */
	private static String packageSegment(String value, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			out.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
		}
		String segment = out.toString();
		if (segment.isEmpty()) {
			return fallback;
		}
		if (!Character.isJavaIdentifierStart(segment.charAt(0)) || MetaEntitySource.JAVA_KEYWORDS.contains(segment)) {
			return "p_" + segment;
		}
		return segment;
	}

	private record Column(int index, String name, String lower, String field, String getter, String setter,
			String fieldType, String reader, String typeLiteral, String jdbcType, String sqlTypeLiteral,
			String sqlTypeComment, String nullable) {
	}

	private static List<Column> columns(List<MetaShapeColumn> shape) {
		Set<String> usedFields = new HashSet<>();
		List<Column> result = new ArrayList<>(shape.size());
		for (int i = 0; i < shape.size(); i++) {
			MetaShapeColumn column = shape.get(i);
			String javaType = column.javaType() == null ? "java.lang.Object" : column.javaType();
			String reader = MetaEntitySource.readerOf(javaType);
			String field = MetaEntitySource.uniqueField(column.name(), usedFields);
			String fieldType = "asObject".equals(reader) ? "java.lang.Object" : javaType;
			result.add(new Column(column.index(), column.name(), column.name().toLowerCase(Locale.ROOT), field,
					"get" + capitalize(field), "set" + capitalize(field), fieldType, reader,
					MetaEntitySource.literal(javaType), Integer.toString(column.jdbcType()),
					column.sqlTypeName() == null ? "null" : MetaEntitySource.literal(column.sqlTypeName()),
					commentSafe(column.sqlTypeName()), Boolean.toString(column.nullable())));
		}
		return result;
	}

	private static String capitalize(String name) {
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	private static String commentSafe(String value) {
		if (value == null) {
			return "unknown";
		}
		return value.replaceAll("[^A-Za-z0-9_ ()\\-.,]", "_");
	}
}
