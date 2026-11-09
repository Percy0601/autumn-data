package xyz.wewin.autumn.data.jdbc.compiler.template;

/**
 * 模板解析异常：XML 格式错误、标签属性缺失、插值语法不合法时抛出。
 */
public class TemplateParseException extends RuntimeException {

	public TemplateParseException(String message) {
		super(message);
	}

	public TemplateParseException(String message, Throwable cause) {
		super(message, cause);
	}

}
