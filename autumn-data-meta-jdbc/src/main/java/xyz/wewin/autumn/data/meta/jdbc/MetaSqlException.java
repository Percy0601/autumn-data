package xyz.wewin.autumn.data.meta.jdbc;

/**
 * Meta SQL 引擎运行期异常：语句未注册、参数不匹配、执行失败时抛出。
 */
public class MetaSqlException extends RuntimeException {

	public MetaSqlException(String message) {
		super(message);
	}

	public MetaSqlException(String message, Throwable cause) {
		super(message, cause);
	}

}
