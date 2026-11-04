package xyz.wewin.autumn.data.common.meta;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MetaUtil 反向序列化（MetaClass / MetaCollection → Map / JSON）验证：
 * 正向转换的逆操作、null 值保留策略、标量类型保持、JSON 往返稳定。
 *
 * @author: baoxin.zhao
 * @date: 2026/11/4
 */
public class MetaUtilSerializeTests {

    private String readJson(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("测试资源未找到: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @Test
    void toMap_should_reverse_forward_conversion() {
        MetaClass market = MetaUtil.metaClass("Market", readJson("/222.json"));

        Map<String, Object> map = MetaUtil.toMap(market);

        // 标量
        assertEquals(10, map.get("expensive"));
        // 嵌套对象 store -> Map
        Map<String, Object> store = asMap(map.get("store"));
        // 集合 book -> List<Map>
        List<Map<String, Object>> books = asMaps(store.get("book"));
        assertEquals(4, books.size());
        assertEquals("reference", books.get(0).get("category"));
        assertEquals(8.95, books.get(0).get("price"));
        assertEquals("fiction", books.get(1).get("category"));
        // 嵌套对象 bicycle
        Map<String, Object> bicycle = asMap(store.get("bicycle"));
        assertEquals("red", bicycle.get("color"));
        assertEquals(3, bicycle.get("count"));
    }

    @Test
    void toMap_should_preserve_scalar_types() {
        MetaClass market = MetaUtil.metaClass("Market", readJson("/222.json"));
        Map<String, Object> map = MetaUtil.toMap(market);

        assertInstanceOf(Integer.class, map.get("expensive"));
        Map<String, Object> bicycle = asMap(asMap(map.get("store")).get("bicycle"));
        assertInstanceOf(Double.class, bicycle.get("price"));
        assertInstanceOf(Integer.class, bicycle.get("count"));
    }

    @Test
    void toMap_should_include_null_when_includeNull_true() {
        String json = "{\"store\":{\"book\":[],\"bicycle\":{\"color\":\"blue\"}},\"expensive\":null}";
        MetaClass market = MetaUtil.metaClass("Market", json);

        Map<String, Object> map = MetaUtil.toMap(market);
        assertTrue(map.containsKey("expensive"));
        assertNull(map.get("expensive"));
        Map<String, Object> bicycle = asMap(asMap(map.get("store")).get("bicycle"));
        assertTrue(bicycle.containsKey("price"));
        assertNull(bicycle.get("price"));
    }

    @Test
    void toMap_should_skip_null_when_includeNull_false() {
        String json = "{\"store\":{\"book\":[],\"bicycle\":{\"color\":\"blue\"}},\"expensive\":null}";
        MetaClass market = MetaUtil.metaClass("Market", json);

        Map<String, Object> map = MetaUtil.toMap(market, false);
        assertFalse(map.containsKey("expensive"));
        Map<String, Object> bicycle = asMap(asMap(map.get("store")).get("bicycle"));
        assertFalse(bicycle.containsKey("price"));
    }

    @Test
    void toJson_should_round_trip() {
        MetaClass market = MetaUtil.metaClass("Market", readJson("/222.json"));
        String json = MetaUtil.toJson(market);

        // JSON -> 元类 -> Map，与直接 toMap 等价（往返稳定，无信息丢失）
        MetaClass reparsed = MetaUtil.metaClass("Market", json);
        assertEquals(MetaUtil.toMap(market), MetaUtil.toMap(reparsed));
    }

    @Test
    void toMaps_and_toJson_should_handle_collection() {
        MetaCollection mc = MetaUtil.metaCollection("Market", readJson("/333.json"));

        List<Map<String, Object>> maps = MetaUtil.toMaps(mc);
        assertEquals(2, maps.size());
        assertEquals(10, maps.get(0).get("expensive"));
        assertEquals(9, maps.get(1).get("expensive"));

        String json = MetaUtil.toJson(mc);
        MetaCollection reparsed = MetaUtil.metaCollection("Market", json);
        assertEquals(maps, MetaUtil.toMaps(reparsed));
    }
}
