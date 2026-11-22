package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
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
import xyz.wewin.autumn.data.common.meta.MetaType;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaJdbcTypes;
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

	/** 查询结果 VO 的源码目录；与表实体分开，低代码平台按目录管理大量 VO。 */
	private final Path shapeSourceDirectory;

	private final MetaEntitySource source;

	private final MetaEntityStrategy strategy;

	private final BoundedCache<String, MetaEntityModel> models;

	/** 自定义结果形状模型：键 = 表名 + 语句 id + 列形状指纹。 */
	private final BoundedCache<String, MetaShapeModel> shapes;

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
		this(new MetaEntitySource(), directories == null ? null : directories.entityDirectory(),
				directories == null ? null : directories.voDirectory(), maximumSize, strategy);
	}

	public MetaEntityRegistry(MetaEntitySource source, Path entityDirectory, int maximumSize) {
		this(source, entityDirectory, entityDirectory, maximumSize, MetaEntityStrategy.auto());
	}

	public MetaEntityRegistry(MetaEntitySource source, Path entityDirectory, int maximumSize,
			MetaEntityStrategy strategy) {
		this(source, entityDirectory, entityDirectory, maximumSize, strategy);
	}

	public MetaEntityRegistry(MetaEntitySource source, Path entityDirectory, Path shapeSourceDirectory,
			int maximumSize, MetaEntityStrategy strategy) {
		if (strategy == null) {
			strategy = MetaEntityStrategy.auto();
		}
		if (strategy == MetaEntityStrategy.CODE && MetaEntityStrategy.inNativeImage()) {
			throw new MetaSqlException("entity code generation is not available in GraalVM Native Image; "
					+ "use MetaEntityStrategy.GENERIC or auto");
		}
		this.source = source == null ? new MetaEntitySource() : source;
		this.sourceDirectory = entityDirectory;
		this.shapeSourceDirectory = shapeSourceDirectory;
		this.strategy = strategy;
		this.models = BoundedCaches.lru(maximumSize);
		this.shapes = BoundedCaches.lru(maximumSize);
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

	/** 表结构变化后丢弃该表所有版本的实体模型；正在执行的旧查询不受影响。 */
	public void refresh(String tableName) {
		String prefix = normalize(tableName) + ":";
		this.models.invalidateIf(key -> key.startsWith(prefix));
	}

	/** 语句文本变化后丢掉该表的形状模型（列结构可能一起变）。 */
	public void refreshShapes(String tableName) {
		String prefix = normalize(tableName) + ":";
		this.shapes.invalidateIf(key -> key.startsWith(prefix));
	}

	public void refreshAll() {
		this.models.invalidateAll();
	}

	public void refreshAllShapes() {
		this.shapes.invalidateAll();
	}

	/**
	 * 自定义结果形状对应的模型：CODE 模式为「语句 id 派生的具体类」编译加载，
	 * GENERIC 模式回退到通用 {@link MetaClass}。同一语句的同一版列结构只构建一次。
	 */
	public MetaShapeModel shapeModel(MetaTable table, String statementId, java.util.List<MetaShapeColumn> columns) {
		String fingerprint = MetaShapeSource.fingerprint(table, statementId, columns);
		String key = normalize(table.name()) + ":" + normalize(statementId) + ":" + fingerprint;
		return this.shapes.get(key, ignored -> buildShape(table, statementId, columns, fingerprint));
	}

	/** 已缓存的结果形状版本数（诊断用）。 */
	public int shapeSize() {
		return (int) this.shapes.estimatedSize();
	}

	/** 结果形状缓存上限（诊断用）。 */
	public int shapeMaximumSize() {
		return this.shapes.maximumSize();
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

	/**
	 * VO 源码落盘后归使用方所有：文件存在就按磁盘版本编译，不再用内存生成的新文本覆盖。
	 * 这样人工调整 getter / 注释 / 读取逻辑能真正生效；结构变化会换指纹包，自然是一份新文件。
	 */
	private MetaEntitySource.Source persistedShapeSource(MetaEntitySource.Source rendered) {
		if (this.shapeSourceDirectory == null) {
			return rendered;
		}
		Path file = this.shapeSourceDirectory.resolve(rendered.packageName().replace('.', '/'))
				.resolve(rendered.className() + ".java");
		if (!Files.isRegularFile(file)) {
			return rendered;
		}
		try {
			return new MetaEntitySource.Source(rendered.packageName(), rendered.className(),
					rendered.qualifiedName(), rendered.fingerprint(), Files.readString(file));
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to read generated VO source '" + file + "'", ex);
		}
	}

	private MetaShapeModel buildShape(MetaTable table, String statementId, List<MetaShapeColumn> columns,
			String fingerprint) {
		if (this.strategy == MetaEntityStrategy.GENERIC) {
			return genericShapeModel(table, statementId, columns, fingerprint);
		}
		MetaEntitySource.Source rendered = MetaShapeSource.render(table, statementId, columns);
		MetaEntitySource.Source source = persistedShapeSource(rendered);
		Class<? extends MetaClass> type = compileSource(source, "compileShape", this.shapeSourceDirectory);
		MetaEntityReader reader;
		Supplier<MetaClass> factory;
		try {
			reader = (MetaEntityReader) type.getField("READER").get(null);
			@SuppressWarnings("unchecked")
			Supplier<MetaClass> newFactory = (Supplier<MetaClass>) type.getField("NEW").get(null);
			factory = newFactory;
		}
		catch (ReflectiveOperationException ex) {
			throw new MetaSqlException("generated result shape '" + source.qualifiedName()
					+ "' has no public static MetaEntityReader READER / Supplier NEW", ex);
		}
		return new MetaShapeModel(type, reader, factory, source.packageName(), source.className(),
				source.qualifiedName(), fingerprint, List.copyOf(columns));
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

	private Class<? extends MetaClass> compileSource(MetaEntitySource.Source source) {
		return compileSource(source, "compile", this.sourceDirectory);
	}

	/**
	 * 按类名反射调用代码生成器；方法名固定为 {@code compile}（实体）或 {@code compileShape}（结果形状）。
	 *
	 * <p>刻意不直接引用 {@link MetaEntityCompiler}：那会把 {@code javax.tools} 变成可达代码，
	 * native-image 构建时就不得不处理一个不支持的 JDK 模块。native 下策略是 GENERIC，这段不会执行；
	 * JVM 下按类名加载，行为不变。</p>
	 */
	private Class<? extends MetaClass> compileSource(MetaEntitySource.Source source, String methodName,
			Path sourceDirectory) {
		String compilerClass = compilerClassName();
		try {
			Class<?> compiler = Class.forName(compilerClass, true, MetaEntityRegistry.class.getClassLoader());
			Method compile = compiler.getMethod(methodName, MetaEntitySource.Source.class, Path.class);
			@SuppressWarnings("unchecked")
			Class<? extends MetaClass> type = (Class<? extends MetaClass>) compile.invoke(null, source,
					sourceDirectory);
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

	/**
	 * GENERIC 回退：不生成类，逐行按列序号写 {@link MetaProperty}。
	 * 行类型就是 {@link MetaClass} 本身，但属性列表仍带列名/类型/JDBC 类型，语义与 CODE 一致。
	 */
	private MetaShapeModel genericShapeModel(MetaTable table, String statementId, List<MetaShapeColumn> columns,
			String fingerprint) {
		Supplier<MetaClass> factory = () -> {
			MetaClass row = new MetaClass();
			row.setName(table.name());
			row.setNamespace(table.schema());
			row.setMetaType(MetaType.META_TYPE_VO);
			List<MetaProperty> properties = new ArrayList<>(columns.size());
			for (MetaShapeColumn column : columns) {
				MetaProperty property = new MetaProperty();
				property.setName(column.name());
				property.setType(column.javaType());
				property.setTypeName(column.javaType());
				property.setJdbcType(column.jdbcType());
				property.setSqlTypeName(column.sqlTypeName());
				property.setNullable(column.nullable());
				properties.add(property);
			}
			row.setProperties(properties);
			return row;
		};
		MetaEntityReader reader = rs -> {
			MetaClass row = factory.get();
			List<MetaProperty> properties = row.getProperties();
			for (int i = 0; i < columns.size(); i++) {
				properties.get(i).setValue(MetaJdbcTypes.value(rs.getObject(columns.get(i).index())));
			}
			row.clearChanged();
			return row;
		};
		return new MetaShapeModel(MetaClass.class, reader, factory, MetaClass.class.getPackageName(),
				MetaClass.class.getSimpleName(), MetaClass.class.getName(), fingerprint, List.copyOf(columns));
	}

	private static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
