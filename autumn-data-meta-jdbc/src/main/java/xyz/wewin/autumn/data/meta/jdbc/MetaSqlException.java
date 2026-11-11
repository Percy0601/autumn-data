package xyz.wewin.autumn.data.meta.jdbc;

/**
 * 动态 SQL 层运行期异常：表元数据读取、语句解析/构建、动态实体生成与执行失败时抛出。
 */
public class MetaSqlException extends RuntimeException {

	public MetaSqlException(String message) {
		super(message);
	}

	public MetaSqlException(String message, Throwable cause) {
		super(message, cause);
	}

}
