package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 表结构 → 已编译实体类的注册表。
 *
 * <p>缓存键是「表名 + 结构指纹」：同一份结构只生成/编译一次；加了列、改了类型，指纹变化会
 * 落到另一个包下的新类，旧实例继续由各自的 ClassLoader 持有，不会被重定义、也不会 ClassCastException。
 * 因此 {@code refresh} 只是丢掉当前可见的条目，不需要停机或替换 JVM 里的类。</p>
 *
 * <p>整个引擎共享一个实例：生成的类只由表结构决定，与哪个数据源池无关。</p>
 */
public final class MetaEntityRegistry {

	private final Path sourceDirectory;

	private final ConcurrentHashMap<String, MetaEntityModel> models = new ConcurrentHashMap<>();

	public MetaEntityRegistry() {
		this((String) null);
	}

	public MetaEntityRegistry(String sourceDirectory) {
		this.sourceDirectory = sourceDirectory == null || sourceDirectory.isBlank()
				? null : Paths.get(sourceDirectory.trim());
	}

	/** 当前表结构对应的实体模型；首次访问时 FreeMarker 生成 → javac 编译 → 加载。 */
	public MetaEntityModel model(MetaTable table) {
		String fingerprint = MetaEntityTemplate.fingerprint(table);
		String key = normalize(table.name()) + ":" + fingerprint;
		return this.models.computeIfAbsent(key, ignored -> compile(table, fingerprint));
	}

	/** 只看类型、不触发查询：上层要检查生成的 getter / 注解时用。 */
	public Class<? extends MetaClass> entityType(MetaTable table) {
		return model(table).entityType();
	}

	/** 表结构变化后丢弃该表所有版本的类；正在执行的旧查询不受影响。 */
	public void refresh(String tableName) {
		String prefix = normalize(tableName) + ":";
		this.models.keySet().removeIf(key -> key.startsWith(prefix));
	}

	public void refreshAll() {
		this.models.clear();
	}

	/** 已缓存的结构版本数（诊断用）。 */
	public int size() {
		return this.models.size();
	}

	private MetaEntityModel compile(MetaTable table, String fingerprint) {
		MetaEntityTemplate.Source source = MetaEntityTemplate.render(table);
		Class<? extends MetaClass> type = MetaEntityCompiler.compile(source, this.sourceDirectory);
		MetaEntityReader reader;
		try {
			reader = (MetaEntityReader) type.getField("READER").get(null);
		}
		catch (ReflectiveOperationException ex) {
			throw new MetaSqlException("generated entity '" + source.qualifiedName()
					+ "' has no public static MetaEntityReader READER", ex);
		}
		return new MetaEntityModel(type, reader, source.java(), fingerprint, source.qualifiedName());
	}

	private static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
