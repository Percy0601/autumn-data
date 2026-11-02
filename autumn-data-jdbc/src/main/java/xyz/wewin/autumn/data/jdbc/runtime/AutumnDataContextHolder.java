package xyz.wewin.autumn.data.jdbc.runtime;

import java.util.concurrent.Callable;

/**
 * 线程级上下文持有器：按线程保存 {@link AutumnDataContext}（分页关联加载模式 + 字段投影），运行期生成代码与调用方共享。
 *
 * <h2>关联加载三态（{@link AssociationMode}）</h2>
 * <ul>
 *     <li><b>0 {@code DEFAULT}</b>：20 条分页级联 —— 页大小 ≤ 阈值（默认 20）eager 加载 1:n / n:1；
 *         超过阈值不自动级联，交给调用方按需取（即最初的设计）。</li>
 *     <li><b>1 {@code CASCADE}</b>：强制级联，任何页大小都加载关联。</li>
 *     <li><b>2 {@code DISABLED}</b>：强制关闭，任何页大小都不查询 / 装配关联。</li>
 * </ul>
 *
 * <h2>字段投影（{@link FieldProjection}）</h2>
 * 编译期生成的 {@code SELECT} 默认<em>全列</em>；{@link #selectFields(String...)} /
 * {@link #excludeFields(String...)} 之后，最近一次取实体的查询只取需要的那几列（少走的列就不再占网络与内存），
 * 未选中的属性留在实体默认值上。主键与多对一装配要读的外键列由编译期判定为<em>不可省略</em>，恒取，
 * 因此裁剪列不会打断级联。
 *
 * <h2>两种用法</h2>
 * <ul>
 *     <li><b>一次性（推荐，对标 PageHelper.startPage）</b>：{@link #startContext} /
 *         {@link #forceCascade()} / {@link #disableCascade()} / {@link #selectFields(String...)} /
 *         {@link #excludeFields(String...)} 开启后，只作用于<b>最近一次</b>取实体的查询
 *         （{@code findAll} / {@code findById} / {@code findAllById} / 分页 / 派生查询；
 *         {@code countBy} / {@code existsBy} 这类不取行的语句不消费，顺延到下一次取实体的查询），
 *         生成代码执行该查询后自动 {@link #consumePending()} 清除，<b>无需手动 clear</b>。
 *         若开启后没紧跟这类查询，则顺延到下一次被消费（与 PageHelper 行为一致）。</li>
 *     <li><b>持久（请求级）</b>：{@link #set} 设定后对整个线程 / 请求持续生效，需 {@link #clear()} 或随线程结束清除；
 *         适合在拦截器里按请求统一设置。</li>
 * </ul>
 *
 * <h2>三种线程模型的感知</h2>
 * <ul>
 *     <li><b>同一线程</b>：直接 {@link #get} / {@link #set} / {@link #clear} / {@link #startContext}。</li>
 *     <li><b>{@code new Thread()} 派生的子线程</b>：用 {@link InheritableThreadLocal}，子线程创建时自动继承父线程上下文。</li>
 *     <li><b>线程池</b>：池里 worker 线程是复用且预先创建的，{@code InheritableThreadLocal} 看不到「提交任务那一刻」
 *         的上下文。必须用 {@link #wrap(Runnable)} / {@link #wrap(Callable)} 在<b>提交时</b>捕获上下文，
 *         工作线程执行前还原、执行后还原为提交前的原值（没有则清除），从而实现线程池感知。</li>
 * </ul>
 */
public final class AutumnDataContextHolder {

	/** 持久上下文（继承式）：未显式设置时惰性为默认。 */
	private static final ThreadLocal<AutumnDataContext> CONTEXT = new InheritableThreadLocal<>();

	/** 一次性上下文（继承式）：由 {@link #startContext} 设置，被「最近一次」分页查询消费后清除。 */
	private static final ThreadLocal<AutumnDataContext> PENDING = new InheritableThreadLocal<>();

	private AutumnDataContextHolder() {
	}

	/**
	 * 取生效上下文：优先一次性 {@code PENDING}，其次持久 {@code CONTEXT}，再否则默认
	 * （{@link AutumnDataContext#defaults()}：DEFAULT 模式、阈值 20）。生成代码一律走此方法，保证非空。
	 */
	public static AutumnDataContext get() {
		AutumnDataContext pending = PENDING.get();
		if (pending != null) {
			return pending;
		}
		AutumnDataContext ctx = CONTEXT.get();
		if (ctx == null) {
			ctx = AutumnDataContext.defaults();
			CONTEXT.set(ctx);
		}
		return ctx;
	}

	/** 持久设置（整个线程 / 请求级）；覆盖默认，需 {@link #clear()} 或随线程结束清除。 */
	public static void set(AutumnDataContext context) {
		CONTEXT.set(context);
	}

	/** 一次性设置：只作用于「最近一次」取实体的查询，查询消费后自动清除（对标 PageHelper.startPage，无需手动 clear）。 */
	public static void startContext(AutumnDataContext context) {
		PENDING.set(context);
	}

	/** 便捷：一次性强制级联（模式 {@link AssociationMode#CASCADE}），只作用于最近一次分页查询；已设定的投影保留。 */
	public static void forceCascade() {
		startContext(get().withMode(AssociationMode.CASCADE));
	}

	/** 便捷：一次性强制关闭关联（模式 {@link AssociationMode#DISABLED}），只作用于最近一次分页查询；已设定的投影保留。 */
	public static void disableCascade() {
		startContext(get().withMode(AssociationMode.DISABLED));
	}

	/**
	 * 便捷：一次性字段投影 —— 最近一次取实体的查询只 {@code SELECT} 这些属性列，查完自动清除。
	 * 级联模式与阈值沿用当前生效上下文，可与 {@link #forceCascade()} / {@link #disableCascade()} 组合使用。
	 */
	public static void selectFields(String... fields) {
		startProjection(FieldProjection.include(fields));
	}

	/** 便捷：一次性字段投影 —— 最近一次取实体的查询排除这些属性列，其余照旧。 */
	public static void excludeFields(String... fields) {
		startProjection(FieldProjection.exclude(fields));
	}

	/** 生效的字段投影；未设置时返回 {@code null}，生成代码据此走编译期写定的全列语句。 */
	public static FieldProjection projection() {
		return get().projection();
	}

	/** 一次性设置投影，保留当前的级联模式与阈值。 */
	private static void startProjection(FieldProjection projection) {
		AutumnDataContext current = get();
		startContext(current.withProjection(projection));
	}

	/**
	 * 由生成代码在「最近一次」取实体的查询执行后调用：清除一次性 {@code PENDING}。
	 * 持久 {@code CONTEXT} 不受影响；未设置一次性上下文时为无操作。
	 */
	public static void consumePending() {
		PENDING.remove();
	}

	/** 清除当前线程的全部上下文（持久 + 一次性），防止脏数据串台。 */
	public static void clear() {
		CONTEXT.remove();
		PENDING.remove();
	}

	/**
	 * 线程池透传：捕获提交线程当前上下文（持久 + 一次性），返回的工作单元执行前还原、
	 * 执行后还原为提交前的原值。线程池复用 worker 线程时，只有经此方法包裹的任务才能看到提交者的上下文。
	 */
	public static Runnable wrap(Runnable task) {
		AutumnDataContext snapshotContext = CONTEXT.get();
		AutumnDataContext snapshotPending = PENDING.get();
		return () -> {
			AutumnDataContext previousContext = CONTEXT.get();
			AutumnDataContext previousPending = PENDING.get();
			restore(snapshotContext, snapshotPending);
			try {
				task.run();
			}
			finally {
				restore(previousContext, previousPending);
			}
		};
	}

	/** 同 {@link #wrap(Runnable)}，但用于 {@code Callable}（带返回值 / 可抛异常）。 */
	public static <V> Callable<V> wrap(Callable<V> task) {
		AutumnDataContext snapshotContext = CONTEXT.get();
		AutumnDataContext snapshotPending = PENDING.get();
		return () -> {
			AutumnDataContext previousContext = CONTEXT.get();
			AutumnDataContext previousPending = PENDING.get();
			restore(snapshotContext, snapshotPending);
			try {
				return task.call();
			}
			finally {
				restore(previousContext, previousPending);
			}
		};
	}

	private static void restore(AutumnDataContext context, AutumnDataContext pending) {
		if (context != null) {
			CONTEXT.set(context);
		}
		else {
			CONTEXT.remove();
		}
		if (pending != null) {
			PENDING.set(pending);
		}
		else {
			PENDING.remove();
		}
	}
}
