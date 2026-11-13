package xyz.wewin.autumn.data.common.mapper;

import java.util.List;

/**
 * 动态 SQL 模板 AST 节点。
 *
 * <p>由 {@link TemplateParser} 在读取 XML 时产出，两种执行方式共用同一棵语法树：
 * 编译期由 {@code TemplateCodeGenerator} 翻译成 Java {@code StringBuilder} 源码，
 * 运行期由动态层的解释器直接遍历。两条路都不引入模板引擎，也不反射。</p>
 *
 * <p>支持的语法子集（只对标 MyBatis 动态 SQL；标签是 XML 这一套，{@code <sql>/<include>}
 * 在解析阶段内联，不进 AST）：</p>
 * <ul>
 *   <li>{@code ${name}} — 变量插值（原样拼入 SQL，只用于标识符片段）</li>
 *   <li>{@code #{name}} — 参数占位符（生成 {@code ?}，绑定实参，防注入）</li>
 *   <li>{@code <if test="expr">} — 条件分支</li>
 *   <li>{@code <choose>/<when>/<otherwise>} — 多分支</li>
 *   <li>{@code <foreach collection item separator>} — 循环展开</li>
 *   <li>{@code <trim>/<where>} — 带前后缀与首尾冗余词剥离的容器</li>
 *   <li>纯文本 — 原样保留的 SQL 片段</li>
 * </ul>
 */
public sealed interface TemplateNode permits TemplateNode.Text, TemplateNode.Variable,
		TemplateNode.Parameter, TemplateNode.If, TemplateNode.Choose, TemplateNode.ForEach,
		TemplateNode.Trim {

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
	 * {@code <if test="expr">} 条件块。
	 *
	 * <p>{@code expression} 是简化的条件表达式，对参数求值：{@code name != null}、
	 * {@code status == 'paid'}、{@code name != ''} 以及 {@code &&}/{@code ||}/{@code !} 组合。</p>
	 */
	record If(String expression, List<TemplateNode> body) implements TemplateNode {
	}

	/**
	 * {@code <choose>/<when>/<otherwise>} 多分支（对标 MyBatis）。
	 *
	 * <p>按 {@code branches} 顺序求值，第一个条件为真的分支生效；
	 * 全不命中时走 {@code otherwise}（可为空）。</p>
	 */
	record Choose(List<WhenBranch> branches, List<TemplateNode> otherwise) implements TemplateNode {

		public record WhenBranch(String expression, List<TemplateNode> body) {
		}
	}

	/**
	 * {@code <foreach collection="" item="" separator="" open="(" close=")">} 循环展开。
	 *
	 * <p>用于 {@code IN (...)} 这类运行期才能确定元素个数的场景。
	 * 编译期生成循环代码，运行期遍历集合并拼接 SQL 片段。</p>
	 *
	 * <p>{@code open}/{@code close} 是<b>整段</b>的前后括号，只在集合非空时各出现一次；
	 * 集合为空则整段（含括号）不渲染，与 MyBatis 一致。</p>
	 *
	 * @param itemName 循环变量名（{@code item}）
	 * @param collectionExpression 集合表达式（{@code ids}）
	 * @param separator 元素间的分隔符（如 {@code , }）
	 * @param open 整段前置片段（如 {@code (}）
	 * @param close 整段后置片段（如 {@code )}）
	 * @param body 循环体节点
	 */
	record ForEach(String itemName, String collectionExpression, String separator, String open, String close,
			List<TemplateNode> body) implements TemplateNode {
	}

	/**
	 * {@code <trim>} 容器（对标 MyBatis {@code <trim>}）：子节点渲染出空白就整段不出现，
	 * 否则补 {@code prefix}/{@code suffix}，并剥掉内容首尾多余的 {@code prefixOverrides}/
	 * {@code suffixOverrides}（大小写不敏感）。
	 *
	 * {@code <where>} 是它的固定写法，等价于
	 * {@code <trim prefix="WHERE " prefixOverrides="AND|OR">}：
	 * 「有条件才出 WHERE」这件事写在 XML 里，而不是由 Java 侧拼好片段塞进 SQL 文本。
	 *
	 * @param prefix 非空内容前置的片段（含需要的空格，如 {@code "WHERE "}）
	 * @param suffix 非空内容后置的片段
	 * @param prefixOverrides 内容开头要剥掉的词（如 {@code [AND, OR]}）
	 * @param suffixOverrides 内容结尾要剥掉的词（如 {@code [,]}）
	 * @param body 子节点
	 */
	record Trim(String prefix, String suffix, List<String> prefixOverrides, List<String> suffixOverrides,
			List<TemplateNode> body) implements TemplateNode {

		/** {@code <where>} 的语法糖。 */
		public static Trim where(List<TemplateNode> body) {
			return new Trim("WHERE ", "", List.of("AND", "OR"), List.of(), body);
		}
	}

}
