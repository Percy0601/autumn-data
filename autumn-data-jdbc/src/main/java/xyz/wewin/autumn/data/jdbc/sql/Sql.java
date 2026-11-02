package xyz.wewin.autumn.data.jdbc.sql;

/**
 * 编译期把 SQL 全部内联进生成类（生成类里的 {@code private static final String} 常量）之后，
 * 运行时只剩两处动态拼接：把 {@code IN (:ids)} 按运行期参数个数展开成对应数量的 {@code ?} 占位符，
 * 以及设置了字段投影时把 {@code {cols}} 换成精简列清单（未设投影的全列路径不走这一步）。
 *
 * <p>其余所有 SQL 都是生成类里的编译期常量，零解析、零类路径资源加载、零额外依赖。</p>
 */
public final class Sql {

	/** IN 列表在 SQL 中的占位标记，例如 {@code WHERE id IN (:ids)}。 */
	private static final String IN_MARKER = ":ids";

	/**
	 * 字段投影在 SELECT 模板里的列占位标记，例如 {@code SELECT {cols} FROM basic_user}。
	 * 模板由编译期写进生成类（与全列常量同形，只把列清单换成该标记），与
	 * {@code EntityMetadata.PROJECTION_MARKER} 是同一个字面量。
	 */
	public static final String COLUMNS_MARKER = "{cols}";

	private Sql() {
	}

	/**
	 * 把 SELECT 模板里的 {@value #COLUMNS_MARKER} 换成运行期挑出的列清单。
	 * 仅在设置了字段投影时调用 —— 没有投影时生成代码直接用编译期的全列常量，不经过这里。
	 */
	public static String withColumns(String template, String columns) {
		if (columns == null || columns.isEmpty()) {
			throw new IllegalArgumentException("projection needs at least one column for " + template);
		}
		return template.replace(COLUMNS_MARKER, columns);
	}

	/** 把 baseSql 里的 {@value #IN_MARKER} 展开为 size 个 {@code ?} 逗号分隔的 IN 子句。 */
	public static String inClause(String baseSql, int size) {
		int at = baseSql.indexOf(IN_MARKER);
		if (at < 0) {
			throw new IllegalArgumentException("SQL has no '" + IN_MARKER + "' placeholder: " + baseSql);
		}
		if (size <= 0) {
			throw new IllegalArgumentException("IN clause needs at least one value, got " + size);
		}
		StringBuilder placeholders = new StringBuilder();
		for (int i = 0; i < size; i++) {
			if (i > 0) {
				placeholders.append(", ");
			}
			placeholders.append('?');
		}
		return baseSql.substring(0, at) + placeholders + baseSql.substring(at + IN_MARKER.length());
	}
}
