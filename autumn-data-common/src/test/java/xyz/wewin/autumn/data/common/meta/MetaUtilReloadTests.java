package xyz.wewin.autumn.data.common.meta;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MetaUtil 元模型热更新与校验验证。
 *
 * <p>热更新测试会改动全局元模型快照，每个用例结束后通过 {@link MetaUtil#reload()}
 * 恢复 classpath 默认模型，避免污染其它用例。</p>
 *
 * @author: baoxin.zhao
 * @date: 2026/11/4
 */
public class MetaUtilReloadTests {

    private static final String META_CLASS_FQN = "xyz.wewin.autumn.data.common.meta.MetaClass";
    private static final String META_COLLECTION_FQN = "xyz.wewin.autumn.data.common.meta.MetaCollection";

    /** 合法模型：Person（含嵌套 Address）+ Address，供热更新成功场景使用。 */
    private static final String MODEL_OK = "{"
            + "\"metaClasses\":["
            + "{\"name\":\"Person\",\"namespace\":\"default\",\"metaType\":\"ENTITY\","
            + "\"properties\":["
            + "{\"name\":\"id\",\"type\":\"java.lang.Long\"},"
            + "{\"name\":\"name\",\"type\":\"java.lang.String\"},"
            + "{\"name\":\"address\",\"type\":\"" + META_CLASS_FQN + "\",\"typeName\":\"Address\"}"
            + "]},"
            + "{\"name\":\"Address\",\"namespace\":\"default\",\"metaType\":\"ENTITY\","
            + "\"properties\":[{\"name\":\"city\",\"type\":\"java.lang.String\"}]}"
            + "]}";

    @AfterEach
    void restoreDefaultModel() {
        MetaUtil.reload();
    }

    @Test
    void reload_should_publish_new_model() {
        MetaUtil.reload(MODEL_OK);

        MetaClass person = MetaUtil.metaClass("Person", "{\"id\":1,\"name\":\"张三\",\"address\":{\"city\":\"北京\"}}");
        assertEquals("Person", person.getName());

        MetaProperty id = findProperty(person, "id");
        assertEquals(1L, id.getValue());
        assertEquals("张三", findProperty(person, "name").getValue());

        // 嵌套对象走新模型的引用
        MetaClass address = (MetaClass) findProperty(person, "address").getValue();
        assertEquals("北京", findProperty(address, "city").getValue());
    }

    @Test
    void reload_should_replace_old_model() {
        MetaUtil.reload(MODEL_OK);

        // 旧模型 Market 已被整体替换
        assertThrows(IllegalArgumentException.class,
                () -> MetaUtil.metaClass("Market", "{}"),
                "热更新后旧元类不应再可用");
    }

    @Test
    void reload_should_reject_undefined_typeName_and_keep_old_model() {
        String broken = "{"
                + "\"metaClasses\":["
                + "{\"name\":\"A\",\"namespace\":\"default\",\"metaType\":\"VO\","
                + "\"properties\":[{\"name\":\"b\",\"type\":\"" + META_CLASS_FQN + "\",\"typeName\":\"NotExist\"}]}"
                + "]}";

        assertThrows(IllegalArgumentException.class, () -> MetaUtil.reload(broken),
                "引用未定义元类应被校验拒绝");
        // 旧模型保持可用
        assertDoesNotThrow(() -> MetaUtil.metaClass("Market", "{}"));
    }

    @Test
    void reload_should_reject_missing_typeName() {
        String broken = "{"
                + "\"metaClasses\":["
                + "{\"name\":\"A\",\"namespace\":\"default\",\"metaType\":\"VO\","
                + "\"properties\":[{\"name\":\"b\",\"type\":\"" + META_COLLECTION_FQN + "\"}]}"
                + "]}";

        assertThrows(IllegalArgumentException.class, () -> MetaUtil.reload(broken),
                "结构类型缺少 typeName 应被校验拒绝");
    }

    @Test
    void reload_should_reject_duplicate_property_names() {
        String broken = "{"
                + "\"metaClasses\":["
                + "{\"name\":\"A\",\"namespace\":\"default\",\"metaType\":\"VO\","
                + "\"properties\":["
                + "{\"name\":\"x\",\"type\":\"java.lang.String\"},"
                + "{\"name\":\"x\",\"type\":\"java.lang.Integer\"}"
                + "]}]}";

        assertThrows(IllegalArgumentException.class, () -> MetaUtil.reload(broken),
                "同一元类内属性名重复应被校验拒绝");
        assertDoesNotThrow(() -> MetaUtil.metaClass("Market", "{}"));
    }

    @Test
    void reload_should_reject_duplicate_meta_class() {
        String broken = "{"
                + "\"metaClasses\":["
                + "{\"name\":\"A\",\"namespace\":\"default\",\"metaType\":\"VO\"},"
                + "{\"name\":\"A\",\"namespace\":\"default\",\"metaType\":\"VO\"}"
                + "]}";

        assertThrows(IllegalStateException.class, () -> MetaUtil.reload(broken),
                "元类重复定义应被拒绝");
    }

    @Test
    void reload_should_reject_invalid_meta_type() {
        String broken = "{"
                + "\"metaClasses\":["
                + "{\"name\":\"A\",\"namespace\":\"default\",\"metaType\":\"NOT_A_TYPE\"}"
                + "]}";

        assertThrows(IllegalArgumentException.class, () -> MetaUtil.reload(broken),
                "非法 metaType 应被拒绝");
    }

    private MetaProperty findProperty(MetaClass metaClass, String name) {
        List<MetaProperty> properties = metaClass.getProperties();
        if (properties == null) {
            throw new AssertionError("元类无属性: " + metaClass.getName());
        }
        return properties.stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("属性不存在: " + name + " in " + metaClass.getName()));
    }
}
