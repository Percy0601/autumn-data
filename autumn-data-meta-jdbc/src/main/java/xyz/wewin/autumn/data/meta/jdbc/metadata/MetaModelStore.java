package xyz.wewin.autumn.data.meta.jdbc.metadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.jayway.jsonpath.Configuration;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.common.meta.MetaType;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaCache;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaCaches;

/**
 * 实体定义仓库：{@code {根}/{ds}-default/{ns}-default/spec/{表名}.json}。
 *
 * <p>数据库元数据是事实，这份 JSON 是使用方对事实的<b>解释</b>：列的 Java 类型、谁是主键、
 * 哪个是逻辑删除列、投影顺序都可以在这里定下来而不用改表。合并规则是
 * <b>定义里出现的字段以定义为准，定义里没写的字段用库补齐</b>。</p>
 *
 * <p>文件由使用方维护，框架<b>只在缺失时按库结构生成一份模板</b>、之后永不重写；
 * 定义里的列在库里不存在时丢弃并告警。解析结果按「文件 + 修改时间 + 大小」进有界 LRU，
 * 改过下次就生效，不重启。</p>
 */
public final class MetaModelStore {

	private static final Logger LOG = Logger.getLogger(MetaModelStore.class.getName());

	private static final String SUFFIX = ".json";

	private final Path directory;

	private final boolean generateFiles;

	private final MetaCache<String, Map<String, Object>> definitions;

	/**
	 * @param directory     定义目录（{@code .../{ns}-default/spec}）；{@code null} 表示不读不写
	 * @param generateFiles 目录里没有该表定义时是否按库结构生成一份模板
	 * @param maximumSize   定义条目上限
	 */
	public MetaModelStore(Path directory, boolean generateFiles, int maximumSize) {
		this.directory = directory;
		this.generateFiles = generateFiles;
		this.definitions = MetaCaches.lru(maximumSize);
	}

	/** 纯内存：没有定义文件，表结构完全以数据库为准。 */
	public static MetaModelStore none() {
		return new MetaModelStore(null, false, 16);
	}

	/** 把库结构快照与该表的定义合并成最终快照。 */
	public MetaTable apply(MetaTable fromDatabase) {
		Path file = file(fromDatabase.name());
		if (file == null) {
			return fromDatabase;
		}
		if (!Files.isRegularFile(file) && !generate(file, fromDatabase)) {
			return fromDatabase;
		}
		Map<String, Object> definition = read(file);
		return definition == null ? fromDatabase : merged(fromDatabase, definition);
	}

	/** 丢掉解析结果（定义文件本身不会被重写）。 */
	public void refresh(String tableName) {
		this.definitions.invalidateIf(key -> key.startsWith(normalize(tableName) + '@'));
	}

	public void refreshAll() {
		this.definitions.invalidateAll();
	}

	public long size() {
		return this.definitions.estimatedSize();
	}

	/** 该表的定义文件路径；纯内存模式返回 {@code null}。 */
	public Path file(String tableName) {
		return this.directory == null ? null : this.directory.resolve(safe(tableName) + SUFFIX);
	}

	private static String safe(String name) {
		return name.replaceAll("[^A-Za-z0-9_.-]", "_").replace("..", "_");
	}

	// ------------------------------------------------------------------
	// 合并：定义优先，缺字段用库补齐
	// ------------------------------------------------------------------

	private static MetaTable merged(MetaTable fromDatabase, Map<String, Object> definition) {
		Map<String, MetaProperty> byName = new LinkedHashMap<>();
		for (MetaProperty column : fromDatabase.columns()) {
			byName.put(normalize(column.getName()), column);
		}
		List<MetaProperty> merged = new ArrayList<>(fromDatabase.columns().size());
		Set<String> taken = new LinkedHashSet<>();
		for (Map<String, Object> declared : declaredProperties(definition)) {
			String name = text(declared.get("name"));
			MetaProperty column = name == null ? null : byName.get(normalize(name));
			if (column == null) {
				LOG.log(Level.WARNING, "entity definition for table ''{0}'' declares column ''{1}'' that the "
						+ "database does not have; the declaration is ignored",
						new Object[] { fromDatabase.name(), name });
				continue;
			}
			if (!taken.add(normalize(name))) {
				continue;
			}
			merged.add(override(column, declared));
		}
		for (MetaProperty column : fromDatabase.columns()) {
			if (!taken.contains(normalize(column.getName()))) {
				merged.add(column);
			}
		}
		MetaClass metaClass = new MetaClass();
		// 顶层就是类定义：name 必须与物理表一致（不一致只告警，防止 SQL 指错表），
		// namespace / metaType 按定义覆盖；comment 是给使用方看的说明，框架忽略。
		String declaredName = text(definition.get("name"));
		if (declaredName != null && !normalize(declaredName).equals(normalize(fromDatabase.name()))) {
			LOG.log(Level.WARNING, "entity definition name ''{0}'' differs from table ''{1}''; table name wins",
					new Object[] { declaredName, fromDatabase.name() });
		}
		metaClass.setName(fromDatabase.name());
		String declaredNamespace = text(definition.get("namespace"));
		metaClass.setNamespace(declaredNamespace == null ? fromDatabase.schema() : declaredNamespace);
		metaClass.setMetaType(metaType(definition));
		metaClass.setProperties(merged);
		return new MetaTable(metaClass, logicDeleteColumn(definition, merged, fromDatabase.logicDeleteColumn()));
	}

	private static MetaType metaType(Map<String, Object> definition) {
		String declared = text(definition.get("metaType"));
		if (declared == null || declared.isBlank()) {
			return MetaType.META_TYPE_ENTITY;
		}
		try {
			return MetaType.of(declared);
		}
		catch (IllegalArgumentException ex) {
			LOG.log(Level.WARNING, "entity definition has unknown metaType ''{0}''; ENTITY is used", declared);
			return MetaType.META_TYPE_ENTITY;
		}
	}

	/** 只覆盖定义里真的写了的键；没写的键保持库里的值。 */
	private static MetaProperty override(MetaProperty column, Map<String, Object> declared) {
		MetaProperty merged = copy(column);
		if (declared.containsKey("type")) {
			merged.setType(text(declared.get("type")));
		}
		if (declared.containsKey("typeName")) {
			merged.setTypeName(text(declared.get("typeName")));
		}
		if (declared.containsKey("jdbcType")) {
			merged.setJdbcType(number(declared.get("jdbcType"), merged.getJdbcType()));
		}
		if (declared.containsKey("sqlTypeName")) {
			merged.setSqlTypeName(text(declared.get("sqlTypeName")));
		}
		if (declared.containsKey("nullable")) {
			merged.setNullable(Boolean.TRUE.equals(declared.get("nullable")));
		}
		if (declared.containsKey("primaryKey")) {
			merged.setPrimaryKey(Boolean.TRUE.equals(declared.get("primaryKey")));
		}
		if (declared.containsKey("autoIncrement")) {
			merged.setAutoIncrement(Boolean.TRUE.equals(declared.get("autoIncrement")));
		}
		if (declared.containsKey("defaultValue")) {
			merged.setDefaultValue(text(declared.get("defaultValue")));
		}
		return merged;
	}

	private static MetaProperty copy(MetaProperty source) {
		MetaProperty copy = new MetaProperty();
		copy.setName(source.getName());
		copy.setType(source.getType());
		copy.setTypeName(source.getTypeName());
		copy.setJdbcType(source.getJdbcType());
		copy.setSqlTypeName(source.getSqlTypeName());
		copy.setNullable(source.isNullable());
		copy.setPrimaryKey(source.isPrimaryKey());
		copy.setAutoIncrement(source.isAutoIncrement());
		copy.setDefaultValue(source.getDefaultValue());
		return copy;
	}

	private static String logicDeleteColumn(Map<String, Object> definition, List<MetaProperty> columns,
			String fromDatabase) {
		String declared = text(definition.get("logicDeleteColumn"));
		if (declared == null || declared.isBlank()) {
			return fromDatabase;
		}
		for (MetaProperty column : columns) {
			if (normalize(column.getName()).equals(normalize(declared))) {
				return column.getName();
			}
		}
		LOG.log(Level.WARNING, "entity definition declares logic delete column ''{0}'' which is not a column"
				+ " of this table; falling back to the configured detection", declared);
		return fromDatabase;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> declaredProperties(Map<String, Object> definition) {
		Object raw = definition.get("properties");
		if (raw == null) {
			return List.of();
		}
		if (!(raw instanceof List)) {
			throw new MetaSqlException("entity definition field 'properties' must be a JSON array");
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (Object item : (List<Object>) raw) {
			if (!(item instanceof Map)) {
				throw new MetaSqlException("entity definition property must be a JSON object");
			}
			result.add((Map<String, Object>) item);
		}
		return result;
	}

	// ------------------------------------------------------------------
	// 读写
	// ------------------------------------------------------------------

	/** 生成一份定义模板；已存在就不动。 */
	private boolean generate(Path file, MetaTable fromDatabase) {
		if (!this.generateFiles) {
			return false;
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, toJson(fromDatabase), StandardCharsets.UTF_8);
			return true;
		}
		catch (IOException ex) {
			LOG.log(Level.WARNING, "failed to write entity definition ''{0}''; using database metadata only",
					new Object[] { file, ex });
			return false;
		}
	}

	private Map<String, Object> read(Path file) {
		String key = normalize(file.getFileName().toString()) + '@' + stamp(file);
		return this.definitions.get(key, ignored -> parse(file));
	}

	private static Map<String, Object> parse(Path file) {
		String content;
		try {
			content = Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to read entity definition '" + file + "'", ex);
		}
		Object parsed = Configuration.defaultConfiguration().jsonProvider().parse(content);
		if (!(parsed instanceof Map)) {
			throw new MetaSqlException("entity definition '" + file + "' must be a JSON object");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> definition = (Map<String, Object>) parsed;
		return definition;
	}

	private static String stamp(Path file) {
		try {
			var attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
			return attrs.lastModifiedTime().toMillis() + ":" + attrs.size();
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to stat entity definition '" + file + "'", ex);
		}
	}

	/** 人类可读的定义模板：字段顺序与列顺序都按库里的来。 */
	static String toJson(MetaTable table) {
		StringBuilder out = new StringBuilder(512);
		out.append("{\n");
		out.append("  \"name\" : ").append(string(table.name())).append(",\n");
		out.append("  \"namespace\" : ").append(table.schema() == null ? "null" : string(table.schema())).append(",\n");
		out.append("  \"metaType\" : ").append(string(MetaType.META_TYPE_ENTITY.getCode())).append(",\n");
		out.append("  \"logicDeleteColumn\" : ")
				.append(table.logicDeleteColumn() == null ? "null" : string(table.logicDeleteColumn())).append(",\n");
		out.append("  \"comment\" : \"按表结构生成一次；只写你想改的键，其余以数据库为准\",\n");
		out.append("  \"properties\" : [\n");
		List<MetaProperty> columns = table.columns();
		for (int i = 0; i < columns.size(); i++) {
			MetaProperty column = columns.get(i);
			out.append("    { \"name\" : ").append(string(column.getName()))
					.append(", \"type\" : ").append(string(column.getType()))
					.append(", \"jdbcType\" : ").append(column.getJdbcType())
					.append(", \"sqlTypeName\" : ").append(string(column.getSqlTypeName()))
					.append(", \"nullable\" : ").append(column.isNullable())
					.append(", \"primaryKey\" : ").append(column.isPrimaryKey())
					.append(", \"autoIncrement\" : ").append(column.isAutoIncrement())
					.append(", \"defaultValue\" : ").append(string(column.getDefaultValue()))
					.append(" }").append(i < columns.size() - 1 ? "," : "").append('\n');
		}
		out.append("  ]\n");
		return out.append("}\n").toString();
	}

	private static String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static int number(Object value, int fallback) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		String text = text(value);
		if (text == null || text.isBlank()) {
			return fallback;
		}
		try {
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException ex) {
			throw new MetaSqlException("entity definition field 'jdbcType' is not a number: " + text, ex);
		}
	}

	/** JSON 字符串字面量：控制字符与引号一律转义。 */
	private static String string(String value) {
		if (value == null) {
			return "null";
		}
		StringBuilder out = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : c);
			}
		}
		return out.append('"').toString();
	}

	private static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
