package xyz.wewin.autumn.data.jdbc.runtime;

/**
 * autumn-data 取数上下文：由 {@link AutumnDataContextHolder} 按线程保存，承载分页关联（1:n / n:1）加载的
 * 分级策略与<em>一次性字段投影</em>。
 *
 * <p>上下文是不可变值对象（record）：子线程继承的是引用而非可变状态，因此 {@link InheritableThreadLocal}
 * 与 {@link AutumnDataContextHolder#wrap(Runnable)} 的捕获/还原都不会产生跨线程的意外写串。</p>
 *
 * @param mode                 分页关联加载模式（默认 {@link AssociationMode#DEFAULT}）
 * @param largePageThreshold   仅 {@link AssociationMode#DEFAULT} 模式生效：页大小不超过该值则 eager 加载关联（默认 20）
 * @param projection           字段投影（默认 {@code null} = 按编译期的全列查询）；非空时最近一次取实体的查询
 *            只 {@code SELECT} 命中的列，未选中的属性留在实体默认值上
 */
public record AutumnDataContext(AssociationMode mode, int largePageThreshold, FieldProjection projection) {

	public AutumnDataContext {
		if (largePageThreshold <= 0) {
			throw new IllegalArgumentException("largePageThreshold must be > 0 but was " + largePageThreshold);
		}
	}

	/** 不裁剪列的上下文（历史两参形态）：投影为空，{@code SELECT} 仍是编译期写定的全列。 */
	public AutumnDataContext(AssociationMode mode, int largePageThreshold) {
		this(mode, largePageThreshold, null);
	}

	/** 默认上下文：DEFAULT 模式、阈值 20（≤20 级联，>20 不自动级联）、不裁剪列。 */
	public static AutumnDataContext defaults() {
		return new AutumnDataContext(AssociationMode.DEFAULT, 20);
	}

	/** 换掉投影、保留级联模式与阈值。 */
	public AutumnDataContext withProjection(FieldProjection projection) {
		return new AutumnDataContext(this.mode, this.largePageThreshold, projection);
	}

	/** 换掉级联模式、保留阈值与已设定的投影。 */
	public AutumnDataContext withMode(AssociationMode mode) {
		return new AutumnDataContext(mode, this.largePageThreshold, this.projection);
	}
}
