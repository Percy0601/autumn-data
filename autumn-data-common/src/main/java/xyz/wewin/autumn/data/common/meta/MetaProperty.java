package xyz.wewin.autumn.data.common.meta;

import java.sql.Types;

/**
 * 元属性：既描述 JSON 元模型里的字段，也可承载数据库列的物理信息。
 *
 * <p>JSON 元模型只关心 {@code name / value / type / typeName}；
 * 动态表场景额外使用 {@code jdbcType / sqlTypeName / nullable / primaryKey / autoIncrement / defaultValue}。
 * 两套信息放在同一个对象上，避免再维护一份“列描述”。</p>
 *
 * @author: baoxin.zhao
 * @date: 2026/2/26
 */
public class MetaProperty {
    private String name;
    private Object value;
    private String type;
    private String typeName;

    /** JDBC 类型，取值见 {@link Types}；默认 {@link Types#OTHER}。 */
    private int jdbcType = Types.OTHER;

    /** 数据库原始类型名（如 VARCHAR / DECIMAL / TIMESTAMP）。 */
    private String sqlTypeName;

    /** 是否允许 NULL；默认 true。 */
    private boolean nullable = true;

    /** 是否主键列。 */
    private boolean primaryKey;

    /** 是否自增列。 */
    private boolean autoIncrement;

    /** 数据库默认值表达式（可能为 null）。 */
    private String defaultValue;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public int getJdbcType() {
        return jdbcType;
    }

    public void setJdbcType(int jdbcType) {
        this.jdbcType = jdbcType;
    }

    public String getSqlTypeName() {
        return sqlTypeName;
    }

    public void setSqlTypeName(String sqlTypeName) {
        this.sqlTypeName = sqlTypeName;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
    }

    public boolean isAutoIncrement() {
        return autoIncrement;
    }

    public void setAutoIncrement(boolean autoIncrement) {
        this.autoIncrement = autoIncrement;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }
}
