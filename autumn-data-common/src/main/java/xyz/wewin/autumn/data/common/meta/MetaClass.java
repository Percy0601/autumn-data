package xyz.wewin.autumn.data.common.meta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * <p>两种形态共用同一套变更追踪（{@link #markChanged(String)} / {@link #isChanged(String)}）：
 * 写值才算脏，从结果集读值不算脏，因此「部分更新只写改过的列」这件事不依赖调用方额外传一个字段清单。</p>
 *
 * @author: baoxin.zhao
 * @date: 2026/2/26
 */
public class MetaClass {

    private String name;
    private String namespace;
    private MetaType metaType;
    private List<MetaProperty> properties;

    /**
     * 被写过的属性名（小写归一），为「部分更新」服务。
     *
     * <p>脏集为空即代表「这行和库里一致」：从结果集映射出来的行走直接字段赋值，不写这里；
     * 调用方经 {@link #set(String, Object)} 或生成类的类型化 setter 改动才写。于是
     * {@code update(entity)} 只写真正碰过的列，不会把没看的列顺手刷成当前值。
     * 懒分配，未改动过的实体不占这份内存。</p>
     */
    private Set<String> changed;

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

    /** 按属性名写值（大小写不敏感）；属性不存在时追加一个动态属性。写入即标脏。 */
    public void set(String propertyName, Object value) {
        MetaProperty property = property(propertyName);
        if (property != null) {
            property.setValue(value);
            markChanged(propertyName);
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
        markChanged(propertyName);
    }

    // ------------------------------------------------------------------
    // 变更追踪：部分更新的唯一依据
    // ------------------------------------------------------------------

    /**
     * 记下一次「这一列被写过」。
     *
     * <p>基类的 {@link #set(String, Object)} 已经调用它；运行期生成的实体子类因为走真实字段，
     * 必须在自己的类型化 setter 里显式调用，否则那一次改动对更新层不可见。
     * 从数据库读取结果集时<b>不要</b>调用——读出来的行应当是干净的。</p>
     */
    public void markChanged(String propertyName) {
        if (propertyName == null || propertyName.isEmpty()) {
            return;
        }
        if (this.changed == null) {
            this.changed = new LinkedHashSet<>();
        }
        this.changed.add(propertyName.toLowerCase(Locale.ROOT));
    }

    /** 该属性是否被写过（大小写不敏感）；干净实体上恒为 {@code false}。 */
    public boolean isChanged(String propertyName) {
        return this.changed != null && propertyName != null
                && this.changed.contains(propertyName.toLowerCase(Locale.ROOT));
    }

    /** 脏属性名的只读视图（小写归一）；没有改动时为空集。 */
    public Set<String> changedNames() {
        return this.changed == null ? Set.of() : Collections.unmodifiableSet(this.changed);
    }

    /** 是否有任何改动。更新入口用它拒绝「无意义的空 UPDATE」。 */
    public boolean hasChanges() {
        return this.changed != null && !this.changed.isEmpty();
    }

    /**
     * 清脏，让实体回到「与库中一致」。
     *
     * <p>写库成功后由数据访问层调用：主键回填一类的内部写也走 {@link #set(String, Object)}，
     * 不清就会让下一次更新把主键当成待写列。</p>
     */
    public void clearChanged() {
        this.changed = null;
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
