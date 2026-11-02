package xyz.wewin.autumn.data.jdbc.runtime;

/**
 * 分页关联（1:n / n:1）加载模式，由 {@link AutumnDataContext} 携带、按线程经
 * {@link AutumnDataContextHolder} 设置，运行期由生成的分页方法读取决定是否需要加载关联。
 *
 * <p>每个枚举常量携带两个元数据：</p>
 * <ul>
 *     <li><b>code（状态值）</b>：{@code 0 / 1 / 2}，便于配置化与序列化传输。</li>
 *     <li><b>name（名称）</b>：展示用的可读名称。</li>
 * </ul>
 *
 * <ul>
 *     <li>{@link #DEFAULT}（0）：「20 条分页级联」—— 页大小不超过阈值（默认 20）时 eager 加载关联，
 *         超过阈值则<b>不再自动级联</b>，改由调用方按需取（即最初的设计：低于阈值默认处理，超过阈值交给 AutumnData）。</li>
 *     <li>{@link #CASCADE}（1）：强制级联，任何页大小都 eager 加载关联。</li>
 *     <li>{@link #DISABLED}（2）：强制关闭，任何页大小都不查询 / 装配关联。</li>
 * </ul>
 */
public enum AssociationMode {
	/** 0：默认，20 条分页级联。 */
	DEFAULT(0, "默认"),
	/** 1：强制级联。 */
	CASCADE(1, "级联"),
	/** 2：强制关闭。 */
	DISABLED(2, "不级联");

	/** 状态值：0 默认 / 1 强制级联 / 2 强制关闭。 */
	private final int code;

	/** 名称：展示用的可读名称。 */
	private final String name;

	AssociationMode(int code, String name) {
		this.code = code;
		this.name = name;
	}

	/** 状态值（0 / 1 / 2）。 */
	public int code() {
		return code;
	}

	/** 名称（展示用可读名称）。 */
	public String displayName() {
		return name;
	}

	/** 按状态值反查枚举；未知状态值抛 {@link IllegalArgumentException}。 */
	public static AssociationMode of(int code) {
		for (AssociationMode mode : values()) {
			if (mode.code == code) {
				return mode;
			}
		}
		throw new IllegalArgumentException("unknown AssociationMode code: " + code);
	}
}
