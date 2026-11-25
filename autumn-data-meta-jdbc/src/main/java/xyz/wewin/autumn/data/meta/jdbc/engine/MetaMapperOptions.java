package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaCache;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories;

/**
 * 动态查询层的运行期策略。
 *
 * @param defaultListLimit   {@code findList} 未显式限行时的默认上限（防止全表拉取）
 * @param fetchPolicy        批量分块与关联取数上限（复用 autumn-data-jdbc 的策略对象）
 * @param logicDeleteColumns 逻辑删除列名（大小写不敏感）；命中后查询自动追加 {@code = 0}，未命中不追加
 * @param metaDirectory      工作根目录；其下按 {@code {ds}-default/{ns}-default/{spec,auto}} 分层，
 *                           空串表示纯内存模式，产物一律不落盘
 * @param generateFiles      auto 语句 XML / 实体定义 JSON 缺失时是否按库结构生成一份模板；
 *                           关掉它只影响「落盘」，默认语句与表结构仍照常在内存里工作
 * @param tableCacheSize     按表缓存（表结构 / 实体 / 执行计划）各自的条目上限
 * @param statementCacheSize 语句 XML 解析结果（auto 文件 + spec 文件）各自的条目上限；默认 1000
 */
public record MetaMapperOptions(int defaultListLimit, FetchPolicy fetchPolicy, Set<String> logicDeleteColumns,
		String metaDirectory, boolean generateFiles, int tableCacheSize, int statementCacheSize) {

	public MetaMapperOptions {
		if (defaultListLimit <= 0) {
			throw new IllegalArgumentException("defaultListLimit must be > 0 but was " + defaultListLimit);
		}
		if (tableCacheSize <= 0) {
			throw new IllegalArgumentException("tableCacheSize must be > 0 but was " + tableCacheSize);
		}
		if (statementCacheSize <= 0) {
			throw new IllegalArgumentException("statementCacheSize must be > 0 but was " + statementCacheSize);
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
	}

	public MetaMapperOptions(int defaultListLimit, FetchPolicy fetchPolicy, Set<String> logicDeleteColumns,
			String metaDirectory, boolean generateFiles, int tableCacheSize) {
		this(defaultListLimit, fetchPolicy, logicDeleteColumns, metaDirectory, generateFiles, tableCacheSize, 1000);
	}

	public MetaMapperOptions(int defaultListLimit, FetchPolicy fetchPolicy, Set<String> logicDeleteColumns,
			String metaDirectory, boolean generateFiles) {
		this(defaultListLimit, fetchPolicy, logicDeleteColumns, metaDirectory, generateFiles,
				MetaCache.DEFAULT_MAXIMUM_SIZE, 1000);
	}

	/** 工作目录。 */
	public MetaDirectories directories() {
		return MetaDirectories.of(this.metaDirectory);
	}

	/** 默认：列表上限 1000、表结构不识别逻辑删除列、产物落 ./meta-data。 */
	public static MetaMapperOptions defaults() {
		return new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), MetaDirectories.DEFAULT_ROOT, true);
	}

	/** 纯内存：什么文件都不生成。测试与只读部署用。 */
	public static MetaMapperOptions inMemory() {
		return new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", false);
	}
}
