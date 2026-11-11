package xyz.wewin.autumn.data.meta.jdbc;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Meta 引擎配置中心：注册编译期生成的 Mapper SQL 类，运行期按 namespace + id 查找。
 *
 * <p>替代原 FreeMarker 版 {@code MetaConfiguration}：<b>SQL 这条路</b>不再加载 .ftl 模板，
 * 而是持有编译期生成的 SQL 构建方法引用，零反射。FreeMarker 在本模块只用于
 * {@code dynamic.MetaEntityTemplate} 生成实体类源码，与 SQL 语句注册是两条独立的路。</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * MetaConfiguration config = new MetaConfiguration();
 * config.registerMapper("com.example.UserMapper", UserMapperSql::class);
 * // 或者注册单个方法：
 * config.registerStatement("com.example.UserMapper", "findByName", UserMapperSql::findByName);
 * }</pre>
 */
public class MetaConfiguration {

	/**
	 * namespace → (statementId → SqlBuild 构建函数)。
	 * 构建函数接收 {@code Map<String, Object>} 参数，返回 {@link SqlBuild}。
	 */
	private final Map<String, Map<String, Function<Map<String, Object>, SqlBuild>>> mappers = new HashMap<>();

	/**
	 * 注册一个编译期生成的 Mapper SQL 类。
	 *
	 * <p>通过反射扫描类上的静态方法自动注册。但为了零反射目标，
	 * 推荐用 {@link #registerStatement} 逐个注册。</p>
	 */
	public void registerMapper(String namespace, Map<String, Function<Map<String, Object>, SqlBuild>> statements) {
		mappers.computeIfAbsent(namespace, k -> new HashMap<>()).putAll(statements);
	}

	/**
	 * 注册单条语句的 SQL 构建函数。
	 *
	 * @param namespace Mapper 命名空间（对应 XML 的 namespace 属性）
	 * @param statementId 语句 ID（对应 XML 的 id 属性）
	 * @param builder 构建函数：接收参数 Map，返回 SqlBuild
	 */
	public void registerStatement(String namespace, String statementId,
			Function<Map<String, Object>, SqlBuild> builder) {
		mappers.computeIfAbsent(namespace, k -> new HashMap<>()).put(statementId, builder);
	}

	/**
	 * 查找已注册的 SQL 构建函数。
	 *
	 * @return 构建函数；未找到返回 null
	 */
	public Function<Map<String, Object>, SqlBuild> findStatement(String namespace, String statementId) {
		Map<String, Function<Map<String, Object>, SqlBuild>> statements = mappers.get(namespace);
		if (statements == null) {
			return null;
		}
		return statements.get(statementId);
	}

	/**
	 * 清空所有注册的 Mapper（用于测试或热重载场景）。
	 */
	public void refresh() {
		mappers.clear();
	}

}
