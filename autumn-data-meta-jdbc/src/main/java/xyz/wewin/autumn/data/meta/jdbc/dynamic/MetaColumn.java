package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.sql.Types;
import java.util.Objects;

/**
 * 动态表的一列：物理列名 + JDBC 类型 + 是否主键/自增/可空。
 *
 * <p>由 {@link MetaTableRegistry} 从 {@link java.sql.DatabaseMetaData} 读取，是运行期快照的一部分，
 * 创建后不可变；主键标记通过 {@link #asPrimaryKey()} 产生新实例，避免共享对象被改写。</p>
 */
public record MetaColumn(
		String name,
		int jdbcType,
		String typeName,
		boolean nullable,
		boolean autoIncrement,
		boolean primaryKey,
		String defaultValue) {

	public MetaColumn {
		Objects.requireNonNull(name, "name");
	}

	/** 同一列的主键标记副本（元数据快照不可变，主键信息在读完主键后才补齐）。 */
	public MetaColumn asPrimaryKey() {
		return new MetaColumn(this.name, this.jdbcType, this.typeName, this.nullable, this.autoIncrement, true,
				this.defaultValue);
	}

	/** 数值型（用于判断逻辑删除列的默认值写法）。 */
	public boolean numeric() {
		return switch (this.jdbcType) {
			case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL,
					Types.REAL, Types.FLOAT, Types.DOUBLE -> true;
			default -> false;
		};
	}

	/** 布尔型。 */
	public boolean booleanType() {
		return this.jdbcType == Types.BOOLEAN || this.jdbcType == Types.BIT;
	}

	/** 字符型。 */
	public boolean stringType() {
		return switch (this.jdbcType) {
			case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> true;
			default -> false;
		};
	}
}
