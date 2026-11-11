package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;

/**
 * 动态行：物理列名 → 原始值，外加按 {@link MetaTable} 做的强类型读取。
 *
 * <p>动态单据没有实体类，这一层就是“运行时实体”。{@link #toMap()} 输出保持 SELECT 的列顺序，
 * 方便直接序列化成接口响应。</p>
 */
public final class MetaRow {

	private final MetaTable table;

	private final Map<String, Object> values;

	MetaRow(MetaTable table, Map<String, Object> values) {
		this.table = table;
		this.values = new LinkedHashMap<>(values);
	}

	/** 当前行对应的表描述。 */
	public MetaTable table() {
		return this.table;
	}

	/** 按列名取原始值（大小写不敏感）。 */
	public Object get(String column) {
		MetaColumn found = this.table.requireColumn(column);
		if (this.values.containsKey(found.name())) {
			return this.values.get(found.name());
		}
		for (Map.Entry<String, Object> entry : this.values.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(found.name())) {
				return entry.getValue();
			}
		}
		return null;
	}

	public String getString(String column) {
		Object value = get(column);
		return value == null ? null : String.valueOf(value);
	}

	public Integer getInt(String column) {
		Object value = get(column);
		return value == null ? null : MetaTypeCoercion.toInt(this.table.requireColumn(column), value);
	}

	public Long getLong(String column) {
		Object value = get(column);
		return value == null ? null : MetaTypeCoercion.toLong(this.table.requireColumn(column), value);
	}

	public BigDecimal getBigDecimal(String column) {
		Object value = get(column);
		return value == null ? null : MetaTypeCoercion.toBigDecimal(this.table.requireColumn(column), value);
	}

	public Boolean getBoolean(String column) {
		Object value = get(column);
		return value == null ? null : MetaTypeCoercion.toBoolean(this.table.requireColumn(column), value);
	}

	public LocalDate getLocalDate(String column) {
		Object value = get(column);
		return value == null ? null : MetaTypeCoercion.toLocalDate(this.table.requireColumn(column), value);
	}

	public LocalDateTime getLocalDateTime(String column) {
		Object value = get(column);
		return value == null ? null : MetaTypeCoercion.toLocalDateTime(this.table.requireColumn(column), value);
	}

	/** 全部列值（物理列名，保持 SELECT 顺序）。 */
	public Map<String, Object> toMap() {
		return new LinkedHashMap<>(this.values);
	}

	/** 按 {@link MetaTable#columns()} 的顺序读取当前行。 */
	public static RowMapper<MetaRow> mapper(MetaTable table) {
		return (ResultSet rs, int rowNum) -> {
			java.sql.ResultSetMetaData metaData = rs.getMetaData();
			Map<String, Object> values = new LinkedHashMap<>();
			for (int index = 1; index <= metaData.getColumnCount(); index++) {
				values.put(metaData.getColumnLabel(index), rs.getObject(index));
			}
			return new MetaRow(table, values);
		};
	}
}
