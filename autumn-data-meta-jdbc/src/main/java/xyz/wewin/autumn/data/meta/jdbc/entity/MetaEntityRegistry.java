package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCache;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCaches;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories;

/**
 * 表结构 → 实体模型的注册表，按 {@link MetaEntityStrategy} 产出两种等价形态。
 *
 * <ul>
 *   <li>{@link MetaEntityStrategy#CODE}：生成 {@code MetaClass} 子类 → javac 内存编译 → 加载；
 *       每个条目持有一个 {@code Class} 与独立 ClassLoader，结构指纹变化就落到另一个包下的新类；</li>
 *   <li>{@link MetaEntityStrategy#GENERIC}：不生成类，逐行把结果集写进通用 {@code MetaClass}；
 *       零反射、零代码生成，供 GraalVM Native Image 使用。</li>
 * </ul>
 *
 * <p>缓存键是「表名 + 结构指纹」，同一份结构只构建一次；{@code refresh} 丢掉当前可见的条目，
 * 不影响已拿到旧模型的执行中查询。条目的上限同样交给 {@link BoundedCache} 的 LRU，
 * 触顶淘汰最久未用的结构版本。</p>
 *
 * <p>整个引擎共享一个实例：实体只由表结构决定，与哪个数据源池无关。</p>
 */
public final class MetaEntityRegistry {

	/**
	 * 代码生成器按类名加载：native 镜像里这个类不可达，{@code javax.tools} 就不会被带进镜像。
	 *
	 * <p>类名刻意在运行期拼装，不写成 {@code Class.forName} 的字面量参数：native-image 会把
	 * 常量类名的 {@code Class.forName} 自动注册成反射目标，那样代码生成器（连同
	 * {@code jdk.compiler}）又会被拉回镜像，失去「native 不碰 javac」的意义。</p>
	 */
	private static String compilerClassName() {
		StringBuilder name = new StringBuilder("xyz.wewin.autumn.data.meta.jdbc.entity.");
		name.append("MetaEntityCompiler");
		return name.toString();
	}

	private final Path sourceDirectory;

	private final MetaEntitySource source;

	private final MetaEntityStrategy strategy;

	private final BoundedCache<String, MetaEntityModel> models;

	/** 普通 JVM 默认：代码生成。 */
	public MetaEntityRegistry() {
		this(MetaEntityStrategy.auto());
	}

	public MetaEntityRegistry(MetaEntityStrategy strategy) {
		this(new MetaEntitySource(), null, BoundedCache.DEFAULT_MAXIMUM_SIZE, strategy);
	}

	/**
	 * @param directories   工作目录：生成源码落 {@code <root>/entity}；纯内存目录则不落盘
	 * @param generateFiles 源码目录缺失时是否创建（源码本身只在配置了目录时落盘）
	 * @param maximumSize   结构版本的缓存上限（默认 {@value BoundedCache#DEFAULT_MAXIMUM_SIZE}）
	 */
	public MetaEntityRegistry(MetaDirectories directories, boolean generateFiles, int maximumSize) {
		this(directories, generateFiles, maximumSize, MetaEntityStrategy.auto());
	}

	public MetaEntityRegistry(MetaDirectories directories, boolean generateFiles, int maximumSize,
			MetaEntityStrategy strategy) {
		this(new MetaEntitySource(), directories == null ? null : directories.entityDirectory(), maximumSize, strategy);
	}

	public MetaEntityRegistry(MetaEntitySource source, Path entityDirectory, int maximumSize) {
		this(source, entityDirectory, maximumSize, MetaEntityStrategy.auto());
	}

	public MetaEntityRegistry(MetaEntitySource source, Path entityDirectory, int maximumSize,
			MetaEntityStrategy strategy) {
		if (strategy == null) {
			strategy = MetaEntityStrategy.auto();
		}
		if (strategy == MetaEntityStrategy.CODE && MetaEntityStrategy.inNativeImage()) {
			throw new MetaSqlException("entity code generation is not available in GraalVM Native Image; "
					+ "use MetaEntityStrategy.GENERIC or auto");
		}
		this.source = source == null ? new MetaEntitySource() : source;
		this.sourceDirectory = entityDirectory;
		this.strategy = strategy;
		this.models = BoundedCaches.lru(maximumSize);
	}

	/** 当前表结构对应的实体模型；CODE 首次访问时生成源码 → javac 编译 → 加载，GENERIC 只建通用容器。 */
	public MetaEntityModel model(MetaTable table) {
		String fingerprint = table.fingerprint();
		String key = normalize(table.name()) + ":" + fingerprint;
		return this.models.get(key, ignored -> build(table, fingerprint));
	}

	/** 只看类型、不触发查询：上层要检查生成的 getter / 注解时用；GENERIC 下恒为 {@code MetaClass}。 */
	public Class<? extends MetaClass> entityType(MetaTable table) {
		return model(table).entityType();
	}

	/** 表结构变化后丢弃该表所有版本的模型；正在执行的旧查询不受影响。 */
	public void refresh(String tableName) {
		String prefix = normalize(tableName) + ":";
		this.models.invalidateIf(key -> key.startsWith(prefix));
	}

	public void refreshAll() {
		this.models.invalidateAll();
	}

	/** 已缓存的结构版本数（诊断用）。 */
	public int size() {
		return (int) this.models.estimatedSize();
	}

	/** 缓存上限（诊断用）。 */
	public int maximumSize() {
		return this.models.maximumSize();
	}

	/** 生成源码用的代码生成器（无模板文件，可直接复用）。 */
	public MetaEntitySource source() {
		return this.source;
	}

	/** 当前生效的实体策略。 */
	public MetaEntityStrategy strategy() {
		return this.strategy;
	}

	private MetaEntityModel build(MetaTable table, String fingerprint) {
		if (this.strategy == MetaEntityStrategy.GENERIC) {
			return genericModel(table, fingerprint);
		}
		return codegenModel(table, fingerprint);
	}

	// ------------------------------------------------------------------
	// CODE：生成子类 + javac
	// ------------------------------------------------------------------

	private MetaEntityModel codegenModel(MetaTable table, String fingerprint) {
		MetaEntitySource.Source source = this.source.render(table);
		Class<? extends MetaClass> type = compileSource(source);
		MetaEntityReader reader;
		Supplier<MetaClass> factory;
		try {
			reader = (MetaEntityReader) type.getField("READER").get(null);
			@SuppressWarnings("unchecked")
			Supplier<MetaClass> newFactory = (Supplier<MetaClass>) type.getField("NEW").get(null);
			factory = newFactory;
		}
		catch (ReflectiveOperationException ex) {
			throw new MetaSqlException("generated entity '" + source.qualifiedName()
					+ "' has no public static MetaEntityReader READER / Supplier NEW", ex);
		}
		return new MetaEntityModel(type, reader, source.java(), fingerprint, source.qualifiedName(), factory);
	}

	/**
	 * 按类名反射调用代码生成器。
	 *
	 * <p>刻意不直接引用 {@link MetaEntityCompiler}：那会把 {@code javax.tools} 变成可达代码，
	 * native-image 构建时就不得不处理一个不支持的 JDK 模块。native 下策略是 GENERIC，这段不会执行；
	 * JVM 下按类名加载，行为不变。</p>
	 */
	private Class<? extends MetaClass> compileSource(MetaEntitySource.Source source) {
		String compilerClass = compilerClassName();
		try {
			Class<?> compiler = Class.forName(compilerClass, true, MetaEntityRegistry.class.getClassLoader());
			Method compile = compiler.getMethod("compile", MetaEntitySource.Source.class, Path.class);
			@SuppressWarnings("unchecked")
			Class<? extends MetaClass> type = (Class<? extends MetaClass>) compile.invoke(null, source,
					this.sourceDirectory);
			return type;
		}
		catch (ClassNotFoundException ex) {
			throw new MetaSqlException("entity code generation is unavailable: " + compilerClass
					+ " is not on the classpath; switch entity-codegen to generic", ex);
		}
		catch (NoSuchMethodException | IllegalAccessException ex) {
			throw new MetaSqlException("cannot invoke " + compilerClass + ".compile", ex);
		}
		catch (InvocationTargetException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new MetaSqlException("entity code generation failed for '" + source.qualifiedName() + "'", cause);
		}
	}

	// ------------------------------------------------------------------
	// GENERIC：通用 MetaClass，零反射、零代码生成
	// ------------------------------------------------------------------

	private MetaEntityModel genericModel(MetaTable table, String fingerprint) {
		MetaEntityReader reader = genericReader(table);
		Supplier<MetaClass> factory = () -> genericEntity(table);
		return new MetaEntityModel(MetaClass.class, reader, (rs, rowNum) -> reader.read(rs), null, fingerprint,
				MetaClass.class.getName(), factory);
	}

	private static MetaEntityReader genericReader(MetaTable table) {
		List<MetaProperty> columns = table.columns();
		return rs -> {
			MetaClass entity = genericEntity(table);
			Map<String, MetaProperty> properties = new LinkedHashMap<>();
			for (MetaProperty property : entity.getProperties()) {
				properties.put(normalize(property.getName()), property);
			}
			Map<String, Integer> labels = MetaEntityColumns.index(rs);
			for (MetaProperty column : columns) {
				Integer index = labels.get(normalize(column.getName()));
				if (index == null) {
					// SELECT 没投影这一列：属性保持 null
					continue;
				}
				Object raw = rs.getObject(index);
				if (raw != null) {
					MetaProperty property = properties.get(normalize(column.getName()));
					if (property != null) {
						// 直接写值、不标脏：读出来的行必须和 CODE 模式一样是「干净」的
						property.setValue(raw);
					}
				}
			}
			entity.clearChanged();
			return entity;
		};
	}

	/** 每行一份独立的属性列表：值存在 {@link MetaProperty} 上，不能跨行共享描述对象。 */
	private static MetaClass genericEntity(MetaTable table) {
		MetaClass entity = new MetaClass();
		entity.setName(table.name());
		entity.setNamespace(table.schema());
		entity.setMetaType(table.metaClass().getMetaType());
		List<MetaProperty> copies = new ArrayList<>(table.columns().size());
		for (MetaProperty column : table.columns()) {
			copies.add(copyOf(column));
		}
		entity.setProperties(copies);
		return entity;
	}

	private static MetaProperty copyOf(MetaProperty column) {
		MetaProperty copy = new MetaProperty();
		copy.setName(column.getName());
		copy.setType(column.getType());
		copy.setTypeName(column.getTypeName());
		copy.setJdbcType(column.getJdbcType());
		copy.setSqlTypeName(column.getSqlTypeName());
		copy.setNullable(column.isNullable());
		copy.setPrimaryKey(column.isPrimaryKey());
		copy.setAutoIncrement(column.isAutoIncrement());
		copy.setDefaultValue(column.getDefaultValue());
		return copy;
	}

	private static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
