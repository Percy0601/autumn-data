package xyz.wewin.autumn.data.jdbc.sql;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 编译期生成的 SQL 语句集：从类路径上的 {@code .properties} 资源读取（由
 * autumn-data-jdbc-compiler 在 APT 阶段写入 {@code target/classes}）。
 *
 * <p>只用 JDK 的 {@link Properties} 解析，不引入任何额外依赖；加载一次后按不可变
 * {@link Map} 持有，运行期零反射、零解析开销。</p>
 */
public final class SqlStatements {

	/** IN 列表在 SQL 中的占位标记，例如 {@code WHERE id IN (:ids)}。 */
	private static final String IN_MARKER = ":ids";

	private final String resource;

	private final Map<String, String> statements;

	private SqlStatements(String resource, Map<String, String> statements) {
		this.resource = resource;
		this.statements = Collections.unmodifiableMap(statements);
	}

	/** 从类路径加载资源；找不到时给出可定位的报错（通常是未重新编译或资源被清理）。 */
	public static SqlStatements load(String resource) {
		ClassLoader loader = SqlStatements.class.getClassLoader();
		ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
		InputStream stream = (contextLoader != null) ? contextLoader.getResourceAsStream(resource) : null;
		if (stream == null) {
			stream = loader.getResourceAsStream(resource);
		}
		if (stream == null) {
			throw new IllegalStateException("SQL resource '" + resource
					+ "' not found on classpath. Rebuild the module so autumn-data-jdbc-compiler can write it.");
		}
		Properties properties = new Properties();
		try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to read SQL resource '" + resource + "': " + ex, ex);
		}
		Map<String, String> statements = new LinkedHashMap<>();
		for (String name : properties.stringPropertyNames()) {
			statements.put(name, properties.getProperty(name));
		}
		return new SqlStatements(resource, statements);
	}

	public String get(String key) {
		String sql = this.statements.get(key);
		if (sql == null) {
			throw new IllegalArgumentException(
					"SQL statement '" + key + "' missing from " + this.resource + " (available: "
							+ this.statements.keySet() + ")");
		}
		return sql;
	}

	/** 把 {@value #IN_MARKER} 展开为对应个数的 {@code ?}，得到可直接执行的 IN 子句 SQL。 */
	public String inClause(String key, int size) {
		String sql = get(key);
		int at = sql.indexOf(IN_MARKER);
		if (at < 0) {
			throw new IllegalArgumentException(
					"SQL statement '" + key + "' has no '" + IN_MARKER + "' placeholder: " + sql);
		}
		if (size <= 0) {
			throw new IllegalArgumentException("IN clause for '" + key + "' needs at least one value");
		}
		StringBuilder placeholders = new StringBuilder();
		for (int i = 0; i < size; i++) {
			if (i > 0) {
				placeholders.append(", ");
			}
			placeholders.append('?');
		}
		return sql.substring(0, at) + placeholders + sql.substring(at + IN_MARKER.length());
	}

	public String resource() {
		return this.resource;
	}

	public int size() {
		return this.statements.size();
	}
}
