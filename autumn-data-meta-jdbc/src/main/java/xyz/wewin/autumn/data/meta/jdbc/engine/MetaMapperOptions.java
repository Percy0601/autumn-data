package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityStrategy;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCache;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories;

/**
 * 动态查询层的运行期策略。
 *
 * @param defaultListLimit   {@code findList} 未显式限行时的默认上限（防止全表拉取）
 * @param fetchPolicy        批量分块与关联取数上限（复用 autumn-data-jdbc 的策略对象）
 * @param logicDeleteColumns 逻辑删除列名（大小写不敏感）；命中后查询自动追加 {@code = 0}，未命中不追加
 * @param metaDirectory      工作根目录，三类产物在其固定子目录下（{@code meta-class} / {@code entity} /
 *                           {@code mapper}）；空串表示纯内存模式，产物一律不落盘
 * @param generateFiles      子目录里的文件缺失时是否生成一份（元类定义 / 实体源码 / Mapper）。
 *                           已存在的文件永远不重写，所以关掉它只影响「第一次」
 * @param tableCacheSize     按表缓存（表结构 / 动态实体 / Mapper AST / 执行计划）各自的条目上限
 * @param entityStrategy     实体形态：{@code CODE} 生成强类型子类，{@code GENERIC} 通用 MetaClass；
 *                           {@code auto} 在 Native Image 里自动选 {@code GENERIC}
 */
public record MetaMapperOptions(int defaultListLimit, FetchPolicy fetchPolicy, Set<String> logicDeleteColumns,
		String metaDirectory, boolean generateFiles, int tableCacheSize, MetaEntityStrategy entityStrategy) {

	public MetaMapperOptions {
		if (defaultListLimit <= 0) {
			throw new IllegalArgumentException("defaultListLimit must be > 0 but was " + defaultListLimit);
		}
		if (tableCacheSize <= 0) {
			throw new IllegalArgumentException("tableCacheSize must be > 0 but was " + tableCacheSize);
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
		metaDirectory = metaDirectory == null ? "" : metaDirectory.trim();
		entityStrategy = entityStrategy == null ? MetaEntityStrategy.auto() : entityStrategy;
	}

	public MetaMapperOptions(int defaultListLimit, FetchPolicy fetchPolicy, Set<String> logicDeleteColumns,
			String metaDirectory, boolean generateFiles, int tableCacheSize) {
		this(defaultListLimit, fetchPolicy, logicDeleteColumns, metaDirectory, generateFiles, tableCacheSize,
				MetaEntityStrategy.auto());
	}

	public MetaMapperOptions(int defaultListLimit, FetchPolicy fetchPolicy, Set<String> logicDeleteColumns,
			String metaDirectory, boolean generateFiles) {
		this(defaultListLimit, fetchPolicy, logicDeleteColumns, metaDirectory, generateFiles,
				BoundedCache.DEFAULT_MAXIMUM_SIZE, MetaEntityStrategy.auto());
	}

	/** 工作目录（根 + 三个子目录）。 */
	public MetaDirectories directories() {
		return MetaDirectories.of(this.metaDirectory);
	}

	/** 默认：列表上限 1000、批量 100 一批、不自动识别逻辑删除列、产物落 ./autumn-meta。 */
	public static MetaMapperOptions defaults() {
		return new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), MetaDirectories.DEFAULT_ROOT, true);
	}

	/** 纯内存：什么文件都不生成。测试与只读部署用。 */
	public static MetaMapperOptions inMemory() {
		return new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", false);
	}
}
