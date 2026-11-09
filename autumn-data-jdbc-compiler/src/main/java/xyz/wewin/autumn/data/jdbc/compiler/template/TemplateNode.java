package xyz.wewin.autumn.data.jdbc.compiler.template;

import java.util.List;

/**
 * FreeMarker-lite 模板 AST 节点。
 *
 * <p>编译期由 {@code TemplateParser} 产出，{@code TemplateCodeGenerator} 消费，
 * 把 XML 里的动态 SQL 模板翻译成 Java {@code StringBuilder} 源码。
 * 运行期不出现——零反射、零模板引擎依赖。</p>
 *
 * <p>支持的语法子集（对标 MyBatis 动态 SQL + FreeMarker 插值）：</p>
 * <ul>
 *   <li>{@code ${name}} — 变量插值（原样拼入 SQL）</li>
 *   <li>{@code #{name}} — 参数占位符（生成 {@code ?}，运行期绑定）</li>
 *   <li>{@code <#if expr>...</#if>} — 条件分支</li>
 *   <li>{@code <#choose>/<#when>/<#otherwise>} — 多分支（对标 MyBatis {@code <choose>}）</li>
 *   <li>{@code <#foreach item in collection>...</#foreach>} — 循环展开</li>
 *   <li>纯文本 — 原样保留的 SQL 片段</li>
 * </ul>
 */
public sealed interface TemplateNode permits TemplateNode.Text, TemplateNode.Variable,
		TemplateNode.Parameter, TemplateNode.If, TemplateNode.Choose, TemplateNode.ForEach {

	/** 纯文本 SQL 片段。 */
	record Text(String content) implements TemplateNode {
	}

	/**
	 * {@code ${name}} 变量插值：编译期已知值直接内联到 SQL。
	 *
	 * <p>用于表名、列名、排序方向等编译期可确定的标识符，
	 * 与 {@link Parameter} 的运行时绑定占位符互斥。</p>
	 */
	record Variable(String expression) implements TemplateNode {
	}

	/**
	 * {@code #{name}} 参数占位符：运行期生成 {@code ?}，由 JDBC 绑定实参。
	 *
	 * <p>对标 MyBatis 的 {@code #{param}}，防 SQL 注入。
	 * 生成的代码里会记录参数名，供运行期按名绑定。</p>
	 */
	record Parameter(String name) implements TemplateNode {
	}

	/**
	 * {@code <#if expr>...</#if>} 条件块。
	 *
	 * <p>{@code expression} 是简化的条件表达式，运行期对 {@code Map<String, Object>} 求值：
	 * 支持 {@code name??}（非空判断）、{@code name == 'value'}（等值）、
	 * {@code name != ''}（非空串）、{@code !expr}（取反）。</p>
	 */
	record If(String expression, List<TemplateNode> body) implements TemplateNode {
	}

	/**
	 * {@code <#choose>/<#when>/<#otherwise>} 多分支（对标 MyBatis {@code <choose>}）。
	 *
	 * <p>按 {@code branches} 顺序求值，第一个条件为真的分支生效；
	 * 全不命中时走 {@code otherwise}（可为空）。</p>
	 */
	record Choose(List<WhenBranch> branches, List<TemplateNode> otherwise) implements TemplateNode {

		public record WhenBranch(String expression, List<TemplateNode> body) {
		}
	}

	/**
	 * {@code <#foreach item in collection>...</#foreach>} 循环展开。
	 *
	 * <p>用于 {@code IN (:ids)} 这类运行期才能确定元素个数的场景。
	 * 编译期生成循环代码，运行期遍历集合并拼接 SQL 片段。</p>
	 *
	 * @param itemName 循环变量名（{@code item}）
	 * @param collectionExpression 集合表达式（{@code ids}）
	 * @param separator 元素间的分隔符（如 {@code , }）
	 * @param body 循环体节点
	 */
	record ForEach(String itemName, String collectionExpression, String separator,
			List<TemplateNode> body) implements TemplateNode {
	}

}
