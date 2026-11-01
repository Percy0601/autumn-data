package xyz.wewin.autumn.data.jdbc.compiler;

/**
 * 方法名无法解析成已支持的派生查询时抛出，消息本身即编译期错误文案（含方法名与原因）。
 *
 * <p>{@link AutoRepositoryProcessor} 捕获后在方法声明位置报错 —— 对应
 * docs/spring-data-derived-query.md 第 8.3 条「解析失败必须定位到方法声明，不允许静默生成」。</p>
 */
class DerivedQueryException extends RuntimeException {

	DerivedQueryException(String message) {
		super(message);
	}
}
