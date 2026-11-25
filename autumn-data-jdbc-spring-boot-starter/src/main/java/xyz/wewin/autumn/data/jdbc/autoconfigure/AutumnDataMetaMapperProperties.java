package xyz.wewin.autumn.data.jdbc.autoconfigure;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import xyz.wewin.autumn.data.meta.jdbc.support.MetaCache;

/**
 * 动态表 CRUD 配置（前缀 {@code autumn.data.meta.mapper}）。
 */
@ConfigurationProperties(prefix = "autumn.data.meta.mapper")
public class AutumnDataMetaMapperProperties {

	/** 是否安装 {@code MetaMapper} 静态门面（默认开启）。 */
	private boolean enabled = true;

	/** 默认池名；{@code MetaMapper} 的静态方法走这个池。 */
	private String defaultPoolName = "default";

	/** {@code findList} 未显式限行时的默认上限。 */
	private int defaultListLimit = 1000;

	/**
	 * 逻辑删除列名（大小写不敏感）：命中后查询自动追加 {@code = 0}。
	 * 设为空集合可关闭自动识别。
	 */
	private Set<String> logicDeleteColumns = new LinkedHashSet<>(
			Set.of("deleted_flag", "del_flag", "is_deleted"));

	/**
	 * 动态层工作根目录；其下按 {@code {数据源}-default/{命名空间}-default/{spec,auto}} 分层：
	 * {@code spec/} 放实体定义 JSON 与手写语句 XML（使用方资产，框架只读），
	 * {@code auto/xml} 放框架生成的默认语句。空串表示纯内存模式。
	 */
	private String metaDirectory = "meta-data";

	/**
	 * auto 语句 XML / 实体定义 JSON 缺失时是否按表结构生成一份模板。
	 * 关掉它只影响「落盘」，默认语句与表结构仍照常在内存里工作。
	 */
	private boolean generateFiles = true;

	/**
	 * 按表缓存（表结构快照 / 动态实体 / 执行计划）各自的条目上限，默认一万张表。
	 * 触顶按 LRU 淘汰冷表，下次访问自动重建，内存不随表数量无限增长。
	 */
	private int tableCacheSize = MetaCache.DEFAULT_MAXIMUM_SIZE;

	/**
	 * 语句 XML 解析结果的缓存上限，默认 1000 张表；auto 文件与 spec 文件分开计数。
	 * 淘汰后下次访问重新从磁盘读取，磁盘文件是唯一真源，便于人工调整 SQL。
	 */
	private int statementCacheSize = 1000;

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getDefaultPoolName() {
		return this.defaultPoolName;
	}

	public void setDefaultPoolName(String defaultPoolName) {
		this.defaultPoolName = defaultPoolName;
	}

	public int getDefaultListLimit() {
		return this.defaultListLimit;
	}

	public void setDefaultListLimit(int defaultListLimit) {
		this.defaultListLimit = defaultListLimit;
	}

	public Set<String> getLogicDeleteColumns() {
		return this.logicDeleteColumns;
	}

	public void setLogicDeleteColumns(Set<String> logicDeleteColumns) {
		this.logicDeleteColumns = logicDeleteColumns;
	}

	public String getMetaDirectory() {
		return this.metaDirectory;
	}

	public void setMetaDirectory(String metaDirectory) {
		this.metaDirectory = metaDirectory;
	}

	public boolean isGenerateFiles() {
		return this.generateFiles;
	}

	public void setGenerateFiles(boolean generateFiles) {
		this.generateFiles = generateFiles;
	}

	public int getTableCacheSize() {
		return this.tableCacheSize;
	}

	public void setTableCacheSize(int tableCacheSize) {
		this.tableCacheSize = tableCacheSize;
	}

	public int getStatementCacheSize() {
		return this.statementCacheSize;
	}

	public void setStatementCacheSize(int statementCacheSize) {
		this.statementCacheSize = statementCacheSize;
	}

}
