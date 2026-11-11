package xyz.wewin.autumn.data.common.meta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 元类：既是 JSON 元模型的描述对象，也是运行期实体的公共契约。
 *
 * <p>两种用法共用同一层 API：</p>
 * <ul>
 *   <li><b>完全动态</b>：直接 {@code new MetaClass()} 后 {@code setProperties(...)}，
 *       {@link #get(String)} / {@link #set(String, Object)} 走属性列表（本质是一个带类型的 Map）；</li>
 *   <li><b>运行期生成的实体</b>：{@code autumn-data-meta-jdbc} 用 FreeMarker 生成
 *       {@code MetaClass} 的子类并编译加载，子类有真实字段与类型化 getter，
 *       覆写 {@link #get(String)} / {@link #getProperties()} 后直接读字段——
 *       <b>逐行映射零反射</b>，静态类型又能保留。</li>
 * </ul>
 *
 * <p>调用方只需要面向 {@code MetaClass} 编程：拿到的究竟是通用容器还是生成类，
 * 由表结构在运行期决定，这正是「介于完全动态与完全静态之间」的那一层。</p>
 *
 * @author: baoxin.zhao
 * @date: 2026/2/26
 */
public class MetaClass {

    private String name;
    private String namespace;
    private MetaType metaType;
    private List<MetaProperty> properties;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public MetaType getMetaType() {
        return metaType;
    }

    public void setMetaType(MetaType metaType) {
        this.metaType = metaType;
    }

    public List<MetaProperty> getProperties() {
        return properties;
    }

    public void setProperties(List<MetaProperty> properties) {
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // 公共契约：按属性名读写（大小写不敏感）
    // 运行期生成的子类会覆写 get/set，把这段遍历换成直接字段访问
    // ------------------------------------------------------------------

    /**
     * 按属性名取描述对象；不存在返回 {@code null}。
     *
     * <p>刻意走 {@link #getProperties()} 这个可覆写方法而不是直接读字段：运行期生成的
     * 实体子类用真实字段摊出元属性视图，基类这一层必须能看到它们。</p>
     */
    public MetaProperty property(String propertyName) {
        if (propertyName == null) {
            return null;
        }
        List<MetaProperty> current = getProperties();
        if (current == null) {
            return null;
        }
        for (MetaProperty property : current) {
            if (property != null && property.getName() != null
                    && property.getName().equalsIgnoreCase(propertyName)) {
                return property;
            }
        }
        return null;
    }

    /** 按属性名取值（大小写不敏感）；不存在返回 {@code null}。 */
    public Object get(String propertyName) {
        MetaProperty property = property(propertyName);
        return property == null ? null : property.getValue();
    }

    /** 按属性名写值（大小写不敏感）；属性不存在时追加一个动态属性。 */
    public void set(String propertyName, Object value) {
        MetaProperty property = property(propertyName);
        if (property != null) {
            property.setValue(value);
            return;
        }
        if (propertyName == null) {
            throw new IllegalArgumentException("property name must not be null");
        }
        MetaProperty added = new MetaProperty();
        added.setName(propertyName);
        added.setValue(value);
        List<MetaProperty> snapshot = getProperties();
        List<MetaProperty> current = snapshot == null ? new ArrayList<>() : new ArrayList<>(snapshot);
        current.add(added);
        setProperties(current);
    }

    // ------------------------------------------------------------------
    // 类型化读取：基类按值转换，生成子类可直接覆写成字段访问
    // ------------------------------------------------------------------

    public String getString(String propertyName) {
        return MetaValueReader.asString(get(propertyName));
    }

    public Integer getInt(String propertyName) {
        return MetaValueReader.asInt(get(propertyName));
    }

    public Long getLong(String propertyName) {
        return MetaValueReader.asLong(get(propertyName));
    }

    public Double getDouble(String propertyName) {
        return MetaValueReader.asDouble(get(propertyName));
    }

    public BigDecimal getBigDecimal(String propertyName) {
        return MetaValueReader.asBigDecimal(get(propertyName));
    }

    public Boolean getBoolean(String propertyName) {
        return MetaValueReader.asBoolean(get(propertyName));
    }

    public LocalDate getLocalDate(String propertyName) {
        return MetaValueReader.asLocalDate(get(propertyName));
    }

    public LocalTime getLocalTime(String propertyName) {
        return MetaValueReader.asLocalTime(get(propertyName));
    }

    public LocalDateTime getLocalDateTime(String propertyName) {
        return MetaValueReader.asLocalDateTime(get(propertyName));
    }

    /** 转成有序 Map（键为属性名，null 保留）。 */
    public Map<String, Object> toMap() {
        return MetaUtil.toMap(this);
    }

    /** 转成 JSON 字符串（null 保留）。 */
    public String toJson() {
        return MetaUtil.toJson(this);
    }

    /** 是否包含指定属性（大小写不敏感）。 */
    public boolean has(String propertyName) {
        return property(propertyName) != null;
    }

    /** 属性名归一：生成实体与元数据对齐时用。 */
    protected static String normalize(String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "MetaClass{" + "name='" + name + '\'' + ", namespace='" + namespace + '\'' + ", metaType=" + metaType + ", properties=" + properties + '}';
    }
}
