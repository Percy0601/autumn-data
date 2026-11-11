package xyz.wewin.autumn.data.common.mapper;

/**
 * Mapper XML 解析异常：文件格式错误、属性缺失、XML 语法不合法时抛出。
 */
public class MapperXmlParseException extends RuntimeException {

	public MapperXmlParseException(String message) {
		super(message);
	}

	public MapperXmlParseException(String message, Throwable cause) {
		super(message, cause);
	}

}
