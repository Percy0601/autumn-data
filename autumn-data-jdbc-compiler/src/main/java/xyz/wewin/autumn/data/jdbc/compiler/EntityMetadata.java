package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.List;

/**
 * 编译期从实体类提取的元数据快照（表名 / 主键 / 列清单），供 SQL 生成与实现类生成共用。
 *
 * <p>字段类型以源码可书写的名字记录：基本类型保留 {@code int} 这类字面名，
 * 引用类型用全限定名，因此生成的代码不需要 import 推断。</p>
 *
 * @param qualifiedName 实体全限定名
 * @param table 表名
 * @param id 主键列
 * @param columns 全部列（含主键，按声明顺序，父类字段在前）
 */
record EntityMetadata(String qualifiedName, String table, EntityMetadata.Column id, List<Column> columns) {

	/** INSERT 的列：自增主键交由数据库生成，不写进列清单。 */
	List<Column> insertColumns() {
		return this.id.generated() ? updateColumns() : this.columns;
	}

	/** 非主键列，用于 UPDATE 的 SET 片段和自增主键实体的 INSERT。 */
	List<Column> updateColumns() {
		return this.columns.stream().filter(column -> !column.id()).toList();
	}

	/**
	 * 单列映射。
	 *
	 * @param field 实体字段名
	 * @param column 数据库列名
	 * @param javaType 字段类型（基本类型字面名或全限定名）
	 * @param getter 读取方法名
	 * @param setter 写入方法名
	 * @param id 是否主键
	 * @param generated 是否数据库自增（{@code @GeneratedValue}）
	 */
	record Column(String field, String column, String javaType, String getter, String setter, boolean id,
			boolean generated) {

		boolean primitive() {
			return !this.javaType.contains(".") && !this.javaType.endsWith("[]");
		}
	}
}
