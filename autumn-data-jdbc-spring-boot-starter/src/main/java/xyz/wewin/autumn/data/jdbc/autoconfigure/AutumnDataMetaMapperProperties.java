package xyz.wewin.autumn.data.jdbc.autoconfigure;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import xyz.wewin.autumn.data.meta.jdbc.dynamic.BoundedCache;

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
	 * 动态 Mapper XML 目录；首次访问某张表且文件不存在时按元数据生成。
	 * 空串表示只生成到内存、不落盘。
	 */
	private String xmlDirectory = "meta-mappers";

	/** 文件不存在时是否自动生成 Mapper XML；false 时缺失直接报错。 */
	private boolean generateXml = true;

	/**
	 * 生成的动态实体 {@code .java} 源码落盘目录（便于查看、排错、固化成静态类）。
	 * 空串表示只在临时目录编译，不额外留存。
	 */
	private String entitySourceDirectory = "";

	/**
	 * 按表缓存（表结构快照 / 动态实体类 / Mapper AST）各自的条目上限，默认一万张表。
	 * 触顶按 LRU 淘汰冷表，下次访问自动重建，内存不随表数量无限增长。
	 */
	private int tableCacheSize = BoundedCache.DEFAULT_MAXIMUM_SIZE;

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

	public String getXmlDirectory() {
		return this.xmlDirectory;
	}

	public void setXmlDirectory(String xmlDirectory) {
		this.xmlDirectory = xmlDirectory;
	}

	public boolean isGenerateXml() {
		return this.generateXml;
	}

	public void setGenerateXml(boolean generateXml) {
		this.generateXml = generateXml;
	}

	public String getEntitySourceDirectory() {
		return this.entitySourceDirectory;
	}

	public void setEntitySourceDirectory(String entitySourceDirectory) {
		this.entitySourceDirectory = entitySourceDirectory;
	}

	public int getTableCacheSize() {
		return this.tableCacheSize;
	}

	public void setTableCacheSize(int tableCacheSize) {
		this.tableCacheSize = tableCacheSize;
	}
}
