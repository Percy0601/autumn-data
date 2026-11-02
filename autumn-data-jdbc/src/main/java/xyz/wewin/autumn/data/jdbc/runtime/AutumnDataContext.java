package xyz.wewin.autumn.data.jdbc.runtime;

/**
 * autumn-data 取数上下文：由 {@link AutumnDataContextHolder} 按线程保存，承载分页关联（1:n / n:1）加载的分级策略。
 *
 * <p>上下文是不可变值对象（record）：子线程继承的是引用而非可变状态，因此 {@link InheritableThreadLocal}
 * 与 {@link AutumnDataContextHolder#wrap(Runnable)} 的捕获/还原都不会产生跨线程的意外写串。</p>
 *
 * @param mode                 分页关联加载模式（默认 {@link AssociationMode#DEFAULT}）
 * @param largePageThreshold   仅 {@link AssociationMode#DEFAULT} 模式生效：页大小不超过该值则 eager 加载关联（默认 20）
 */
public record AutumnDataContext(AssociationMode mode, int largePageThreshold) {

	public AutumnDataContext {
		if (largePageThreshold <= 0) {
			throw new IllegalArgumentException("largePageThreshold must be > 0 but was " + largePageThreshold);
		}
	}

	/** 默认上下文：DEFAULT 模式、阈值 20（≤20 级联，>20 不自动级联）。 */
	public static AutumnDataContext defaults() {
		return new AutumnDataContext(AssociationMode.DEFAULT, 20);
	}
}
