package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.List;

/**
 * 自定义返回类型（VO / DTO 投影）的编译期元数据：怎么构造 + 有哪些成员。
 *
 * <p>VO 不参与实体约定：它不认识表，只描述「要哪些属性」。派生查询把每个成员按<b>实体字段名</b>
 * 映射到列（{@code @Column(name)} 可显式指定实体列），{@code @Query} 则把成员名转成结果集列标签
 * （同样可用 {@code @Column(name)} 指定别名）。生成实现类时按成员声明顺序直接拼出构造调用
 * （record / 全参构造）或 setter 调用，结果映射仍是零反射。</p>
 *
 * <p>两种形态：</p>
 * <ul>
 *   <li><b>record</b>：成员 = 组件，按声明顺序走规范构造器；</li>
 *   <li><b>普通类</b>：成员 = 公共 {@code setXxx}，走无参构造 + setter（编译期按 {@link java.beans}
 *       的驼峰规则从方法名还原属性名；不引入运行期反射）。</li>
 * </ul>
 *
 * @param qualifiedName VO 全限定名（生成代码直接 new 它）
 * @param canonicalConstructor {@code true} 走全参构造（record 天然如此）；{@code false} 走无参构造 + setter
 * @param members 成员清单，顺序即生成代码里的构造实参顺序
 */
record VoMetadata(String qualifiedName, boolean canonicalConstructor, List<Member> members) {

	/**
	 * 一个 VO 成员。
	 *
	 * @param name 属性名（record 组件名 / setter 还原出的属性名）
	 * @param typeSource 类型源码形态（基本类型字面名或全限定名）
	 * @param setter 写入方法名；全参构造时为 {@code null}
	 * @param columnOverride {@code @Column(name)} 指定的列 / 别名；{@code null} 表示按名字推导
	 * @param enumeration 是否为枚举类型（取值时先读字符串再 {@code Enum.valueOf}）
	 */
	record Member(String name, String typeSource, String setter, String columnOverride, boolean enumeration) {

		/** 基本类型（含 {@code char}）用 {@code ResultSet} 的专用取值方法，其余走类型化 {@code getObject}。 */
		boolean primitive() {
			return !this.typeSource.contains(".") && !this.typeSource.endsWith("[]");
		}
	}
}
