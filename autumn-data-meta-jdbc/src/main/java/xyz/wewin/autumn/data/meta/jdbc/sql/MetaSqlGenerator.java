package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.util.ArrayList;
import java.util.List;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;

/**
 * 每表一个 XML 文件里「系统自动生成区」的语句生成器。
 *
 * <p>一张表固定生成七条默认语句：{@code findList} / {@code findPage} / {@code findOne} /
 * {@code findById} / {@code save} / {@code update} / {@code delete}。语法就是
 * {@code autumn-data-common} 那套 MyBatis 风格 XML，不另起炉灶。</p>
 *
 * <p>七条语句都会<b>写进那份 XML</p>，{@code findOne} 之类的 SQL 在文件里看得见、也改得动。
 * 三条 select 只差执行方式，条件抽成 {@code <sql id="__autumn_where">} 片段共用，
 * 列清单抽成 {@code <sql id="__autumn_columns">}，手改这两处即可，不必在三条语句里各改一遍。</p>
 *
 * <p>文件里用两行注释标记把这一块圈起来：</p>
 * <pre>{@code
 * <!-- ===== AUTUMN-DATA GENERATED START fingerprint=1-xxxx ===== -->
 *   ... 七条默认语句 ...
 * <!-- ===== AUTUMN-DATA GENERATED END ===== -->
 *
 *   <!-- 自定义语句写在这里，框架更新文件时原样保留 -->
 * }</pre>
 *
 * <p>标记头里带结构指纹（{@link MetaTable#fingerprint()} + 生成器版本）。表结构变化时框架
 * <b>只替换标记之间的内容</b>，标记之前的注释、标记之后的自定义语句、缩进与空行全部逐字保留
 * —— 见 {@link #merge(String, MetaTable)}。没有标记的文件第一次被访问时由 {@link #inject} 插入
 * 生成块：默认语句因此也出现在文件里，使用方能看到、也能改；想长期改成自己的版本，就把同名语句
 * 写进标记之后的自定义区，后出现者优先。</p>
 *
 * <p>写哪几列由实体的变更集决定：{@code save} / {@code update} 用 {@code changed('col')}
 * 这个框架内置谓词（见 {@link SqlTemplate}），因此「显式置 NULL」和「这一列没动」是两件事，
 * 与纯 {@code col != null} 的 MyBatis 惯用写法相比不丢语义。</p>
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

	/** 列清单片段 id，默认语句与自定义语句都能 {@code <include>} 复用。 */
	static final String COLUMNS_FRAGMENT = "__autumn_columns";

	/** 查询条件片段 id：findList / findPage / findOne 共用，改条件只改一处。 */
	static final String WHERE_FRAGMENT = "__autumn_where";

	/** 生成器版本：改了生成逻辑就 +1，让所有表在下次访问时刷新自动区。 */
	private static final String VERSION = "3";

	private static final String START_PREFIX = "<!-- ===== AUTUMN-DATA GENERATED START";
	private static final String END_MARKER = "<!-- ===== AUTUMN-DATA GENERATED END ===== -->";

	private final Dialect dialect;

	public MetaSqlGenerator(Dialect dialect) {
		if (dialect == null) {
			throw new IllegalArgumentException("dialect must not be null");
		}
		this.dialect = dialect;
	}

	/** 结构指纹：表结构 + 生成器版本，写进标记头用于判断是否要重写自动区。 */
	public String fingerprint(MetaTable table) {
		return VERSION + "-" + table.fingerprint();
	}

	/** 完整的自动生成块（含 START / END 标记）。 */
	public String generatedBlock(MetaTable table) {
		StringBuilder out = new StringBuilder(4096);
		out.append(START_PREFIX).append(" fingerprint=").append(fingerprint(table)).append(" ===== -->\n");
		out.append("<!-- 本块由框架按表结构生成，表结构变化时只重写本块；手工改动会被覆盖。 -->\n");
		out.append("<!-- 想改查询条件只改 __autumn_where，想改列清单只改 __autumn_columns；整条语句不合适就复制到下方自定义区改。 -->\n");
		out.append(blockBody(table));
		out.append(END_MARKER).append('\n');
		return out.toString();
	}

	/** 文件不存在时生成的完整 XML 文档。 */
	public String fullDocument(MetaTable table, String namespace) {
		StringBuilder out = new StringBuilder(5120);
		out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		out.append("<!-- 一张表一个文件：目录 = 数据源 / 命名空间，文件名 = 表名。 -->\n");
		out.append("<!-- 语法与 MyBatis 一致：#{} 绑定、${} 标识符、<if>/<where>/<trim>/<foreach>/<choose>、<sql>/<include>。 -->\n");
		out.append("<!-- 在 GENERATED END 之后写自定义语句，框架更新文件时原样保留。 -->\n");
		out.append("<mapper namespace=\"").append(escape(namespace)).append("\">\n\n");
		out.append(generatedBlock(table));
		out.append("\n    <!-- ===== 自定义语句区：写在下面，框架不会改动 ===== -->\n\n");
		out.append("</mapper>\n");
		return out.toString();
	}

	/** 只含七条默认语句的独立文档，供内存兜底解析（手写文件 / 只读 classpath 缺默认语句时用）。 */
	public String statementsDocument(MetaTable table) {
		return "<mapper namespace=\"meta\">\n" + blockBody(table) + "\n</mapper>\n";
	}

	/**
	 * 把已有文件里的生成块替换为最新内容。
	 *
	 * @return 改写后的全文；{@code null} 表示不需要改动（文件没有标记，或指纹已是最新）
	 */
	public String merge(String existing, MetaTable table) {
		int start = existing.indexOf(START_PREFIX);
		int end = existing.indexOf(END_MARKER);
		if (start < 0 || end < 0 || end <= start) {
			return null;
		}
		String header = existing.substring(start, lineEnd(existing, start));
		if (header.contains("fingerprint=" + fingerprint(table))) {
			return null;
		}
		int after = lineEnd(existing, end);
		// END 标记后没有换行（文件在这里结束）时，lineEnd 返回 length，不能再 +1
		int tail = after >= existing.length() ? existing.length() : after + 1;
		return existing.substring(0, start) + generatedBlock(table) + existing.substring(tail);
	}

	public boolean hasGeneratedBlock(String text) {
		return text.contains(START_PREFIX) && text.contains(END_MARKER);
	}

	/**
	 * 把自动生成块插进一份<b>还没有标记</b>的现有文件。
	 *
	 * <p>这是本版相对上一版的关键变化：手写文件不再只由内存补默认语句，而是把默认语句
	 * <b>落进文件</b>——{@code findOne/findById/findList/findPage/save/update/delete} 都能在
	 * XML 里看到、也能被使用方直接改成自己要的样子。插入点在 {@code <mapper ...>} 开标签之后，
	 * 因此文件后半段的自定义语句仍在生成块之后：同名 id 以自定义语句为准（后出现者覆盖）。</p>
	 *
	 * @param namespace 文件没有 {@code <mapper>} 壳、需要补壳时写进 {@code namespace}
	 * @return 改写后的全文；{@code null} 表示无需改动（已有生成块，交给 {@link #merge}）
	 */
	public String inject(String existing, MetaTable table, String namespace) {
		if (existing == null || existing.isBlank() || hasGeneratedBlock(existing)) {
			return null;
		}
		int mapperAt = existing.indexOf("<mapper");
		if (mapperAt < 0) {
			// 没有 <mapper> 壳：补一个壳，并把原内容整体放在生成块之后（自定义语句仍优先）
			return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mapper namespace=\"" + escape(namespace)
					+ "\">\n\n" + generatedBlock(table) + "\n" + stripDeclaration(existing).trim() + "\n</mapper>\n";
		}
		int openTagEnd = existing.indexOf('>', mapperAt);
		if (openTagEnd < 0) {
			return null;
		}
		String head = existing.substring(0, openTagEnd + 1);
		String tail = existing.substring(openTagEnd + 1);
		return head + "\n\n" + generatedBlock(table) + tail;
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
	 * 单独抽成片段，手改条件只动这一处，findList / findPage / findOne 跟着变。
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

	/** 所在行末尾 {@code \n} 的下标（不含）；没有换行则返回文本长度。 */
	private static int lineEnd(String text, int from) {
		int at = text.indexOf('\n', from);
		return at < 0 ? text.length() : at;
	}

	/** 去掉 {@code <?xml ...?>} 声明：补壳时由生成器自己写一份。 */
	private static String stripDeclaration(String content) {
		int at = content.indexOf("<?xml");
		if (at < 0) {
			return content;
		}
		int end = content.indexOf("?>", at);
		return end < 0 ? content : content.substring(0, at) + content.substring(end + 2);
	}
}
