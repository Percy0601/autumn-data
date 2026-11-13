package xyz.wewin.autumn.data.common.mapper;

import java.util.List;

/**
 * 解析后的 Mapper 定义：一个 XML 文件对应一个 {@code MapperDefinition}。
 *
 * <p>对标 MyBatis 的 {@code <mapper namespace="...">} 根元素。
 * 编译期由 {@code MapperXmlParser} 产出，{@code TemplateCodeGenerator} 消费生成 Java 源码。</p>
 *
 * <p>{@code <sql>} 片段在解析阶段就按 {@code <include refid="...">} 内联进语句 AST，
 * {@code <resultMap>} 也解析成 {@link ResultMapping} 挂在引用它的语句上，所以消费方拿到的
 * {@link StatementDefinition} 已经是一棵自包含的树，不需要再回查整份 Mapper。</p>
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
	 * @param template 解析后的模板 AST（{@code <sql>} 片段已内联）
	 * @param resultMappings 结果列映射（{@code resultMap} 引用解析后的内容）；空表示按列名默认映射
	 */
	public record StatementDefinition(String id, StatementType type, String resultType,
			String parameterType, List<TemplateNode> template, List<ResultMapping> resultMappings) {

		/** 不带结果映射的语句。 */
		public StatementDefinition(String id, StatementType type, String resultType, String parameterType,
				List<TemplateNode> template) {
			this(id, type, resultType, parameterType, template, List.of());
		}

		public StatementDefinition {
			template = template == null ? List.of() : List.copyOf(template);
			resultMappings = resultMappings == null ? List.of() : List.copyOf(resultMappings);
		}
	}

	/**
	 * 一列结果映射：结果集里取的列（或 SELECT 里的别名）→ {@code MetaClass} 属性名。
	 *
	 * @param column 结果集中的列标签 / 别名，大小写不敏感
	 * @param property 目标属性：元数据里的列名，或实体字段的驼峰名（运行期会换成对应列名）
	 */
	public record ResultMapping(String column, String property) {
	}

	/** 语句类型，决定生成代码走 query 还是 update。 */
	public enum StatementType {
		SELECT, INSERT, UPDATE, DELETE
	}

}
