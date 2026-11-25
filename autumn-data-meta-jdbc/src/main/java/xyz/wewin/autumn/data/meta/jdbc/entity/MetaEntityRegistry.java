package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.common.meta.MetaValueReader;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaCache;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaCaches;

/**
 * 表结构 → 实体模型的注册表：统一产出通用 {@link MetaClass}，零代码生成、零反射。
 *
 * <p>行数据按列名写进 {@link MetaClass} 的属性列表（本质是一个带类型的 Map）：
 * 属性描述（列名 / Java 类型 / JDBC 类型 / 主键 / 自增）都来自表结构，读出来就能用
 * {@code getInt/getString/getBigDecimal} 取强类型值，不必为每张表生成子类、也不碰 javac。</p>
 *
 * <p>缓存键是「表名 + 结构指纹」，同一份结构只构建一次；{@code refresh} 丢掉当前可见的条目，
 * 不影响已拿到旧模型的执行中查询。整个引擎共享一个实例：实体只由表结构决定，与哪个池无关。</p>
 */
public final class MetaEntityRegistry {

	private final MetaCache<String, MetaEntityModel> models;

	public MetaEntityRegistry() {
		this(MetaCache.DEFAULT_MAXIMUM_SIZE);
	}

	public MetaEntityRegistry(int maximumSize) {
		this.models = MetaCaches.lru(maximumSize);
	}

	/** 当前表结构对应的实体模型；首次访问时构建，结构指纹变化就换一份。 */
	public MetaEntityModel model(MetaTable table) {
		String fingerprint = table.fingerprint();
		String key = normalize(table.name()) + ":" + fingerprint;
		return this.models.get(key, ignored -> build(table, fingerprint));
	}

	/** 实体类型恒为 {@link MetaClass}。 */
	public Class<? extends MetaClass> entityType(MetaTable table) {
		return MetaClass.class;
	}

	/** 表结构变化后丢弃该表所有版本的实体模型；正在执行的旧查询不受影响。 */
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

	private MetaEntityModel build(MetaTable table, String fingerprint) {
		MetaEntityReader reader = reader(table);
		Supplier<MetaClass> factory = () -> entity(table);
		return new MetaEntityModel(reader, fingerprint, factory);
	}

	private static MetaEntityReader reader(MetaTable table) {
		List<MetaProperty> columns = table.columns();
		return rs -> {
			MetaClass entity = entity(table);
			Map<String, MetaProperty> properties = new LinkedHashMap<>();
			for (MetaProperty property : entity.getProperties()) {
				properties.put(normalize(property.getName()), property);
			}
			Map<String, Integer> labels = MetaEntityColumns.index(rs);
			for (MetaProperty column : columns) {
				Integer index = labels.get(normalize(column.getName()));
				if (index == null) {
					continue;
				}
				Object raw = rs.getObject(index);
				if (raw != null) {
					MetaProperty property = properties.get(normalize(column.getName()));
					if (property != null) {
						// JSON 定义/数据库元数据声明了字段类型，按它收口，别让驱动类型不一致悄悄传下去。
						property.setValue(MetaValueReader.asType(raw, property.getType()));
					}
				}
			}
			entity.clearChanged();
			return entity;
		};
	}

	/** 每行一份独立的属性列表：值存在 {@link MetaProperty} 上，不能跨行共享描述对象。 */
	private static MetaClass entity(MetaTable table) {
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
