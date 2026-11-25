package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.util.ArrayList;
import java.util.List;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;

/**
 * 框架自动生成区的语句生成器：一张表一份独立 XML 文档。
 *
 * <p>每张表固定生成七条默认语句：{@code findList} / {@code findPage} / {@code findOne} /
 * {@code findById} / {@code save} / {@code update} / {@code delete}。语法就是
 * {@code autumn-data-common} 那套 MyBatis 风格 XML，不另起炉灶。</p>
 *
 * <p>这份文档落在 {@code auto/xml/{表}.xml}，整份归框架所有：表结构变化时整份重写，
 * 使用方不要改它。自定义语句写在 {@code spec/xml/{表}.xml}，框架从不改写，按 id 覆盖同名默认语句
 * —— 两份文件由 {@link MetaSqlFileStore} 合并，职责边界干净。</p>
 *
 * <p>三条 select 只差执行方式，条件抽成 {@code <sql id="__autumn_where">} 片段共用，
 * 列清单抽成 {@code <sql id="__autumn_columns">}。</p>
 *
 * <p>写哪几列由实体的变更集决定：{@code save} / {@code update} 用 {@code changed('col')}
 * 这个框架内置谓词（见 {@link SqlTemplate}），因此「显式置 NULL」和「这一列没动」是两件事。</p>
 */
public final class MetaSqlGenerator {

	/** 核心 CRUD 约定的七个默认语句 id。 */
	public static final String FIND_LIST = "findList";
	public static final String FIND_PAGE = "findPage";
	public static final String FIND_ONE = "findOne";
	public static final String FIND_BY_ID = "findById";
	public static final String SAVE = "save";
	public static final String UPDATE = "update";
	public static final String DELETE = "delete";

	public static final List<String> DEFAULT_IDS = List.of(FIND_LIST, FIND_PAGE, FIND_ONE, FIND_BY_ID, SAVE,
			UPDATE, DELETE);

	/** 列清单片段 id，默认语句都能 {@code <include>} 复用。 */
	static final String COLUMNS_FRAGMENT = "__autumn_columns";

	/** 查询条件片段 id：findList / findPage / findOne 共用。 */
	static final String WHERE_FRAGMENT = "__autumn_where";

	/** 生成器版本：改了生成逻辑就 +1，让所有表在下次访问时重写 auto 文件。 */
	private static final String VERSION = "4";

	private final Dialect dialect;

	public MetaSqlGenerator(Dialect dialect) {
		if (dialect == null) {
			throw new IllegalArgumentException("dialect must not be null");
		}
		this.dialect = dialect;
	}

	/** 结构指纹：表结构 + 生成器版本；auto 文件的注释头带它，用于判断要不要重写。 */
	public String fingerprint(MetaTable table) {
		return VERSION + "-" + table.fingerprint();
	}

	/** 生成完整的 auto XML 文档（整份归框架，表结构变化整份重写）。 */
	public String fullDocument(MetaTable table, String namespace) {
		StringBuilder out = new StringBuilder(5120);
		out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		out.append("<!-- AUTUMN-DATA GENERATED fingerprint=").append(fingerprint(table)).append(" -->\n");
		out.append("<!-- 本文件由框架按表结构自动生成，表结构变化会整份重写；不要改这里。 -->\n");
		out.append("<!-- 自定义语句写在 spec/xml/{表名}.xml，框架从不改写，同名 id 覆盖本文件的默认语句。 -->\n");
		out.append("<!-- 语法与 MyBatis 一致：#{} 绑定、${} 标识符、<if>/<where>/<trim>/<foreach>/<choose>、<sql>/<include>。 -->\n");
		out.append("<mapper namespace=\"").append(escape(namespace)).append("\">\n\n");
		out.append(blockBody(table));
		out.append("\n</mapper>\n");
		return out.toString();
	}

	/** 只含七条默认语句的文档，供内存兜底解析（auto 文件落不了盘时用）。 */
	public String statementsDocument(MetaTable table) {
		return "<mapper namespace=\"meta\">\n" + blockBody(table) + "\n</mapper>\n";
	}

	/**
	 * 从已有 auto 文件里读出指纹；没有指纹头返回 {@code null}（视为过期，重写）。
	 */
	public static String fingerprintOf(String content) {
		if (content == null) {
			return null;
		}
		String marker = "AUTUMN-DATA GENERATED fingerprint=";
		int at = content.indexOf(marker);
		if (at < 0) {
			return null;
		}
		int from = at + marker.length();
		int end = content.indexOf(' ', from);
		if (end < 0) {
			end = content.indexOf('-', from + 1);
		}
		int commentEnd = content.indexOf("-->", from);
		if (commentEnd >= 0 && (end < 0 || end > commentEnd)) {
			end = commentEnd;
		}
		return end < 0 ? content.substring(from).trim() : content.substring(from, end).trim();
	}

	// ------------------------------------------------------------------
	// 七条默认语句
	// ------------------------------------------------------------------

	private String blockBody(MetaTable table) {
		StringBuilder out = new StringBuilder(4096);
		out.append("    <sql id=\"").append(COLUMNS_FRAGMENT).append("\">")
				.append(quotedNames(table.columns())).append("</sql>\n\n");
		appendWhereFragment(out, table);
		appendSelect(out, table, FIND_LIST, true);
		appendSelect(out, table, FIND_PAGE, true);
		appendSelect(out, table, FIND_ONE, true);
		appendFindById(out, table);
		appendSave(out, table);
		appendUpdate(out, table);
		appendDelete(out, table);
		return out.toString();
	}

	private void appendSelect(StringBuilder out, MetaTable table, String id, boolean orderByKey) {
		out.append("    <select id=\"").append(id).append("\">\n");
		out.append("        SELECT <include refid=\"").append(COLUMNS_FRAGMENT).append("\"/> FROM ")
				.append(quote(table.name())).append('\n');
		out.append("        <include refid=\"").append(WHERE_FRAGMENT).append("\"/>\n");
		if (orderByKey && !table.primaryKeys().isEmpty()) {
			out.append("        ORDER BY ").append(quotedNames(table.primaryKeys())).append('\n');
		}
		out.append("    </select>\n\n");
	}

	/**
	 * 三条 select 共用的查询条件：逐列等值条件 + 固定的逻辑删除过滤；没展开的条件不产生绑定槽。
	 */
	private void appendWhereFragment(StringBuilder out, MetaTable table) {
		out.append("    <sql id=\"").append(WHERE_FRAGMENT).append("\">\n");
		out.append("        <where>\n");
		MetaProperty logic = logicColumn(table);
		if (logic != null) {
			out.append("            AND ").append(quote(logic.getName())).append(" = 0\n");
		}
		for (MetaProperty column : table.columns()) {
			if (column == logic) {
				continue;
			}
			out.append("            <if test=\"").append(column.getName()).append(" != null\">AND ")
					.append(quote(column.getName())).append(" = #{").append(column.getName()).append("}</if>\n");
		}
		out.append("        </where>\n");
		out.append("    </sql>\n\n");
	}

	private void appendFindById(StringBuilder out, MetaTable table) {
		out.append("    <select id=\"").append(FIND_BY_ID).append("\">\n");
		out.append("        SELECT <include refid=\"").append(COLUMNS_FRAGMENT).append("\"/> FROM ")
				.append(quote(table.name())).append('\n');
		if (table.primaryKeys().isEmpty()) {
			out.append("        <!-- 该表没有主键：客户端会拒绝 findById，这里保留一条占位语句。 -->\n");
			out.append("        WHERE 1 = 1\n");
		}
		else {
			out.append("        WHERE ").append(keyCondition(table));
			MetaProperty logic = logicColumn(table);
			if (logic != null) {
				out.append(" AND ").append(quote(logic.getName())).append(" = 0");
			}
			out.append('\n');
		}
		out.append("    </select>\n\n");
	}

	private void appendSave(StringBuilder out, MetaTable table) {
		List<MetaProperty> writable = new ArrayList<>();
		for (MetaProperty column : table.columns()) {
			if (!column.isAutoIncrement()) {
				writable.add(column);
			}
		}
		out.append("    <insert id=\"").append(SAVE).append("\">\n");
		out.append("        INSERT INTO ").append(quote(table.name())).append('\n');
		appendTrim(out, "(", ")");
		for (MetaProperty column : writable) {
			out.append("            <if test=\"changed('").append(column.getName()).append("')\">")
					.append(quote(column.getName())).append(",</if>\n");
		}
		out.append("        </trim>\n");
		appendTrim(out, "VALUES (", ")");
		for (MetaProperty column : writable) {
			out.append("            <if test=\"changed('").append(column.getName()).append("')\">#{")
					.append(column.getName()).append("},</if>\n");
		}
		out.append("        </trim>\n");
		out.append("    </insert>\n\n");
	}

	private void appendUpdate(StringBuilder out, MetaTable table) {
		out.append("    <update id=\"").append(UPDATE).append("\">\n");
		out.append("        UPDATE ").append(quote(table.name())).append('\n');
		appendTrim(out, "SET ", "");
		for (MetaProperty column : table.columns()) {
			if (column.isPrimaryKey() || column.isAutoIncrement()) {
				continue;
			}
			out.append("            <if test=\"changed('").append(column.getName()).append("')\">")
					.append(quote(column.getName())).append(" = #{").append(column.getName()).append("},</if>\n");
		}
		out.append("        </trim>\n");
		out.append("        WHERE ").append(keyConditionOrFalse(table)).append('\n');
		out.append("    </update>\n\n");
	}

	private void appendDelete(StringBuilder out, MetaTable table) {
		String where = keyConditionOrFalse(table);
		MetaProperty logic = logicColumn(table);
		if (logic != null) {
			out.append("    <update id=\"").append(DELETE).append("\">\n");
			out.append("        UPDATE ").append(quote(table.name())).append(" SET ")
					.append(quote(logic.getName())).append(" = 1\n");
			out.append("        WHERE ").append(where).append(" AND ")
					.append(quote(logic.getName())).append(" = 0\n");
			out.append("    </update>\n\n");
		}
		else {
			out.append("    <delete id=\"").append(DELETE).append("\">\n");
			out.append("        DELETE FROM ").append(quote(table.name())).append(" WHERE ").append(where)
					.append('\n');
			out.append("    </delete>\n\n");
		}
	}

	private static void appendTrim(StringBuilder out, String prefix, String suffix) {
		out.append("        <trim prefix=\"").append(prefix).append("\" suffix=\"").append(suffix)
				.append("\" suffixOverrides=\",\">\n");
	}

	private String keyCondition(MetaTable table) {
		StringBuilder condition = new StringBuilder();
		for (MetaProperty key : table.primaryKeys()) {
			if (!condition.isEmpty()) {
				condition.append(" AND ");
			}
			condition.append(quote(key.getName())).append(" = #{").append(key.getName()).append('}');
		}
		return condition.toString();
	}

	/** 无主键的表不能安全更新/删除，生成一条永不命中的条件兜底。 */
	private String keyConditionOrFalse(MetaTable table) {
		return table.primaryKeys().isEmpty() ? "1 = 0" : keyCondition(table);
	}

	private String quotedNames(List<MetaProperty> columns) {
		StringBuilder names = new StringBuilder();
		for (MetaProperty column : columns) {
			if (!names.isEmpty()) {
				names.append(", ");
			}
			names.append(quote(column.getName()));
		}
		return names.toString();
	}

	private MetaProperty logicColumn(MetaTable table) {
		String name = table.logicDeleteColumn();
		return name == null ? null : table.findColumn(name);
	}

	private String quote(String identifier) {
		return this.dialect.quoteIdentifier(identifier);
	}

	/** 方言引号：手写 SQL 里的 {@code ${table}} 由客户端填这个值。 */
	public String quoteIdentifier(String identifier) {
		return this.dialect.quoteIdentifier(identifier);
	}

	private static String escape(String value) {
		return value == null ? "meta" : value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
	}
}
