package xyz.wewin.autumn.data.jdbc.compiler;

/**
 * 生成 {@code <名称>Impl} 的 Java 源码文本（管道版骨架）。
 *
 * <p>骨架阶段先实现 {@code CrudRepository} 的标准方法清单并抛出异常占位；
 * 下一步由实体元数据驱动的 SQL / RowMapper 生成替换方法体。
 * {@code findAllById}/{@code saveAll} 等以 {@code List} 表达（对齐核心 API 设计）。</p>
 */
final class ImplementationSource {

	private ImplementationSource() {
	}

	static String generate(String interfaceSimpleName, String implSimpleName, String entity, String id) {
		StringBuilder sb = new StringBuilder();
		sb.append("/** 由 autumn-data-jdbc-compiler 生成，请勿手工修改。 */\n");
		sb.append("@javax.annotation.processing.Generated(\"")
			.append(AutoRepositoryProcessor.class.getName())
			.append("\")\n");
		sb.append("public class ").append(implSimpleName)
			.append(" implements ").append(interfaceSimpleName).append(" {\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic <S extends ").append(entity).append("> S save(S entity) {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic <S extends ").append(entity).append("> java.util.List<S> saveAll(")
			.append("java.util.List<S> entities) {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic java.util.Optional<").append(entity).append("> findById(").append(id)
			.append(" id) {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic boolean existsById(").append(id).append(" id) {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic java.util.List<").append(entity).append("> findAll() {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic java.util.List<").append(entity).append("> findAllById(java.util.List<").append(id)
			.append("> ids) {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic long count() {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic void deleteById(").append(id).append(" id) {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic void delete(").append(entity).append(" entity) {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic void deleteAllById(java.util.List<? extends ").append(id).append("> ids) {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic void deleteAll(java.util.List<? extends ").append(entity).append("> entities) {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n\n");
		sb.append("\t@Override\n")
			.append("\tpublic void deleteAll() {\n")
			.append("\t\tthrow new UnsupportedOperationException(\"awaiting M2: SQL generation\");\n\t}\n")
			.append("}\n");
		return sb.toString();
	}
}
