package xyz.wewin.autumn.data.jdbc.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 字段投影：本次查询只取（{@code inclusive = true}）或排除（{@code inclusive = false}）主实体的部分属性列，
 * 用来减少不必要的列走网络 —— 编译期默认仍是<em>全列</em>，只有运行期设了投影才换成精简的 {@code SELECT}。
 *
 * <p>不可变值对象：与 {@link AutumnDataContext} 一起放在 {@link InheritableThreadLocal} 里，
 * 子线程继承与线程池透传都不会产生跨线程写串。</p>
 *
 * <p>字段名用<em>实体属性名</em>（驼峰），不是数据库列名 —— 生成代码按属性名匹配，列名由编译期映射。</p>
 *
 * @param inclusive {@code true} 表示白名单（只取这些字段），{@code false} 表示黑名单（排除这些字段）；
 *            访问器名避开 {@link #include(String...)} 工厂，否则零参调用会撞进实例方法
 * @param fields 参与判断的属性名集合（已做不可变拷贝）
 */
public record FieldProjection(boolean inclusive, Set<String> fields) {

	public FieldProjection {
		fields = Set.copyOf(fields);
	}

	/** 白名单：本次查询只取这些属性列。 */
	public static FieldProjection include(String... fields) {
		return new FieldProjection(true, names(fields));
	}

	/** 黑名单：本次查询排除这些属性列。 */
	public static FieldProjection exclude(String... fields) {
		return new FieldProjection(false, names(fields));
	}

	/**
	 * 该列是否进入本次 {@code SELECT}。
	 *
	 * @param field 实体属性名
	 * @param required 编译期判定的<em>不可省略列</em>（主键、多对一装配要读的外键）——
	 *            缺了它们级联装配与分页排序会失效，因此恒取，不受白/黑名单影响
	 */
	public boolean projects(String field, boolean required) {
		if (required) {
			return true;
		}
		return this.inclusive ? this.fields.contains(field) : !this.fields.contains(field);
	}

	private static Set<String> names(String[] fields) {
		if (fields == null || fields.length == 0) {
			throw new IllegalArgumentException("field projection needs at least one property name");
		}
		Set<String> names = new LinkedHashSet<>();
		for (String field : fields) {
			if (field == null || field.isBlank()) {
				throw new IllegalArgumentException("field projection does not accept null/blank property name");
			}
			names.add(field);
		}
		return names;
	}

	/** 便于调试与断言的可读形态。 */
	@Override
	public String toString() {
		return (this.inclusive ? "include " : "exclude ") + List.copyOf(this.fields);
	}
}
