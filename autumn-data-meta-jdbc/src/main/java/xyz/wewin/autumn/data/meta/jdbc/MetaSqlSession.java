package xyz.wewin.autumn.data.meta.jdbc;

/**
 * Meta SQL 会话（meta 模块对外门面，后续步骤实现）。
 *
 * <p>对标 MyBatis 的 {@code SqlSession}：</p>
 * <ul>
 *   <li>{@link #getMapper(Class)}：为 Mapper 接口返回动态代理实现，方法调用会被路由到
 *       对应 FreeMarker 模板渲染出的 SQL；</li>
 *   <li>{@link #refresh()}：重新加载模板 / 扫描 Mapper，支持运行时动态变更。</li>
 * </ul>
 *
 * <p>当前为骨架接口，实现将在“FreeMarker 动态 Mapper 引擎”步骤中补全。</p>
 *
 * @param <T> Mapper 接口类型
 */
public interface MetaSqlSession {

	<T> T getMapper(Class<T> mapperInterface);

	void refresh();
}
