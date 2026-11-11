package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.common.meta.MetaType;
import xyz.wewin.autumn.data.common.meta.MetaUtil;
import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;

/**
 * 动态行：一张表的 {@link MetaClass} 实例，属性 {@code value} 已按结果集填好。
 *
 * <p>动态单据没有实体类，这一层就是“运行时实体”；{@link #metaClass()} 可直接交给
 * {@link MetaUtil#toMap(MetaClass)} / {@link MetaUtil#toJson(MetaClass)} 做序列化。</p>
 */
public final class MetaRow {

	private final MetaTable table;

	private final MetaClass metaClass;

	MetaRow(MetaTable table, MetaClass metaClass) {
		this.table = table;
		this.metaClass = metaClass;
	}

	/** 当前行对应的表描述。 */
	public MetaTable table() {
		return this.table;
	}

	/** 当前行的元类实例（属性 value 已填充）。 */
	public MetaClass metaClass() {
		return this.metaClass;
	}

	/** 按列名取原始值（大小写不敏感）。 */
	public Object get(String column) {
		MetaProperty found = this.table.requireColumn(column);
		for (MetaProperty property : this.metaClass.getProperties()) {
			if (property.getName().equalsIgnoreCase(found.getName())) {
				return property.getValue();
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

	/** 转成有序 Map（键为属性名，null 保留）。 */
	public Map<String, Object> toMap() {
		return MetaUtil.toMap(this.metaClass);
	}

	/** 转成 JSON 字符串（null 保留）。 */
	public String toJson() {
		return MetaUtil.toJson(this.metaClass);
	}

	/** 按结果集列标签逐列收集，投影查询也不会错位。 */
	public static RowMapper<MetaRow> mapper(MetaTable table) {
		return (ResultSet rs, int rowNum) -> {
			Map<String, Object> values = new LinkedHashMap<>();
			ResultSetMetaData metaData = rs.getMetaData();
			for (int index = 1; index <= metaData.getColumnCount(); index++) {
				values.put(metaData.getColumnLabel(index), rs.getObject(index));
			}
			MetaClass row = new MetaClass();
			row.setName(table.name());
			row.setNamespace(table.schema());
			row.setMetaType(MetaType.META_TYPE_ENTITY);
			List<MetaProperty> properties = new ArrayList<>(table.columns().size());
			for (MetaProperty column : table.columns()) {
				MetaProperty property = copy(column);
				property.setValue(valueOf(values, column.getName()));
				properties.add(property);
			}
			row.setProperties(properties);
			return new MetaRow(table, row);
		};
	}

	private static Object valueOf(Map<String, Object> values, String column) {
		if (values.containsKey(column)) {
			return values.get(column);
		}
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(column)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private static MetaProperty copy(MetaProperty source) {
		MetaProperty property = new MetaProperty();
		property.setName(source.getName());
		property.setType(source.getType());
		property.setTypeName(source.getTypeName());
		property.setJdbcType(source.getJdbcType());
		property.setSqlTypeName(source.getSqlTypeName());
		property.setNullable(source.isNullable());
		property.setPrimaryKey(source.isPrimaryKey());
		property.setAutoIncrement(source.isAutoIncrement());
		property.setDefaultValue(source.getDefaultValue());
		return property;
	}
}
