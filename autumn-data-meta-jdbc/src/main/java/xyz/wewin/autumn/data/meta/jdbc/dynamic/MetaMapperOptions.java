package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;

/**
 * 动态查询层的运行期策略。
 *
 * @param defaultListLimit   {@code findList} 未显式限行时的默认上限（防止全表拉取）
 * @param fetchPolicy        批量分块与关联取数上限（复用 autumn-data-jdbc 的策略对象）
 * @param logicDeleteColumns 逻辑删除列名（大小写不敏感）；命中后查询自动追加 {@code = 0}，未命中不追加
 */
public record MetaMapperOptions(int defaultListLimit, FetchPolicy fetchPolicy, Set<String> logicDeleteColumns) {

	public MetaMapperOptions {
		if (defaultListLimit <= 0) {
			throw new IllegalArgumentException("defaultListLimit must be > 0 but was " + defaultListLimit);
		}
		fetchPolicy = fetchPolicy == null ? FetchPolicy.defaults() : fetchPolicy;
		Set<String> normalized = new LinkedHashSet<>();
		if (logicDeleteColumns != null) {
			for (String column : logicDeleteColumns) {
				if (column != null && !column.isBlank()) {
					normalized.add(column.toLowerCase(Locale.ROOT));
				}
			}
		}
		logicDeleteColumns = Set.copyOf(normalized);
	}

	/** 默认：列表上限 1000、批量 100 一批、不自动识别逻辑删除列。 */
	public static MetaMapperOptions defaults() {
		return new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of());
	}
}
