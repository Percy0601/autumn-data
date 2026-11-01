package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Column;

/**
 * 生成 {@code <名称>Impl} 的 Java 源码文本。
 *
 * <p>运行期形态：{@code @Service} + 构造注入 {@code JdbcClient}，SQL 来自编译期落盘的
 * properties 资源，结果集映射是生成的显式取值代码 —— 全程零反射。</p>
 */
final class ImplementationSource {

	private static final String SQL_STATEMENTS = "xyz.wewin.autumn.data.jdbc.sql.SqlStatements";

	private static final String JDBC_CLIENT = "org.springframework.jdbc.core.simple.JdbcClient";

	private ImplementationSource() {
	}

	static String generate(String interfaceSimpleName, String implSimpleName, String entity, String id,
			EntityMetadata meta, String sqlResource) {
		Generation generation = new Generation(interfaceSimpleName, implSimpleName, entity, id, meta, sqlResource);
		return generation.render();
	}

	/** 一次生成的上下文，方法各自返回一段源码文本。 */
	private static final class Generation {

		private final String interfaceSimpleName;

		private final String implSimpleName;

		private final String entity;

		private final String id;

		private final EntityMetadata meta;

		private final String sqlResource;

		private Generation(String interfaceSimpleName, String implSimpleName, String entity, String id,
				EntityMetadata meta, String sqlResource) {
			this.interfaceSimpleName = interfaceSimpleName;
			this.implSimpleName = implSimpleName;
			this.entity = entity;
			this.id = id;
			this.meta = meta;
			this.sqlResource = sqlResource;
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
			sb.append(rowMapper());
			sb.append("\tprivate final " + JDBC_CLIENT + " jdbcClient;\n\n");
			sb.append(constructor());
			sb.append(method("<S extends " + this.entity + "> S save(S entity)", saveBody()));
			sb.append(method("<S extends " + this.entity + "> java.util.List<S> saveAll(java.util.List<S> entities)",
					saveAllBody()));
			sb.append(method("java.util.Optional<" + this.entity + "> findById(" + this.id + " id)",
					"return this.jdbcClient.sql(SQL.get(\"" + CrudSqlGenerator.SELECT_BY_ID + "\")).param(id)"
							+ ".query(ROW_MAPPER).optional();"));
			sb.append(method("boolean existsById(" + this.id + " id)",
					"return this.jdbcClient.sql(SQL.get(\"" + CrudSqlGenerator.EXISTS_BY_ID + "\")).param(id)"
							+ ".query(java.lang.Long.class).single() > 0;"));
			sb.append(method("java.util.List<" + this.entity + "> findAll()",
					"return this.jdbcClient.sql(SQL.get(\"" + CrudSqlGenerator.SELECT_ALL + "\")).query(ROW_MAPPER)"
							+ ".list();"));
			sb.append(method("java.util.List<" + this.entity + "> findAllById(java.util.List<" + this.id + "> ids)",
					findAllByIdBody()));
			sb.append(method("long count()", "return this.jdbcClient.sql(SQL.get(\"" + CrudSqlGenerator.COUNT + "\"))"
					+ ".query(java.lang.Long.class).single();"));
			sb.append(method("void deleteById(" + this.id + " id)",
					"this.jdbcClient.sql(SQL.get(\"" + CrudSqlGenerator.DELETE_BY_ID + "\")).param(id).update();"));
			sb.append(method("void delete(" + this.entity + " entity)",
					"deleteById(entity." + this.meta.id().getter() + "());"));
			sb.append(method("void deleteAllById(java.util.List<? extends " + this.id + "> ids)",
					deleteAllByIdBody()));
			sb.append(method("void deleteAll(java.util.List<? extends " + this.entity + "> entities)",
					deleteAllBody()));
			sb.append(method("void deleteAll()",
					"this.jdbcClient.sql(SQL.get(\"" + CrudSqlGenerator.DELETE_ALL + "\")).update();"));
			sb.append("\tprivate void insert(").append(this.entity).append(" entity) {\n")
				.append(indent(insertBody().stripTrailing())).append("\t}\n");
			sb.append("}\n");
			return sb.toString();
		}

		private String method(String signature, String body) {
			return "\t@Override\n\tpublic " + signature + " {\n" + indent(body) + "\t}\n\n";
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

		private String rowMapper() {
			StringBuilder sb = new StringBuilder();
			sb.append("\tprivate static final org.springframework.jdbc.core.RowMapper<").append(this.entity)
				.append("> ROW_MAPPER = (rs, rowNum) -> {\n");
			sb.append("\t\t").append(this.entity).append(" row = new ").append(this.entity).append("();\n");
			for (Column column : this.meta.columns()) {
				sb.append("\t\trow.").append(column.setter()).append('(').append(readColumn(column)).append(");\n");
			}
			sb.append("\t\treturn row;\n");
			sb.append("\t};\n\n");
			return sb.toString();
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

		private String constructor() {
			return "\tpublic " + this.implSimpleName + "(" + JDBC_CLIENT + " jdbcClient) {\n"
					+ "\t\tthis.jdbcClient = jdbcClient;\n" + "\t}\n\n";
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
			return "this.jdbcClient.sql(SQL.get(\"" + CrudSqlGenerator.UPDATE + "\")).params("
					+ valueArguments(this.meta.updateColumns()) + ", entity." + this.meta.id().getter() + "())"
					+ ".update();";
		}

		private String trimUpdate(String updateCall) {
			return updateCall.substring(0, updateCall.length() - 1);
		}

		private String saveAllBody() {
			return "java.util.List<S> saved = new java.util.ArrayList<>(entities.size());\n"
					+ "for (S entity : entities) {\n" + "\tsaved.add(save(entity));\n" + "}\n" + "return saved;";
		}

		private String findAllByIdBody() {
			return "if (ids.isEmpty()) {\n" + "\treturn java.util.List.of();\n" + "}\n"
					+ "return this.jdbcClient.sql(SQL.inClause(\"" + CrudSqlGenerator.SELECT_BY_IDS + "\", ids.size()))"
					+ ".params(ids.toArray()).query(ROW_MAPPER).list();";
		}

		private String deleteAllByIdBody() {
			return "if (!ids.isEmpty()) {\n" + "\tthis.jdbcClient.sql(SQL.inClause(\""
					+ CrudSqlGenerator.DELETE_BY_IDS + "\", ids.size())).params(ids.toArray()).update();\n" + "}";
		}

		private String deleteAllBody() {
			return "for (" + this.entity + " entity : entities) {\n" + "\tdeleteById(entity."
					+ this.meta.id().getter() + "());\n" + "}";
		}

		private String insertBody() {
			List<Column> columns = this.meta.insertColumns();
			if (!this.meta.id().generated()) {
				return "this.jdbcClient.sql(SQL.get(\"" + CrudSqlGenerator.INSERT + "\")).params("
						+ valueArguments(columns) + ").update();";
			}
			StringBuilder sb = new StringBuilder();
			sb.append("org.springframework.jdbc.support.GeneratedKeyHolder keyHolder = ")
				.append("new org.springframework.jdbc.support.GeneratedKeyHolder();\n");
			sb.append("this.jdbcClient.sql(SQL.get(\"").append(CrudSqlGenerator.INSERT)
				.append("\")).params(").append(valueArguments(columns)).append(").update(keyHolder, \"")
				.append(this.meta.id().column()).append("\");\n");
			String assign = assignGeneratedKey();
			if (assign != null) {
				sb.append("java.lang.Number generatedKey = keyHolder.getKey();\n");
				sb.append("if (generatedKey != null) {\n").append("\t").append(assign).append("\n}\n");
			}
			return sb.toString();
		}

		/** 把数据库生成键写回实体主键；无法转换的主键类型保留原值。 */
		private String assignGeneratedKey() {
			String idType = this.meta.id().javaType();
			String number = switch (idType) {
				case "long", "java.lang.Long" -> "generatedKey.longValue()";
				case "int", "java.lang.Integer" -> "generatedKey.intValue()";
				case "short", "java.lang.Short" -> "generatedKey.shortValue()";
				case "java.math.BigInteger" -> "java.math.BigInteger.valueOf(generatedKey.longValue())";
				default -> null;
			};
			return (number == null) ? null : "entity." + this.meta.id().setter() + "(" + number + ");";
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
