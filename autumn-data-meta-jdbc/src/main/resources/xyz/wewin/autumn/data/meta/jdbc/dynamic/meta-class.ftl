<#-- FreeMarker 模板：按数据库表结构生成 MetaClass 的实体子类源码。
     生成物是可读、可落盘、可固化成静态类的 .java，不是运行时拼 Map。 -->
package ${packageName};

// generated-by: autumn-data-meta-jdbc (FreeMarker + javax.tools.JavaCompiler)
// structure-id: ${fingerprint}
// source table: ${tableNameLiteral}
// 本类由运行期表结构生成：字段/getter/setter/结果集映射全部是静态调用，逐行零反射。
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.common.meta.MetaType;
import xyz.wewin.autumn.data.common.meta.MetaValueReader;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaEntityColumns;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaEntityReader;

public final class ${className} extends MetaClass {

    public static final String TABLE_NAME = ${tableNameLiteral};
    public static final String TABLE_SCHEMA = ${schemaLiteral};
    public static final String STRUCTURE_ID = ${fingerprintLiteral};
    public static final List<String> COLUMNS = List.of(
<#list columns as c>
            ${c.nameLiteral}<#if c.hasNext>,</#if>
</#list>
    );

    /** 编译期未知的结构在加载这一刻固化：之后每行数据只走调用，不查元数据、不走反射。 */
    public static final MetaEntityReader READER = ${className}::read;

<#list columns as c>
    private ${c.javaType} ${c.field};
</#list>

    public ${className}() {
        setName(TABLE_NAME);
        setNamespace(TABLE_SCHEMA);
        setMetaType(MetaType.META_TYPE_ENTITY);
    }

<#list columns as c>
    // ${c.nameLiteral} (${c.sqlTypeNameForComment})
    public ${c.javaType} ${c.getter}() {
        return this.${c.field};
    }

    public void ${c.setter}(${c.javaType} value) {
        this.${c.field} = value;
        markChanged(${c.nameLiteral});
    }

</#list>
    /** 逐行读取：先建列标签索引，投影查询缺列时字段保持 null。直写字段不标脏——读出来的行是干净的。 */
    public static ${className} read(ResultSet rs) throws SQLException {
        ${className} entity = new ${className}();
        Map<String, Integer> cols = MetaEntityColumns.index(rs);
        Integer index;
        Object raw;
<#list columns as c>
        index = cols.get(${c.lowerLiteral});
        if (index != null) {
            raw = rs.getObject(index);
            entity.${c.field} = raw == null ? null : MetaValueReader.${c.reader}(raw);
        }
</#list>
        return entity;
    }

    /** 按列名读取（大小写不敏感）：生成的 switch 直达字段，不经属性列表。 */
    @Override
    public Object get(String name) {
        if (name == null) {
            return null;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
<#list columns as c>
            case ${c.lowerLiteral}:
                return this.${c.field};
</#list>
            default:
                return super.get(name);
        }
    }

    /** 按列名写入（大小写不敏感）：值按列的 Java 类型收口，写完标脏。 */
    @Override
    public void set(String name, Object value) {
        if (name == null) {
            return;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
<#list columns as c>
            case ${c.lowerLiteral}:
                this.${c.field} = value == null ? null : MetaValueReader.${c.reader}(value);
                break;
</#list>
            default:
                super.set(name, value);
                return;
        }
        markChanged(name);
    }

    /** 元模型视图：把真实字段摊成 MetaProperty，MetaUtil.toMap/toJson 直接可用。 */
    @Override
    public List<MetaProperty> getProperties() {
        List<MetaProperty> properties = new ArrayList<>(${columnCount});
<#list columns as c>
        properties.add(prop(${c.nameLiteral}, ${c.typeLiteral}, ${c.jdbcType}, ${c.sqlTypeLiteral},
                ${c.nullable}, ${c.primaryKey}, ${c.autoIncrement}, ${c.defaultLiteral}, this.${c.field}));
</#list>
        return properties;
    }

    private static MetaProperty prop(String name, String type, int jdbcType, String sqlTypeName,
            boolean nullable, boolean primaryKey, boolean autoIncrement, String defaultValue, Object value) {
        MetaProperty property = new MetaProperty();
        property.setName(name);
        property.setType(type);
        property.setTypeName(type);
        property.setJdbcType(jdbcType);
        property.setSqlTypeName(sqlTypeName);
        property.setNullable(nullable);
        property.setPrimaryKey(primaryKey);
        property.setAutoIncrement(autoIncrement);
        property.setDefaultValue(defaultValue);
        property.setValue(value);
        return property;
    }

    @Override
    public String toString() {
        return "${className}{" + toMap() + "}";
    }
}
