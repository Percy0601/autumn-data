package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * VO 与结果集的绑定：每个成员读哪一列、怎么写进 VO。
 *
 * <p>{@code column} 是进 {@code SELECT} 的列（编译期方言视图下可能带引号），{@code label} 是
 * {@code ResultSet} 上要取的标签（恒为未引号形态，因为 SQL 引号只影响标识符，不影响结果集标签）。
 * {@code @Query} 的 SQL 由使用方写死，不参与列裁剪，两个值相同。</p>
 *
 * @param voType VO 全限定名
 * @param canonicalConstructor {@code true} 走全参构造；{@code false} 走无参构造 + setter
 * @param members 成员绑定，顺序即构造实参顺序
 */
record VoMapping(String voType, boolean canonicalConstructor, List<Member> members) {

	/**
	 * 一个成员的读取与写入方式。
	 *
	 * @param name 属性名（错误信息与可读性用）
	 * @param setter setter 方法名；全参构造时为 {@code null}
	 * @param typeSource 类型源码形态
	 * @param column 进 {@code SELECT} 的列名（派生查询按实体列，{@code @Query} 与 {@code label} 相同）
	 * @param label 结果集列标签
	 * @param enumeration 是否为枚举
	 */
	record Member(String name, String setter, String typeSource, String column, String label, boolean enumeration) {

		/** 基本类型走 {@code getInt}/{@code getLong}…，其余走类型化 {@code getObject}。 */
		boolean primitive() {
			return !this.typeSource.contains(".") && !this.typeSource.endsWith("[]");
		}
	}

	/** {@code SELECT} 的列清单：同一列被多个成员映射时只出现一次，按成员声明顺序。 */
	String selectColumns() {
		Set<String> columns = new LinkedHashSet<>();
		for (Member member : this.members) {
			columns.add(member.column());
		}
		return String.join(", ", columns);
	}

	/** 是否存在枚举成员（是则生成类里需要 {@code Enum.valueOf} 的小助手）。 */
	boolean hasEnumeration() {
		return this.members.stream().anyMatch(Member::enumeration);
	}
}
