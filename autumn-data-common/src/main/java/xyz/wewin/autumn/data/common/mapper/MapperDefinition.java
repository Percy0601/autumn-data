package xyz.wewin.autumn.data.common.mapper;

import java.util.List;

/**
 * 解析后的 Mapper 定义：一个 XML 文件对应一个 {@code MapperDefinition}。
 *
 * <p>对标 MyBatis 的 {@code <mapper namespace="...">} 根元素。
 * 编译期由 {@code MapperXmlParser} 产出，{@code TemplateCodeGenerator} 消费生成 Java 源码。</p>
 *
 * @param namespace Mapper 接口的全限定名（或逻辑分组名）
 * @param statements 该 Mapper 下的所有语句（select / insert / update / delete）
 */
public record MapperDefinition(String namespace, List<StatementDefinition> statements) {

	/**
	 * 单条语句定义：对应 XML 里的一个 {@code <select>}/{@code <insert>}/{@code <update>}/{@code <delete>} 元素。
	 *
	 * @param id 语句 ID（方法名）
	 * @param type 语句类型
	 * @param resultType 返回类型（select 时有值）
	 * @param parameterType 参数类型（可选，编译期可从方法签名推断）
	 * @param template 解析后的模板 AST
	 */
	public record StatementDefinition(String id, StatementType type, String resultType,
			String parameterType, List<TemplateNode> template) {
	}

	/** 语句类型，决定生成代码走 query 还是 update。 */
	public enum StatementType {
		SELECT, INSERT, UPDATE, DELETE
	}

}
