package xyz.wewin.autumn.data.meta.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;

/**
 * Meta SQL 会话：对标 MyBatis 的 {@code SqlSession}，为 Mapper 接口返回动态代理实现。
 *
 * <p>方法调用被路由到 {@link MetaConfiguration} 注册的 SQL 构建函数，
 * 生成 {@link SqlBuild} 后交给 {@link AutumnJdbc} 执行。</p>
 *
 * <p>方法签名约定：</p>
 * <ul>
 *   <li>参数：方法形参按名字注入参数 Map（编译期需开启 {@code -parameters}）</li>
 *   <li>返回 {@code List<T>}：走 {@link AutumnJdbc#query}；</li>
 *   <li>返回 {@code Optional<T>}：走 {@link AutumnJdbc#queryOptional}；</li>
 *   <li>返回 {@code long/int}：走 {@link AutumnJdbc#count} 或 {@link AutumnJdbc#update}；</li>
 *   <li>返回 {@code void}：走 {@link AutumnJdbc#update}。</li>
 * </ul>
 */
public class MetaSqlSession {

	private final MetaConfiguration configuration;

	private final AutumnJdbc autumnJdbc;

	public MetaSqlSession(MetaConfiguration configuration, AutumnJdbc autumnJdbc) {
		this.configuration = configuration;
		this.autumnJdbc = autumnJdbc;
	}

	/**
	 * 为 Mapper 接口返回动态代理实现。
	 *
	 * @param mapperInterface Mapper 接口 Class
	 * @param rowMapper 行映射器（select 查询时需要）
	 * @param <T> Mapper 接口类型
	 * @return 代理实例
	 */
	@SuppressWarnings("unchecked")
	public <T> T getMapper(Class<T> mapperInterface, RowMapper<?> rowMapper) {
		String namespace = mapperInterface.getName();
		return (T) Proxy.newProxyInstance(
				mapperInterface.getClassLoader(),
				new Class<?>[] { mapperInterface },
				new MapperInvocationHandler(namespace, rowMapper));
	}

	/**
	 * 为 Mapper 接口返回动态代理实现（无 rowMapper，用于纯 update/insert 场景）。
	 */
	public <T> T getMapper(Class<T> mapperInterface) {
		return getMapper(mapperInterface, null);
	}

	/**
	 * 直接执行一条已注册的语句。
	 *
	 * @param namespace Mapper 命名空间
	 * @param statementId 语句 ID
	 * @param params 参数 Map
	 * @return SqlBuild 结果
	 */
	public SqlBuild execute(String namespace, String statementId, Map<String, Object> params) {
		Function<Map<String, Object>, SqlBuild> builder = configuration.findStatement(namespace, statementId);
		if (builder == null) {
			throw new MetaSqlException("no statement registered for " + namespace + "." + statementId);
		}
		return builder.apply(params != null ? params : Map.of());
	}

	/**
	 * 执行查询并返回 List。
	 */
	public <T> List<T> query(String namespace, String statementId, Map<String, Object> params, RowMapper<T> mapper) {
		SqlBuild build = execute(namespace, statementId, params);
		return autumnJdbc.query(build.sql(), mapper, build.args());
	}

	/**
	 * 执行查询并返回 Optional。
	 */
	public <T> Optional<T> queryOptional(String namespace, String statementId, Map<String, Object> params,
			RowMapper<T> mapper) {
		SqlBuild build = execute(namespace, statementId, params);
		return autumnJdbc.queryOptional(build.sql(), mapper, build.args());
	}

	/**
	 * 执行更新（INSERT/UPDATE/DELETE）。
	 */
	public int update(String namespace, String statementId, Map<String, Object> params) {
		SqlBuild build = execute(namespace, statementId, params);
		return autumnJdbc.update(build.sql(), build.args());
	}

	/**
	 * 刷新配置：清空已注册的 Mapper。
	 */
	public void refresh() {
		configuration.refresh();
	}

	/**
	 * Mapper 动态代理的 InvocationHandler：把方法调用路由到注册的 SQL 构建函数。
	 */
	private class MapperInvocationHandler implements InvocationHandler {

		private final String namespace;

		private final RowMapper<?> rowMapper;

		MapperInvocationHandler(String namespace, RowMapper<?> rowMapper) {
			this.namespace = namespace;
			this.rowMapper = rowMapper;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			// Object 方法直接委托
			if (method.getDeclaringClass() == Object.class) {
				return method.invoke(this, args);
			}

			String statementId = method.getName();
			Map<String, Object> params = buildParams(method, args);

			Class<?> returnType = method.getReturnType();

			// update / insert / delete
			if (returnType == void.class || returnType == int.class || returnType == long.class
					|| returnType == Integer.class || returnType == Long.class) {
				int affected = update(namespace, statementId, params);
				if (returnType == void.class) {
					return null;
				}
				if (returnType == int.class || returnType == Integer.class) {
					return affected;
				}
				return (long) affected;
			}

			// select → List 或 Optional
			if (rowMapper == null) {
				throw new MetaSqlException("select statement " + namespace + "." + statementId
						+ " requires a RowMapper; use getMapper(Class, RowMapper)");
			}

			if (returnType == Optional.class) {
				return queryOptional(namespace, statementId, params, rowMapper);
			}
			if (List.class.isAssignableFrom(returnType)) {
				return query(namespace, statementId, params, rowMapper);
			}

			// 单实体返回 → 取 Optional.orElse(null)
			return queryOptional(namespace, statementId, params, rowMapper).orElse(null);
		}

		/**
		 * 把方法形参转成参数 Map。
		 * 形参名来自 {@code Method.getParameters()}（需要编译期开启 {@code -parameters}）。
		 */
		private Map<String, Object> buildParams(Method method, Object[] args) {
			if (args == null || args.length == 0) {
				return Map.of();
			}
			// 单参数且为 Map → 直接采用
			if (args.length == 1 && args[0] instanceof Map) {
				@SuppressWarnings("unchecked")
				Map<String, Object> map = (Map<String, Object>) args[0];
				return map;
			}
			Map<String, Object> params = new HashMap<>();
			var parameters = method.getParameters();
			for (int i = 0; i < parameters.length; i++) {
				params.put(parameters[i].getName(), args[i]);
			}
			return params;
		}

	}

}
