package xyz.wewin.autumn.data.common.meta;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.jayway.jsonpath.Configuration;

/**
 * 元模型驱动的 JSON 转换器。
 *
 * <p>元模型定义外部化在 classpath:/meta-model.json 中，类加载时载入内存快照；
 * 支持运行期 {@link #reload(String)} 热更新（先校验后原子替换，非法模型拒绝生效、旧模型保持可用）；
 * 解析时以深拷贝出的元类副本为模板，按 JSON 数据递归填充属性值，模板本身保持纯净、可复用、可并发。</p>
 *
 * <p>属性类别约定（对应 MetaProperty.type 全限定名）：
 * <ul>
 *     <li>{@link MetaClass} 全限定名 = 嵌套对象，MetaProperty.typeName 指向目标元类名；</li>
 *     <li>{@link MetaCollection} 全限定名 = 对象集合，MetaProperty.typeName 指向元素元类名；</li>
 *     <li>其余为基础类型（Integer/Long/Double/String/BigDecimal 等）。</li>
 * </ul>
 * </p>
 *
 * @author: baoxin.zhao
 * @date: 2026/2/26
 */
public class MetaUtil {
    private static final String META_MODEL_RESOURCE = "/meta-model.json";
    private static final String DEFAULT_NAMESPACE = "default";

    /**
     * 元模型快照（不可变 Map，volatile 保证热更新发布可见性）。
     *
     * <p>读取路径无锁：{@link #metaClass} / {@link #metaCollection} 每次读取快照并深拷贝模板填值；
     * 热更新 {@link #reload(String)} 构建全新快照并通过校验后一次性原子替换，
     * 不会出现半更新状态，非法模型也不会覆盖旧模型。</p>
     */
    private static volatile Map<String, MetaClass> MAPPING;

    static {
        reload();
    }

    private MetaUtil() {
    }

    /**
     * 将单个 JSON 对象按元模型转换为元类对象树。
     *
     * @param type 元类名，支持简单名（Market）与命名空间限定名（com.foo.Market，namespace 未命中时回退 default）
     * @param json JSON 对象文本
     */
    public static MetaClass metaClass(String type, String json) {
        NameParts parts = parseType(type);
        MetaClass template = resolveClass(parts.namespace, parts.name);
        MetaClass metaClass = deepCopy(template);
        Object document = Configuration.defaultConfiguration().jsonProvider().parse(json);
        handleMetaClass(metaClass, document);
        return metaClass;
    }

    /**
     * 将 JSON 对象数组按元模型转换为元集合。
     *
     * @param type 元素元类名
     * @param json JSON 数组文本（顶层必须是数组，否则抛出 IllegalArgumentException）
     */
    public static MetaCollection metaCollection(String type, String json) {
        NameParts parts = parseType(type);
        MetaClass template = resolveClass(parts.namespace, parts.name);
        Object document = Configuration.defaultConfiguration().jsonProvider().parse(json);
        MetaCollection mc = new MetaCollection();
        mc.setName(parts.name);
        List<Map<String, Object>> values = castList(document, parts.name);
        List<MetaClass> mcs = new ArrayList<>(values.size());
        mc.setMetas(mcs);
        for (Map<String, Object> v : values) {
            MetaClass m = deepCopy(template);
            mcs.add(m);
            handleMetaClass(m, v);
        }
        return mc;
    }

    // ------------------------------------------------------------------
    // 递归填充
    // ------------------------------------------------------------------

    private static void handleMetaClass(MetaClass metaClass, Object document) {
        List<MetaProperty> properties = metaClass.getProperties();
        if (properties == null) {
            return;
        }
        for (MetaProperty metaProperty : properties) {
            String name = metaProperty.getName();
            Object value = readValue(document, name);
            if (value == null) {
                metaProperty.setValue(null);
                continue;
            }
            // JSON 里出现过的字段就是调用方「提供的」，等价于写过：部分更新据此判定待写列
            metaClass.markChanged(name);
            if (MetaClass.class.getName().equals(metaProperty.getType())) {
                MetaClass mc = deepCopy(resolveType(metaProperty));
                metaProperty.setValue(mc);
                handleMetaClass(mc, value);
                continue;
            }
            if (MetaCollection.class.getName().equals(metaProperty.getType())) {
                List<Map<String, Object>> vs = castList(value, name);
                List<MetaClass> mcs = new ArrayList<>(vs.size());
                for (Map<String, Object> v : vs) {
                    MetaClass m = deepCopy(resolveType(metaProperty));
                    mcs.add(m);
                    handleMetaClass(m, v);
                }
                metaProperty.setValue(mcs);
                continue;
            }
            handleProperties(value, metaProperty);
        }
    }

    private static void handleProperties(Object value, MetaProperty metaProperty) {
        String type = metaProperty.getType();
        if (Integer.class.getName().equals(type)) {
            metaProperty.setValue(value instanceof Integer ? value : Integer.valueOf(value.toString()));
            return;
        }
        if (Long.class.getName().equals(type)) {
            metaProperty.setValue(value instanceof Long ? value : Long.valueOf(value.toString()));
            return;
        }
        if (Double.class.getName().equals(type)) {
            metaProperty.setValue(value instanceof Double ? value : Double.valueOf(value.toString()));
            return;
        }
        if (String.class.getName().equals(type)) {
            metaProperty.setValue(value);
            return;
        }
        if (BigDecimal.class.getName().equals(type)) {
            metaProperty.setValue(new BigDecimal(value.toString()));
            return;
        }
        // 未注册的基础类型：宽容处理，原样保留，避免静默丢值
        metaProperty.setValue(value);
    }

    /**
     * 读取 JSON 对象中指定字段；字段缺失或值为 null 时返回 null。
     *
     * <p>性能优化：直接对已解析的 {@link Map} 做键查找，替代原先每次
     * {@code JsonPath.read(document, "$.name")} 的路径解析（默认 provider 解析出的
     * JSON 对象即为 {@link java.util.LinkedHashMap}），避免批量场景下
     * O(属性数 × 对象数) 的路径求值开销。</p>
     */
    @SuppressWarnings("unchecked")
    private static Object readValue(Object document, String name) {
        if (!(document instanceof Map)) {
            // 非对象文档（畸形输入 / 顶层数组误用）：按缺字段宽容处理
            return null;
        }
        return ((Map<String, Object>) document).get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value, String context) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("字段不是 JSON 数组，无法解析为元集合: " + context);
        }
        List<?> raw = (List<?>) value;
        for (Object item : raw) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("集合元素不是 JSON 对象: " + context + " -> " + item);
            }
        }
        return (List<Map<String, Object>>) raw;
    }

    // ------------------------------------------------------------------
    // 反向序列化：元类对象树 -> Map / JSON（回显 / 写库）
    // ------------------------------------------------------------------

    /**
     * 将元类对象树转换为 {@link Map}（含全部已声明属性，null 值保留）。
     *
     * <p>与 {@link #metaClass(String, String)} 互为逆操作：嵌套 {@link MetaClass} 递归为 Map，
     * {@link List}&lt;{@link MetaClass}&gt; 递归为 List&lt;Map&gt;，标量原值透传。</p>
     *
     * @param metaClass 元类实例（来自 {@link #metaClass} 或 {@link #metaCollection} 的元素）
     * @return 有序 Map（LinkedHashMap），键为属性名
     */
    public static Map<String, Object> toMap(MetaClass metaClass) {
        return toMap(metaClass, true);
    }

    /**
     * 同 {@link #toMap(MetaClass)}，可控制是否保留 null 值属性。
     *
     * @param includeNull true 保留 null 值属性（写库需完整列集）；false 忽略 null（回显更贴近原始入参形状）
     */
    public static Map<String, Object> toMap(MetaClass metaClass, boolean includeNull) {
        Objects.requireNonNull(metaClass, "metaClass 不能为 null");
        Map<String, Object> map = new LinkedHashMap<>();
        List<MetaProperty> properties = metaClass.getProperties();
        if (properties == null) {
            return map;
        }
        for (MetaProperty property : properties) {
            Object value = property.getValue();
            if (value == null) {
                if (includeNull) {
                    map.put(property.getName(), null);
                }
                continue;
            }
            if (value instanceof MetaClass) {
                map.put(property.getName(), toMap((MetaClass) value, includeNull));
                continue;
            }
            if (value instanceof List) {
                map.put(property.getName(), toMaps((List<?>) value, includeNull));
                continue;
            }
            // 标量（含宽容策略透传的原始值）：原样放入
            map.put(property.getName(), value);
        }
        return map;
    }

    /**
     * 将元集合转换为 {@code List<Map>}（每个元素与 {@link #toMap(MetaClass)} 同规则，null 保留）。
     */
    public static List<Map<String, Object>> toMaps(MetaCollection collection) {
        Objects.requireNonNull(collection, "collection 不能为 null");
        List<MetaClass> metas = collection.getMetas();
        List<Map<String, Object>> maps = new ArrayList<>(metas == null ? 0 : metas.size());
        if (metas != null) {
            for (MetaClass metaClass : metas) {
                maps.add(toMap(metaClass, true));
            }
        }
        return maps;
    }

    /**
     * 将元类对象树序列化为 JSON 字符串（含 null 值属性）。
     *
     * <p>复用默认 provider 的 {@code toJson} 能力，无新增依赖；与 {@link #metaClass(String, String)}
     * 组合即可完成 对象 → JSON → 元类 → 对象 的完整往返。</p>
     */
    public static String toJson(MetaClass metaClass) {
        return toJson(metaClass, true);
    }

    /**
     * 同 {@link #toJson(MetaClass)}，可控制是否保留 null 值属性。
     */
    public static String toJson(MetaClass metaClass, boolean includeNull) {
        return Configuration.defaultConfiguration().jsonProvider().toJson(toMap(metaClass, includeNull));
    }

    /**
     * 将元集合序列化为 JSON 数组字符串（含 null 值属性）。
     */
    public static String toJson(MetaCollection collection) {
        return Configuration.defaultConfiguration().jsonProvider().toJson(toMaps(collection));
    }

    private static List<Object> toMaps(List<?> values, boolean includeNull) {
        List<Object> list = new ArrayList<>(values.size());
        for (Object item : values) {
            if (item instanceof MetaClass) {
                list.add(toMap((MetaClass) item, includeNull));
            } else {
                // 宽容策略透传的原始数组元素：原样保留
                list.add(item);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------
    // 元模型加载 / 热更新 / 校验与模板解析
    // ------------------------------------------------------------------

    /**
     * 从 classpath:{@code /meta-model.json} 重新加载元模型。
     *
     * <p>等价于 {@link #reload(String)}，用于默认外部化资源路径的热更新；类加载时首次调用。</p>
     */
    public static void reload() {
        try (InputStream in = MetaUtil.class.getResourceAsStream(META_MODEL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("元模型资源未找到: " + META_MODEL_RESOURCE);
            }
            reload(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            IllegalStateException error = new IllegalStateException("加载元模型失败: " + META_MODEL_RESOURCE);
            error.initCause(e);
            throw error;
        }
    }

    /**
     * 热更新元模型：以指定 JSON 文本替换当前模型并原子发布。
     *
     * <p>新模型先完整解析并通过 {@link #validateModel} 校验；任一环节失败都会抛出
     * {@link IllegalArgumentException} / {@link IllegalStateException} 且<b>旧模型保持生效</b>，
     * 调用方捕获后可按需回滚或重试，保证运行期模型始终是完整一致的快照。</p>
     *
     * @param json 与 meta-model.json 同构的元模型 JSON 文本（顶层须含 metaClasses 数组）
     */
    public static synchronized void reload(String json) {
        Map<String, MetaClass> model = parseMetaModel(json);
        validateModel(model);
        MAPPING = model;
    }

    private static Map<String, MetaClass> parseMetaModel(String json) {
        Object document = Configuration.defaultConfiguration().jsonProvider().parse(json);
        List<Map<String, Object>> classes = castList(((Map<String, Object>) document).get("metaClasses"), "metaClasses");
        Map<String, MetaClass> model = new HashMap<>();
        for (Map<String, Object> c : classes) {
            MetaClass mc = new MetaClass();
            mc.setName(asString(c.get("name"), "metaClass.name"));
            mc.setNamespace(asString(c.get("namespace"), "metaClass.namespace"));
            String metaTypeCode = asString(c.get("metaType"), "metaClass.metaType");
            if (metaTypeCode != null) {
                mc.setMetaType(MetaType.of(metaTypeCode));
            }
            List<MetaProperty> properties = new ArrayList<>();
            Object rawProps = c.get("properties");
            if (rawProps != null) {
                List<Map<String, Object>> props = castList(rawProps, mc.getName() + ".properties");
                for (Map<String, Object> p : props) {
                    MetaProperty mp = new MetaProperty();
                    mp.setName(asString(p.get("name"), mc.getName() + ".property.name"));
                    mp.setType(asString(p.get("type"), mc.getName() + ".property.type"));
                    mp.setTypeName(asString(p.get("typeName"), null));
                    properties.add(mp);
                }
            }
            mc.setProperties(properties);
            String key = keyOf(mc.getNamespace(), mc.getName());
            if (model.put(key, mc) != null) {
                throw new IllegalStateException("元模型重复定义: " + key);
            }
        }
        return model;
    }

    /**
     * 校验元模型结构完整性与引用完整性，任一失败抛出 {@link IllegalArgumentException}。
     *
     * <ul>
     *     <li>类级：name / namespace / metaType 已由解析阶段强制非空并校验枚举取值；</li>
     *     <li>属性级：name / type 非空，同一元类内属性名不重复；</li>
     *     <li>引用完整性：{@link MetaClass} / {@link MetaCollection} 结构类型必须声明 typeName，
     *         且目标元类必须已定义（解析规则含 default 命名空间回退，与运行期一致）。</li>
     * </ul>
     *
     * <p>标量属性 type 采用宽容策略：未知全限定名原样保留（运行时原值透传），仅校验非空，
     * 不做白名单限制，避免收紧既有语义。</p>
     */
    private static void validateModel(Map<String, MetaClass> model) {
        for (MetaClass mc : model.values()) {
            List<MetaProperty> properties = mc.getProperties();
            if (properties == null) {
                continue;
            }
            Set<String> seenNames = new HashSet<>();
            for (MetaProperty p : properties) {
                String propName = p.getName();
                if (propName == null || propName.isEmpty()) {
                    throw new IllegalArgumentException("元模型配置缺失字段: " + mc.getName() + ".property.name");
                }
                if (!seenNames.add(propName)) {
                    throw new IllegalArgumentException("元类属性重复定义: " + mc.getName() + "." + propName);
                }
                String type = p.getType();
                if (type == null || type.isEmpty()) {
                    throw new IllegalArgumentException("元模型配置缺失字段: " + mc.getName() + "." + propName + ".type");
                }
                if (MetaClass.class.getName().equals(type) || MetaCollection.class.getName().equals(type)) {
                    String typeName = p.getTypeName();
                    if (typeName == null || typeName.isEmpty()) {
                        throw new IllegalArgumentException("嵌套属性缺少 typeName，无法解析目标元类: " + mc.getName() + "." + propName);
                    }
                    NameParts parts = parseType(typeName);
                    // 引用完整性：目标元类必须已定义（含 default 命名空间回退）
                    resolveClass(parts.namespace, parts.name, model);
                }
            }
        }
    }

    private static String asString(Object value, String field) {
        if (value == null) {
            if (field != null) {
                throw new IllegalArgumentException("元模型配置缺失字段: " + field);
            }
            return null;
        }
        return String.valueOf(value);
    }

    /**
     * 解析元类引用，返回模板（共享、不可变语义，调用方必须深拷贝后再填充）。
     */
    private static MetaClass resolveType(MetaProperty metaProperty) {
        String typeName = metaProperty.getTypeName();
        if (typeName == null || typeName.isEmpty()) {
            throw new IllegalArgumentException("嵌套属性缺少 typeName，无法解析目标元类: " + metaProperty.getName());
        }
        NameParts parts = parseType(typeName);
        return resolveClass(parts.namespace, parts.name);
    }

    private static MetaClass resolveClass(String namespace, String name) {
        return resolveClass(namespace, name, MAPPING);
    }

    private static MetaClass resolveClass(String namespace, String name, Map<String, MetaClass> model) {
        MetaClass template = model.get(keyOf(namespace, name));
        if (template == null && !DEFAULT_NAMESPACE.equals(namespace)) {
            // 宽容匹配：命名空间未命中时回退 default 命名空间
            template = model.get(keyOf(DEFAULT_NAMESPACE, name));
        }
        if (template == null) {
            throw new IllegalArgumentException("未定义的元类: " + keyOf(namespace, name));
        }
        return template;
    }

    private static String keyOf(String namespace, String name) {
        return namespace.concat(".").concat(name);
    }

    /**
     * 按最后一个 '.' 切分命名空间与类名，避免 String.split 的正则陷阱。
     */
    private static NameParts parseType(String type) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("元类名不能为空");
        }
        int idx = type.lastIndexOf('.');
        if (idx < 0) {
            return new NameParts(DEFAULT_NAMESPACE, type);
        }
        if (idx == type.length() - 1) {
            throw new IllegalArgumentException("非法元类名（以 . 结尾）: " + type);
        }
        return new NameParts(type.substring(0, idx), type.substring(idx + 1));
    }

    // ------------------------------------------------------------------
    // 深拷贝：模板 -> 实例
    // ------------------------------------------------------------------

    /**
     * 深拷贝元类模板。properties 列表与每个 MetaProperty 均为新对象，
     * 保证实例填值不会写回模板缓存；value 属于运行时数据，不参与拷贝。
     */
    private static MetaClass deepCopy(MetaClass template) {
        MetaClass copy = new MetaClass();
        copy.setName(template.getName());
        copy.setNamespace(template.getNamespace());
        copy.setMetaType(template.getMetaType());
        List<MetaProperty> properties = template.getProperties();
        if (properties == null) {
            copy.setProperties(null);
            return copy;
        }
        List<MetaProperty> copied = new ArrayList<>(properties.size());
        for (MetaProperty p : properties) {
            MetaProperty np = new MetaProperty();
            np.setName(p.getName());
            np.setType(p.getType());
            np.setTypeName(p.getTypeName());
            np.setJdbcType(p.getJdbcType());
            np.setSqlTypeName(p.getSqlTypeName());
            np.setNullable(p.isNullable());
            np.setPrimaryKey(p.isPrimaryKey());
            np.setAutoIncrement(p.isAutoIncrement());
            np.setDefaultValue(p.getDefaultValue());
            copied.add(np);
        }
        copy.setProperties(copied);
        return copy;
    }

    private static final class NameParts {
        private final String namespace;
        private final String name;

        private NameParts(String namespace, String name) {
            this.namespace = namespace;
            this.name = name;
        }
    }
}
