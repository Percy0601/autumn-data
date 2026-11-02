package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Association;
import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Column;

/**
 * 按实体元数据生成标准 CRUD 的 SQL（对应 {@code CrudRepository} 的方法清单），
 * 返回 {@code Map<key, sql>}。
 *
 * <p>SQL 不再落盘、不再运行期加载：编译期由 {@link ImplementationSource} 把这些语句内联成
 * 生成类里的 {@code private static final String} 常量，运行期只解析常量、零资源 I/O。</p>
 *
 * <p>方言差异只在运行期介入（分页片段由 {@code Dialect#limitOffset} 追加），
 * 语句本身用裸标识符与 ANSI 形式，MySQL / PostgreSQL / H2 均可执行。</p>
 */
final class CrudSqlGenerator {

	/** IN 列表的占位标记，运行时按参数个数展开成 {@code ?}。 */
	static final String IN_MARKER = ":ids";

	static final String INSERT = "insert";

	static final String UPDATE = "update";

	static final String SELECT_BY_ID = "selectById";

	static final String EXISTS_BY_ID = "existsById";

	static final String SELECT_ALL = "selectAll";

	static final String SELECT_BY_IDS = "selectByIds";

	static final String COUNT = "count";

	static final String PAGE_ALL = "pageAll";

	static final String DELETE_BY_ID = "deleteById";

	static final String DELETE_BY_IDS = "deleteByIds";

	static final String DELETE_ALL = "deleteAll";

	/**
	 * 投影模板语句的键后缀：{@code selectAll} → {@code selectAllProjection}，
	 * 在生成类里就是 {@code SQL_SELECT_ALL_PROJECTION}（与全列常量 {@code SQL_SELECT_ALL} 并存）。
	 */
	static final String PROJECTION = "Projection";

	private CrudSqlGenerator() {
	}

	/**
	 * 登记一条取实体行的语句，并顺带登记它的<em>投影模板</em>：模板与全列语句同形，
	 * 只把列清单换成 {@code {cols}} 标记，运行期设置了字段投影才替换成精简列。
	 *
	 * <p>全列常量仍是默认路径（绝大多数查询不裁剪列，编译期常量直接用、运行期零拼接）；
	 * 实体没有可裁剪的列（全是主键/级联必需列）时不生成模板。</p>
	 */
	private static void putSelect(Map<String, String> statements, EntityMetadata entity, String key,
			Function<String, String> sql) {
		statements.put(key, sql.apply(columnList(entity.columns())));
		if (entity.projectionEligible()) {
			statements.put(key + PROJECTION, sql.apply(EntityMetadata.PROJECTION_MARKER));
		}
	}

	static Map<String, String> generate(EntityMetadata entity) {
		String table = entity.table();
		String idColumn = entity.id().column();
		String insertColumns = columnList(entity.insertColumns());
		String updateSet = entity.updateColumns().stream()
			.map(column -> column.column() + " = ?")
			.collect(Collectors.joining(", "));
		Map<String, String> statements = new LinkedHashMap<>();
		statements.put(INSERT, "INSERT INTO " + table + " (" + insertColumns + ") VALUES ("
				+ marks(entity.insertColumns().size()) + ")");
		statements.put(UPDATE, "UPDATE " + table + " SET " + updateSet + " WHERE " + idColumn + " = ?");
		putSelect(statements, entity, SELECT_BY_ID,
				cols -> "SELECT " + cols + " FROM " + table + " WHERE " + idColumn + " = ?");
		statements.put(EXISTS_BY_ID, "SELECT COUNT(*) FROM " + table + " WHERE " + idColumn + " = ?");
		putSelect(statements, entity, SELECT_ALL, cols -> "SELECT " + cols + " FROM " + table);
		putSelect(statements, entity, SELECT_BY_IDS,
				cols -> "SELECT " + cols + " FROM " + table + " WHERE " + idColumn + " IN (" + IN_MARKER + ")");
		statements.put(COUNT, "SELECT COUNT(*) FROM " + table);
		// 分页必须有确定顺序，否则跨页会重复/漏行；未接派生查询前固定按主键排
		putSelect(statements, entity, PAGE_ALL,
				cols -> "SELECT " + cols + " FROM " + table + " ORDER BY " + idColumn);
		statements.put(DELETE_BY_ID, "DELETE FROM " + table + " WHERE " + idColumn + " = ?");
		statements.put(DELETE_BY_IDS, "DELETE FROM " + table + " WHERE " + idColumn + " IN (" + IN_MARKER + ")");
		statements.put(DELETE_ALL, "DELETE FROM " + table);
		for (Association association : entity.associations()) {
			if (!association.eager()) {
				continue;
			}
			statements.put(association.statementKey(),
					association.toOne() ? childByIdStatement(association) : childStatement(association));
		}
		return statements;
	}

	/**
	 * 一对多的第二条语句：一次把本页/本次查到的父主键对应的子行全部取回，
	 * 按外键分组在生成的实现类里做，因此不需要 {@code JOIN} 也不会产生 N+1。
	 */
	private static String childStatement(Association association) {
		EntityMetadata child = association.target();
		String foreignKey = association.foreignKeyColumn();
		String columns = columnList(child.columns());
		// 子实体自己把外键映射成了列时不必再选一遍，避免重复列名
		boolean mapsForeignKey = child.columns().stream().anyMatch(column -> column.column().equals(foreignKey));
		if (!mapsForeignKey) {
			columns = columns + ", " + foreignKey;
		}
		return "SELECT " + columns + " FROM " + child.table() + " WHERE " + foreignKey + " IN (" + IN_MARKER
				+ ") ORDER BY " + association.targetIdColumn();
	}

	/**
	 * 多对一（单值）的第二条语句：用父表上的外键值去 {@code IN} 子表主键，一次取回全部相关子实体；
	 * 分组（按子表主键挂回父表外键）在生成的实现类里做。
	 */
	private static String childByIdStatement(Association association) {
		EntityMetadata child = association.target();
		String columns = columnList(child.columns());
		return "SELECT " + columns + " FROM " + child.table() + " WHERE " + child.id().column() + " IN (" + IN_MARKER
				+ ") ORDER BY " + association.targetIdColumn();
	}

	/** 列名列表，逗号分隔。 */
	private static String columnList(List<Column> columns) {
		return columns.stream().map(Column::column).collect(Collectors.joining(", "));
	}

	private static String marks(int count) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < count; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append('?');
		}
		return sb.toString();
	}
}
