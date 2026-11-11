package xyz.wewin.autumn.data.meta.jdbc.dynamic;

/**
 * 更新时的显式空值标记。
 *
 * <p>{@code updateById} 是部分更新：没传的列保持原值；要把某列置空，必须显式传
 * {@link #NULL}，否则 {@code null} 只会被视为“本次不更新这一列”。</p>
 */
public final class MetaValue {

	/** 显式置空标记。 */
	public static final MetaValue NULL = new MetaValue();

	private MetaValue() {
	}

	@Override
	public String toString() {
		return "MetaValue.NULL";
	}
}
