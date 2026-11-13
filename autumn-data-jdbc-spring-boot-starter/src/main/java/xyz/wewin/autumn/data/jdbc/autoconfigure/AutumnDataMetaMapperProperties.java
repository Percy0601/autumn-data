package xyz.wewin.autumn.data.jdbc.autoconfigure;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCache;

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
	 * 动态层工作根目录，三类产物在其固定子目录下：{@code meta-class/}（元类定义 JSON）、
	 * {@code entity/}（生成的实体 .java）、{@code mapper/}（Mapper XML）。
	 * 空串表示纯内存模式：产物一律不落盘。
	 */
	private String metaDirectory = "autumn-meta";

	/**
	 * 子目录里的文件缺失时是否生成一份（元类定义 / 实体源码 / Mapper）。
	 * 已存在的文件永远不重写，所以关掉它只影响「第一次」。
	 */
	private boolean generateFiles = true;

	/**
	 * 按表缓存（表结构快照 / 动态实体 / Mapper AST）各自的条目上限，默认一万张表。
	 * 触顶按 LRU 淘汰冷表，下次访问自动重建，内存不随表数量无限增长。
	 */
	private int tableCacheSize = BoundedCache.DEFAULT_MAXIMUM_SIZE;

	/**
	 * 实体生成模式：{@code auto}（默认，Native Image 里自动退化到 generic）、
	 * {@code true}/{@code code}（运行期 javac 生成强类型子类，需要完整 JDK）、
	 * {@code false}/{@code generic}（通用 MetaClass，零反射，可 native 化）。
	 */
	private String entityCodegen = "auto";

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

	public String getEntityCodegen() {
		return this.entityCodegen;
	}

	public void setEntityCodegen(String entityCodegen) {
		this.entityCodegen = entityCodegen;
	}
}
