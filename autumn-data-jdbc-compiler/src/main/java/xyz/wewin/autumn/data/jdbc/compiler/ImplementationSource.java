package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Association;
import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Column;

/**
 * 生成 {@code <名称>Impl} 的 Java 源码文本。
 *
 * <p>运行期形态：{@code @Service} + 构造注入 {@code AutumnJdbc}（唯一的运行期协作者，里面收着
 * JDBC 客户端、方言与取数上限），SQL 来自编译期落盘的 properties 资源，结果集映射是生成的显式取值代码
 * —— 全程零反射。</p>
 *
 * <p>一对多：一条父查询 + 一条按外键 {@code IN} 的子查询，再按外键分组写回集合；
 * 子查询行数由 {@code AutumnJdbc#queryLimited} 按 {@code associationLimit} 截断。</p>
 */
final class ImplementationSource {

	private static final String SQL_STATEMENTS = "xyz.wewin.autumn.data.jdbc.sql.SqlStatements";

	private static final String AUTUMN_JDBC = "xyz.wewin.autumn.data.jdbc.spring.AutumnJdbc";

	private static final String PAGE = "xyz.wewin.autumn.data.jdbc.page.Page";

	private static final String PAGEABLE = "xyz.wewin.autumn.data.jdbc.page.Pageable";

	private static final String ROW_MAPPER = "org.springframework.jdbc.core.RowMapper";

	private ImplementationSource() {
	}

	static String generate(String interfaceSimpleName, String implSimpleName, String entity, String id,
			EntityMetadata meta, String sqlResource, boolean paging) {
		return new Generation(interfaceSimpleName, implSimpleName, entity, id, meta, sqlResource, paging).render();
	}

	/** 一次生成的上下文，方法各自返回一段源码文本。 */
	private static final class Generation {

		private final String interfaceSimpleName;

		private final String implSimpleName;

		private final String entity;

		private final String id;

		private final EntityMetadata meta;

		private final String sqlResource;

		private final boolean paging;

		private Generation(String interfaceSimpleName, String implSimpleName, String entity, String id,
				EntityMetadata meta, String sqlResource, boolean paging) {
			this.interfaceSimpleName = interfaceSimpleName;
			this.implSimpleName = implSimpleName;
			this.entity = entity;
			this.id = id;
			this.meta = meta;
			this.sqlResource = sqlResource;
			this.paging = paging;
		}

		private List<Association> eagerAssociations() {
			return this.meta.associations().stream().filter(Association::eager).toList();
		}

		private String render() {
			StringBuilder sb = new StringBuilder();
			sb.append("/** 由 autumn-data-jdbc-compiler 生成，请勿手工修改。 */\n");
			sb.append("@javax.annotation.processing.Generated(\"")
				.append(AutoRepositoryProcessor.class.getName())
				.append("\")\n");
			sb.append("@org.springframework.stereotype.Service\n");
			sb.append("public class ").append(this.implSimpleName).append(" implements ")
				.append(this.interfaceSimpleName).append(" {\n\n");
			sb.append("\tprivate static final ").append(SQL_STATEMENTS).append(" SQL = ")
				.append(SQL_STATEMENTS).append(".load(\"").append(this.sqlResource).append("\");\n\n");
			sb.append(rowMapperField(this.meta, "ROW_MAPPER", this.entity));
			for (Association association : eagerAssociations()) {
				sb.append(childRowMapperField(association));
			}
			sb.append("\tprivate final " + AUTUMN_JDBC + " autumn;\n\n");
			sb.append(constructor());
			sb.append(method("<S extends " + this.entity + "> S save(S entity)", saveBody()));
			sb.append(method("<S extends " + this.entity + "> java.util.List<S> saveAll(java.util.List<S> entities)",
					saveAllBody()));
			sb.append(method("java.util.Optional<" + this.entity + "> findById(" + this.id + " id)",
					findByIdBody()));
			sb.append(method("boolean existsById(" + this.id + " id)",
					"return this.autumn.count(SQL.get(\"" + CrudSqlGenerator.EXISTS_BY_ID + "\"), id) > 0;"));
			sb.append(method("java.util.List<" + this.entity + "> findAll()",
					loaded("this.autumn.query(SQL.get(\"" + CrudSqlGenerator.SELECT_ALL + "\"), ROW_MAPPER)")));
			if (this.paging) {
				sb.append(method(PAGE + "<" + this.entity + "> findAll(" + PAGEABLE + " pageable)",
						findAllPageableBody()));
			}
			sb.append(method("java.util.List<" + this.entity + "> findAllById(java.util.List<" + this.id + "> ids)",
					findAllByIdBody()));
			sb.append(method("long count()",
					"return this.autumn.count(SQL.get(\"" + CrudSqlGenerator.COUNT + "\"));"));
			sb.append(method("void deleteById(" + this.id + " id)",
					"this.autumn.update(SQL.get(\"" + CrudSqlGenerator.DELETE_BY_ID + "\"), id);"));
			sb.append(method("void delete(" + this.entity + " entity)",
					"deleteById(entity." + this.meta.id().getter() + "());"));
			sb.append(method("void deleteAllById(java.util.List<? extends " + this.id + "> ids)",
					deleteAllByIdBody()));
			sb.append(method("void deleteAll(java.util.List<? extends " + this.entity + "> entities)",
					deleteAllBody()));
			sb.append(method("void deleteAll()",
					"this.autumn.update(SQL.get(\"" + CrudSqlGenerator.DELETE_ALL + "\"));"));
			sb.append(privateMethod("insert", "void", this.entity + " entity", insertBody()));
			for (Association association : eagerAssociations()) {
				sb.append(childRowMethod(association));
				sb.append(attachMethod(association));
			}
			sb.append("}\n");
			return sb.toString();
		}

		private String method(String signature, String body) {
			return "\t@Override\n\tpublic " + signature + " {\n" + indent(body) + "\t}\n\n";
		}

		private String privateMethod(String name, String returnType, String parameters, String body) {
			return "\tprivate " + returnType + " " + name + "(" + parameters + ") {\n" + indent(body) + "\t}\n\n";
		}

		private static String indent(String body) {
			StringBuilder sb = new StringBuilder();
			for (String line : body.split("\n", -1)) {
				if (!line.isEmpty()) {
					sb.append("\t\t");
				}
				sb.append(line).append('\n');
			}
			return sb.toString();
		}

		/** 实体行映射器：逐列显式取值，无反射。 */
		private String rowMapperField(EntityMetadata meta, String fieldName, String type) {
			return "\tprivate static final " + ROW_MAPPER + '<' + type + "> " + fieldName
					+ " = (rs, rowNum) -> {\n" + indent(rowConstruction(meta)) + "\t};\n\n";
		}

		/** 子行映射器：把外键值和子实体成对带回，分组就靠这个键，子实体不必暴露外键字段。 */
		private String childRowMapperField(Association association) {
			String keyed = "java.util.Map.Entry<java.lang.Object, " + association.elementType() + ">";
			return "\tprivate static final " + ROW_MAPPER + '<' + keyed + "> " + association.mapperField()
					+ " = (rs, rowNum) -> new java.util.AbstractMap.SimpleEntry<>((java.lang.Object) rs.getObject(\""
					+ association.foreignKeyColumn() + "\", " + this.meta.idValueType() + ".class), "
					+ association.rowMethod() + "(rs));\n\n";
		}

		private String childRowMethod(Association association) {
			return "\tprivate static " + association.elementType() + " " + association.rowMethod()
					+ "(java.sql.ResultSet rs) throws java.sql.SQLException {\n"
					+ indent(rowConstruction(association.target())) + "\t}\n\n";
		}

		/** {@code ResultSet -> 实体} 的取值代码，父/子实体共用。 */
		private String rowConstruction(EntityMetadata meta) {
			List<String> lines = new ArrayList<>();
			lines.add(meta.qualifiedName() + " row = new " + meta.qualifiedName() + "();");
			for (Column column : meta.columns()) {
				lines.add("row." + column.setter() + "(" + readColumn(column) + ");");
			}
			lines.add("return row;");
			return String.join("\n", lines);
		}

		/** 基本类型用专用取值（NULL 退化为 0/false），其余走类型化 {@code getObject}。 */
		private String readColumn(Column column) {
			String columnLabel = '"' + column.column() + '"';
			switch (column.javaType()) {
				case "int" :
					return "rs.getInt(" + columnLabel + ")";
				case "long" :
					return "rs.getLong(" + columnLabel + ")";
				case "short" :
					return "rs.getShort(" + columnLabel + ")";
				case "byte" :
					return "rs.getByte(" + columnLabel + ")";
				case "double" :
					return "rs.getDouble(" + columnLabel + ")";
				case "float" :
					return "rs.getFloat(" + columnLabel + ")";
				case "boolean" :
					return "rs.getBoolean(" + columnLabel + ")";
				case "char" :
					return "rs.getString(" + columnLabel + ").charAt(0)";
				default :
					return "rs.getObject(" + columnLabel + ", " + column.javaType() + ".class)";
			}
		}

		/**
		 * 关联装配：两条语句中的第二条。一次把本次父主键对应的子行全部取回（行数受
		 * {@code associationLimit} 约束），按外键分组后写回各自集合，因此不会有 N+1。
		 */
		private String attachMethod(Association association) {
			String elementType = association.elementType();
			String idGetter = this.meta.id().getter();
			List<String> body = List.of("if (parents.isEmpty()) {",
					"\treturn;",
					"}",
					"java.util.List<Object> parentIds = new java.util.ArrayList<>(parents.size());",
					"for (" + this.entity + " parent : parents) {",
					"\tparentIds.add(parent." + idGetter + "());",
					"}",
					"java.util.Map<Object, java.util.List<" + elementType + ">> grouped = new java.util.HashMap<>();",
					"for (java.util.Map.Entry<Object, " + elementType + "> entry : this.autumn.queryLimited("
							+ "SQL.inClause(\"" + association.statementKey() + "\", parentIds.size()), "
							+ association.mapperField() + ", parentIds.toArray())) {",
					"\tgrouped.computeIfAbsent(entry.getKey(), key -> new java.util.ArrayList<>())"
							+ ".add(entry.getValue());",
					"}",
					"for (" + this.entity + " parent : parents) {",
					"\tjava.util.List<" + elementType + "> loaded = grouped.get(parent." + idGetter + "());",
					"\tparent." + association.setter() + "(" + collectionValue(association, "loaded") + ");",
					"}");
			return privateMethod(association.attachMethod(), "void", "java.util.List<" + this.entity + "> parents",
					String.join("\n", body));
		}

		/** 关联集合为空时给可变空集合；Set 形态从分组结果转换。 */
		private String collectionValue(Association association, String loaded) {
			String elementType = association.elementType();
			if (association.collectionType().equals("java.util.Set")) {
				return "(" + loaded + " == null) ? new java.util.LinkedHashSet<" + elementType + ">() : new"
						+ " java.util.LinkedHashSet<" + elementType + ">(" + loaded + ")";
			}
			return "(" + loaded + " == null) ? new java.util.ArrayList<" + elementType + ">() : " + loaded;
		}

		private String constructor() {
			return "\tpublic " + this.implSimpleName + "(" + AUTUMN_JDBC + " autumn) {\n"
					+ "\t\tthis.autumn = autumn;\n" + "\t}\n\n";
		}

		/** 自增主键实体的判定：主键为空即新行，走 INSERT 并回填生成键。 */
		private String saveBody() {
			String update = updateCall();
			if (this.meta.id().generated()) {
				return "if (entity." + this.meta.id().getter() + "() == null) {\n" + "\tinsert(entity);\n"
						+ "}\nelse {\n\t" + update + "\n}\nreturn entity;";
			}
			return "if (" + trimUpdate(update) + " == 0) {\n" + "\tinsert(entity);\n" + "}\nreturn entity;";
		}

		private String updateCall() {
			return "this.autumn.update(SQL.get(\"" + CrudSqlGenerator.UPDATE + "\"), "
					+ valueArguments(this.meta.updateColumns()) + ", entity." + this.meta.id().getter() + "());";
		}

		private String trimUpdate(String updateCall) {
			return updateCall.substring(0, updateCall.length() - 1);
		}

		/**
		 * 批量写：按主键是否为空分两拨 —— 新行走一条带生成键回流的批量 INSERT，已有行走一条批量 UPDATE；
		 * 主键非自增的实体无法从数据库取键，只能逐条 upsert。
		 */
		private String saveAllBody() {
			if (!this.meta.id().generated()) {
				return "java.util.List<S> saved = new java.util.ArrayList<>(entities.size());\n"
						+ "for (S entity : entities) {\n" + "\tsaved.add(save(entity));\n" + "}\n" + "return saved;";
			}
			List<String> body = List.of("java.util.List<S> inserts = new java.util.ArrayList<>();",
					"java.util.List<S> updates = new java.util.ArrayList<>();",
					"for (S entity : entities) {",
					"\tif (entity." + this.meta.id().getter() + "() == null) {",
					"\t\tinserts.add(entity);",
					"\t}",
					"\telse {",
					"\t\tupdates.add(entity);",
					"\t}",
					"}",
					"if (!inserts.isEmpty()) {",
					"\tjava.util.List<Object[]> rows = new java.util.ArrayList<>(inserts.size());",
					"\tfor (S entity : inserts) {",
					"\t\trows.add(new Object[] { " + valueArguments(this.meta.insertColumns()) + " });",
					"\t}",
					"\tjava.util.List<java.lang.Number> keys = this.autumn.batchInsertReturningKeys(SQL.get(\""
							+ CrudSqlGenerator.INSERT + "\"), \"" + this.meta.id().column() + "\", rows);",
					"\t" + backfillKeys("inserts", "keys"),
					"}",
					"if (!updates.isEmpty()) {",
					"\tjava.util.List<Object[]> rows = new java.util.ArrayList<>(updates.size());",
					"\tfor (S entity : updates) {",
					"\t\trows.add(new Object[] { " + valueArguments(this.meta.updateColumns()) + ", entity."
							+ this.meta.id().getter() + "() });",
					"\t}",
					"\tthis.autumn.batchUpdate(SQL.get(\"" + CrudSqlGenerator.UPDATE + "\"), rows);",
					"}",
					"return entities;");
			return String.join("\n", body);
		}

		/** 数据库按序回吐生成键时逐个回填；行数不吻合（驱动只给汇总键）就保持实体原样，不误填。 */
		private String backfillKeys(String targets, String keys) {
			String assign = assignGeneratedKey(targets + ".get(i)", keys + ".get(i)");
			if (assign == null) {
				return "// " + this.meta.id().javaType() + " is not assignable from a generated key, ids stay as-is";
			}
			return "if (" + keys + ".size() == " + targets + ".size()) {\n" + "\t\tfor (int i = 0; i < " + keys
					+ ".size(); i++) {\n" + "\t\t\t" + assign + "\n" + "\t\t}\n" + "\t}";
		}

		private String findByIdBody() {
			String select = "this.autumn.queryOptional(SQL.get(\"" + CrudSqlGenerator.SELECT_BY_ID
					+ "\"), ROW_MAPPER, id)";
			if (eagerAssociations().isEmpty()) {
				return "return " + select + ";";
			}
			List<String> attach = eagerAssociations().stream()
				.map(a -> "\t" + a.attachMethod() + "(java.util.List.of(row));")
				.toList();
			return "java.util.Optional<" + this.entity + "> found = " + select + ";\n"
					+ "found.ifPresent(row -> {\n" + String.join("\n", attach) + "\n});\nreturn found;";
		}

		/** {@code List<Entity> rows = <query>; } 后逐条关联装配；无关联时直接返回查询结果。 */
		private String loaded(String queryCall) {
			if (eagerAssociations().isEmpty()) {
				return "return " + queryCall + ";";
			}
			StringBuilder sb = new StringBuilder("java.util.List<" + this.entity + "> rows = " + queryCall + ";\n");
			for (Association association : eagerAssociations()) {
				sb.append(association.attachMethod()).append("(rows);\n");
			}
			return sb.append("return rows;").toString();
		}

		private String findAllPageableBody() {
			String select = "this.autumn.page(SQL.get(\"" + CrudSqlGenerator.PAGE_ALL + "\"), SQL.get(\""
					+ CrudSqlGenerator.COUNT + "\"), ROW_MAPPER, pageable)";
			if (eagerAssociations().isEmpty()) {
				return "return " + select + ";";
			}
			StringBuilder sb = new StringBuilder(PAGE + "<" + this.entity + "> page = " + select + ";\n")
				.append("java.util.List<")
				.append(this.entity)
				.append("> rows = page.content();\n");
			for (Association association : eagerAssociations()) {
				sb.append(association.attachMethod()).append("(rows);\n");
			}
			return sb.append("return page;").toString();
		}

		private String findAllByIdBody() {
			String query = "this.autumn.query(SQL.inClause(\"" + CrudSqlGenerator.SELECT_BY_IDS + "\", ids.size()),"
					+ " ROW_MAPPER, ids.toArray())";
			return "if (ids.isEmpty()) {\n" + "\treturn java.util.List.of();\n" + "}\n" + loaded(query);
		}

		private String deleteAllByIdBody() {
			return "if (!ids.isEmpty()) {\n" + "\tthis.autumn.update(SQL.inClause(\""
					+ CrudSqlGenerator.DELETE_BY_IDS + "\", ids.size()), ids.toArray());\n" + "}";
		}

		/** 批量删：一条 {@code IN} 语句，不再逐行 DELETE。 */
		private String deleteAllBody() {
			return "if (entities.isEmpty()) {\n" + "\treturn;\n" + "}\n"
					+ "java.util.List<Object> ids = new java.util.ArrayList<>(entities.size());\n"
					+ "for (" + this.entity + " entity : entities) {\n" + "\tids.add(entity." + this.meta.id().getter()
					+ "());\n" + "}\n" + "this.autumn.update(SQL.inClause(\"" + CrudSqlGenerator.DELETE_BY_IDS
					+ "\", ids.size()), ids.toArray());";
		}

		private String insertBody() {
			List<Column> columns = this.meta.insertColumns();
			if (!this.meta.id().generated()) {
				return "this.autumn.update(SQL.get(\"" + CrudSqlGenerator.INSERT + "\"), " + valueArguments(columns)
						+ ");";
			}
			StringBuilder sb = new StringBuilder("java.lang.Number generatedKey = this.autumn.insertReturningKey(")
				.append("SQL.get(\"")
				.append(CrudSqlGenerator.INSERT)
				.append("\"), \"")
				.append(this.meta.id().column())
				.append("\", ")
				.append(valueArguments(columns))
				.append(");");
			String assign = assignGeneratedKey("entity", "generatedKey");
			if (assign != null) {
				sb.append("\nif (generatedKey != null) {\n").append("\t").append(assign).append("\n}");
			}
			return sb.toString();
		}

		/** 把数据库生成键写回实体主键；无法转换的主键类型返回 {@code null}，保留原值。 */
		private String assignGeneratedKey(String target, String keyExpression) {
			String number = switch (this.meta.id().javaType()) {
				case "long", "java.lang.Long" -> keyExpression + ".longValue()";
				case "int", "java.lang.Integer" -> keyExpression + ".intValue()";
				case "short", "java.lang.Short" -> keyExpression + ".shortValue()";
				case "java.math.BigInteger" -> "java.math.BigInteger.valueOf(" + keyExpression + ".longValue())";
				default -> null;
			};
			return (number == null) ? null : target + "." + this.meta.id().setter() + "(" + number + ");";
		}

		private String valueArguments(List<Column> columns) {
			List<String> arguments = new ArrayList<>(columns.size());
			for (Column column : columns) {
				arguments.add("entity." + column.getter() + "()");
			}
			return arguments.stream().collect(Collectors.joining(", "));
		}
	}
}
