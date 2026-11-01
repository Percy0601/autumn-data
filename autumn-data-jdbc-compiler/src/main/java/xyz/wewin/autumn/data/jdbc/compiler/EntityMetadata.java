package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.List;

/**
 * 编译期从实体类提取的元数据快照（表名 / 主键 / 列清单 / 一对多关联），供 SQL 生成与实现类生成共用。
 *
 * <p>字段类型以源码可书写的名字记录：基本类型保留 {@code int} 这类字面名，
 * 引用类型用全限定名，因此生成的代码不需要 import 推断。</p>
 *
 * @param qualifiedName 实体全限定名
 * @param table 表名
 * @param id 主键列
 * @param columns 全部列（含主键，按声明顺序，父类字段在前）
 * @param associations 一对多关联（仅根实体有；关联实体的快照只用到表名与列清单）
 */
record EntityMetadata(String qualifiedName, String table, EntityMetadata.Column id, List<Column> columns,
		List<Association> associations) {

	EntityMetadata(String qualifiedName, String table, Column id, List<Column> columns) {
		this(qualifiedName, table, id, columns, List.of());
	}

	/** INSERT 的列：自增主键交由数据库生成，不写进列清单。 */
	List<Column> insertColumns() {
		return this.id.generated() ? updateColumns() : this.columns;
	}

	/** 非主键列，用于 UPDATE 的 SET 片段和自增主键实体的 INSERT。 */
	List<Column> updateColumns() {
		return this.columns.stream().filter(column -> !column.id()).toList();
	}

	/** 主键类型的装箱形态，用于从结果集按类型取关联外键。 */
	String idValueType() {
		return switch (this.id.javaType()) {
			case "byte" -> "java.lang.Byte";
			case "short" -> "java.lang.Short";
			case "int" -> "java.lang.Integer";
			case "long" -> "java.lang.Long";
			case "float" -> "java.lang.Float";
			case "double" -> "java.lang.Double";
			case "boolean" -> "java.lang.Boolean";
			case "char" -> "java.lang.Character";
			default -> this.id.javaType();
		};
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

	/**
	 * 一对多关联：父实体上的集合字段，子表用外键指回父表。
	 *
	 * <p>加载形态固定为「两条语句 + 按外键分组装配」，不是 {@code JOIN} 笛卡尔展开，
	 * 行数受 {@code FetchPolicy.associationLimit()} 约束；嵌套一层后不再深入。</p>
	 *
	 * @param field 集合字段名
	 * @param getter 读取方法名
	 * @param setter 写入方法名
	 * @param collectionType 集合类型全限定名（{@code java.util.List} / {@code java.util.Set} /
	 *            {@code java.util.Collection}）
	 * @param elementType 元素（子实体）类型全限定名
	 * @param foreignKeyColumn 子表中指回父表的主键列
	 * @param target 子实体元数据（表名与列清单）
	 * @param eager 是否在查询后立即装配（{@code fetch = LAZY} 时为 false）
	 */
	record Association(String field, String getter, String setter, String collectionType, String elementType,
			String foreignKeyColumn, EntityMetadata target, boolean eager) {

		/** properties 里的语句键，例如 {@code ordersByParentIds}。 */
		String statementKey() {
			return this.field + "ByParentIds";
		}

		/** 生成的子行映射器字段名，例如 {@code orderItems} → {@code ORDER_ITEMS_ROW_MAPPER}。 */
		String mapperField() {
			return EntityIntrospector.snakeCase(this.field).toUpperCase() + "_ROW_MAPPER";
		}

		/** 生成的子实体构造方法名，例如 {@code orderItems} → {@code orderItemRow}。 */
		String rowMethod() {
			String singular = this.field.endsWith("s") ? this.field.substring(0, this.field.length() - 1)
					: this.field;
			return singular.substring(0, 1).toLowerCase() + singular.substring(1) + "Row";
		}

		/** 生成的装配方法名，例如 {@code orderItems} → {@code attachOrderItems}。 */
		String attachMethod() {
			return "attach" + this.field.substring(0, 1).toUpperCase() + this.field.substring(1);
		}

		/** 子实体主键列名（分组顺序与结果稳定用）。 */
		String targetIdColumn() {
			return this.target.id().column();
		}
	}
}
