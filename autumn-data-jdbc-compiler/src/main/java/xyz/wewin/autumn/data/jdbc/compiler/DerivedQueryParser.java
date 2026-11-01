package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Binding;
import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Kind;
import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Parameter;
import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Association;
import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Column;

/**
 * 派生查询解析器：把 {@code findByNameAndAgeGreaterThanOrderByNameAsc} 这类方法名解析成
 * {@link DerivedQuery}（编译期 SQL + 运行期绑定顺序 + 返回类型契约）。
 * 切分顺序与 Spring 的 {@code PartTree} 一致，依据见 docs/spring-data-derived-query.md 第 1~6 节。
 *
 * <p>纯函数：输入方法名、返回类型、形参表和实体元数据，产出结果或抛 {@link DerivedQueryException}，
 * 不碰编译器 API，因此可以脱离 APT 直接单测。</p>
 *
 * <p><b>首期覆盖</b>：主语 {@code find/read/get/query/search/stream}、{@code count}、{@code exists}；
 * 关键词见 {@link #KEYWORDS}；{@code And}/{@code Or} 组合与 {@code OrderBy}；属性既可以是实体自己的字段，
 * 也可以是一层关联路径（{@code findByOrdersOrderNo} 这类一对多条件，见 {@link #resolveProperty}）。
 * 表外的构造（{@code Distinct}、{@code First/Top} 行数限制、{@code deleteBy}、两层以上关联、
 * {@code StartingWith} 这类要方言拼字符串的关键词、裸实体返回值）一律显式报错，
 * 由 {@link AutoRepositoryProcessor} 定位到方法声明 —— 编译期说清楚，不静默生成半成品。</p>
 */
final class DerivedQueryParser {

	/** 主语动词（规范第 2 节）；{@code delete/remove} 首期不生成，命中后单独给原因。 */
	private static final List<String> VERBS = List.of("find", "read", "get", "query", "search", "stream", "count",
			"exists", "delete", "remove");

	private static final List<String> ORDER_DIRECTIONS = List.of("Asc", "Desc");

	/** IN 列表的运行期展开标记，与 CRUD 语句用同一个（{@link CrudSqlGenerator#IN_MARKER}）。 */
	private static final String IN_MARKER = CrudSqlGenerator.IN_MARKER;

	/**
	 * 关键词表：方法名后缀 →（消耗参数个数，SQL 片段模板）。按后缀长度倒序匹配，
	 * 保证 {@code GreaterThanEqual} 不被 {@code GreaterThan} 抢走、{@code IsNotNull} 不被 {@code NotNull} 截断。
	 * {@code fragment} 为 null 表示认得这个词但首期不生成，报错时给出原因。
	 */
	private static final List<Keyword> KEYWORDS = List
		.of(new Keyword("GreaterThanEqual", 1, "%s >= ?"), new Keyword("LessThanEqual", 1, "%s <= ?"),
			new Keyword("NotBetween", 2, "%s NOT BETWEEN ? AND ?"), new Keyword("StartingWith", 1, null),
			new Keyword("EndingWith", 1, null), new Keyword("IsNotNull", 0, "%s IS NOT NULL"),
			new Keyword("NotGreaterThan", 1, "%s <= ?"), new Keyword("NotLessThan", 1, "%s >= ?"),
			new Keyword("GreaterThan", 1, "%s > ?"), new Keyword("LessThan", 1, "%s < ?"),
			new Keyword("NotLike", 1, "%s NOT LIKE ?"), new Keyword("NotEqual", 1, "%s <> ?"),
			new Keyword("Between", 2, "%s BETWEEN ? AND ?"), new Keyword("NotNull", 0, "%s IS NOT NULL"),
			new Keyword("NotIn", 1, "%s NOT IN (" + IN_MARKER + ")"), new Keyword("Equals", 1, "%s = ?"),
			new Keyword("IsNull", 0, "%s IS NULL"), new Keyword("After", 1, "%s > ?"),
			new Keyword("Before", 1, "%s < ?"), new Keyword("Equal", 1, "%s = ?"), new Keyword("Like", 1, "%s LIKE ?"),
			new Keyword("True", 0, "%s = TRUE"), new Keyword("False", 0, "%s = FALSE"),
			new Keyword("Null", 0, "%s IS NULL"), new Keyword("In", 1, "%s IN (" + IN_MARKER + ")"))
		.stream()
		.sorted(Comparator.comparingInt((Keyword keyword) -> keyword.suffix().length()).reversed())
		.toList();

	private DerivedQueryParser() {
	}

	/**
	 * 解析一个派生查询方法。
	 * @param methodName 接口上的方法名
	 * @param returnTypeSource 返回类型的源码形态，如 {@code java.util.List<demo.User>}、{@code long}
	 * @param parameters 形参表，顺序与声明一致
	 * @param meta 实体元数据，属性名到列名的映射依据
	 * @throws DerivedQueryException 方法名不在已实现子集内，或参数个数/类型、返回类型与谓词不匹配
	 */
	static DerivedQuery parse(String methodName, String returnTypeSource, List<Parameter> parameters,
			EntityMetadata meta) {
		Subject subject = readSubject(methodName);
		Ordering ordering = readOrdering(subject.predicateText(), meta, methodName);
		List<List<Atom>> branches = ordering.predicateText().isEmpty() ? List.of()
				: splitBranches(ordering.predicateText(), meta, methodName);
		List<Atom> atoms = branches.stream().flatMap(List::stream).toList();
		Kind kind = kindOf(subject, returnTypeSource, meta, methodName);
		List<Binding> bindings = bind(atoms, parameters, methodName);
		// 计数/存在性判断没有行序概念，带上 ORDER BY 在 PostgreSQL 里还会因非聚合列报错
		String orderSql = (kind == Kind.COUNT || kind == Kind.EXISTS || ordering.sql().isEmpty()) ? ""
				: " ORDER BY " + ordering.sql();
		String sql = "SELECT " + (kind == Kind.COUNT || kind == Kind.EXISTS ? "COUNT(*)" : columns(meta)) + " FROM "
				+ meta.table() + whereClause(branches) + orderSql;
		return new DerivedQuery(methodName, kind, returnTypeSource, parameters, bindings, sql);
	}

	private static String columns(EntityMetadata meta) {
		return meta.columns().stream().map(Column::column).collect(Collectors.joining(", "));
	}

	/**
	 * 切主语：动词 + 可选 {@code All} + 必需的 {@code By}。
	 * {@code Distinct} 和 {@code First/Top} 在这里挡下，因为它们要改语句形状（{@code DISTINCT} / 行数限制）。
	 */
	private static Subject readSubject(String methodName) {
		String verb = null;
		for (String candidate : VERBS) {
			if (methodName.startsWith(candidate) && startsWord(methodName, candidate.length())) {
				verb = candidate;
				break;
			}
		}
		if (verb == null) {
			throw fail(methodName,
					"unrecognised subject; derived queries start with find/read/get/query/search/stream/count/exists");
		}
		if (verb.equals("delete") || verb.equals("remove")) {
			throw fail(methodName, "'" + verb + "By' is not generated yet - use deleteById/deleteAllById for now");
		}
		String rest = methodName.substring(verb.length());
		if (rest.startsWith("Distinct")) {
			throw fail(methodName, "Distinct is not supported yet");
		}
		if (rest.startsWith("First") || rest.startsWith("Top")) {
			throw fail(methodName,
					"findFirst/findTop impose a row limit through the dialect, which is not generated yet");
		}
		if (rest.startsWith("All")) {
			rest = rest.substring("All".length());
		}
		String predicateText;
		if (rest.startsWith("By")) {
			predicateText = rest.substring("By".length());
			if (predicateText.isEmpty()) {
				throw fail(methodName, "no predicate after 'By'");
			}
		}
		else if (rest.startsWith("OrderBy")) {
			// 只有排序没有条件的方法名（findAllOrderByCreatedAtDesc）：Spring 同样接受，谓词为空即不带 WHERE
			predicateText = rest;
		}
		else {
			throw fail(methodName, "expected the 'By' separator before the predicate (found '" + beforeBy(rest)
					+ "'), see docs/spring-data-derived-query.md section 1");
		}
		Kind kind = switch (verb) {
			case "count" -> Kind.COUNT;
			case "exists" -> Kind.EXISTS;
			default -> Kind.LIST;
		};
		return new Subject(verb, kind, predicateText);
	}

	private static String beforeBy(String rest) {
		int by = rest.indexOf("By");
		return by < 0 ? rest : rest.substring(0, by);
	}

	/** 动词后必须紧跟大写或到结尾，否则 {@code fancier…} 这类名字会被误切成动词 + 残段。 */
	private static boolean startsWord(String text, int at) {
		return at == text.length() || Character.isUpperCase(text.charAt(at));
	}

	/** 剥出尾部的 {@code OrderBy}，属性与方向当场校验，剩下的才是谓词。 */
	private static Ordering readOrdering(String text, EntityMetadata meta, String methodName) {
		int at = indexOfToken(text, "OrderBy");
		if (at < 0) {
			return new Ordering(text, "");
		}
		String predicate = text.substring(0, at);
		String tail = text.substring(at + "OrderBy".length());
		if (tail.isEmpty()) {
			throw fail(methodName, "'OrderBy' with no term after it - each term needs a property and Asc or Desc");
		}
		return new Ordering(predicate, renderOrderBy(tail, meta, methodName));
	}

	/** {@code AgeAscNameDesc} → {@code age ASC, name DESC}。 */
	private static String renderOrderBy(String text, EntityMetadata meta, String methodName) {
		List<String> terms = new ArrayList<>();
		int cursor = 0;
		while (cursor < text.length()) {
			int consumed = -1;
			String direction = null;
			for (int end = cursor + 1; end <= text.length() && consumed < 0; end++) {
				for (String candidate : ORDER_DIRECTIONS) {
					Column column = text.startsWith(candidate, end) ? columnFor(text.substring(cursor, end), meta)
							: null;
					if (column != null) {
						consumed = end + candidate.length();
						direction = candidate;
						break;
					}
				}
			}
			if (consumed < 0) {
				throw fail(methodName, "cannot parse ORDER BY segment '" + text.substring(cursor)
						+ "' - each term needs a known property followed by Asc or Desc");
			}
			Column column = columnFor(text.substring(cursor, consumed - direction.length()), meta);
			terms.add(column.column() + " " + direction.toUpperCase(Locale.ROOT));
			cursor = consumed;
		}
		return String.join(", ", terms);
	}

	/** 按 {@code Or} 切分支，分支内按 {@code And} 切原子（规范第 4 节：{@code AAndBOrC} 即 {@code (A AND B) OR C}）。 */
	private static List<List<Atom>> splitBranches(String text, EntityMetadata meta, String methodName) {
		List<List<Atom>> branches = new ArrayList<>();
		for (String branch : splitOnToken(text, "Or")) {
			List<Atom> atoms = new ArrayList<>();
			for (String atom : splitOnToken(branch, "And")) {
				atoms.add(atomOf(atom, meta, methodName));
			}
			branches.add(atoms);
		}
		return branches;
	}

	/** 在 token 后紧跟大写的地方切开，所以 {@code Orange} 不会被当成 {@code Or + ange}。 */
	private static List<String> splitOnToken(String text, String token) {
		List<String> parts = new ArrayList<>();
		int cursor = 0;
		int at;
		while ((at = indexOfToken(text.substring(cursor), token)) >= 0) {
			parts.add(text.substring(cursor, cursor + at));
			cursor += at + token.length();
		}
		parts.add(text.substring(cursor));
		return parts;
	}

	private static int indexOfToken(String text, String token) {
		for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + 1)) {
			if (startsWord(text, at + token.length())) {
				return at;
			}
		}
		return -1;
	}

	/** 一个原子谓词：先拿整个片段当属性名试（等值），再按最长关键词后缀切。 */
	private static Atom atomOf(String atom, EntityMetadata meta, String methodName) {
		Resolved exact = resolveProperty(atom, meta);
		if (exact != null) {
			return new Atom(exact.wrap("%s = ?", meta), 1, false);
		}
		String unresolvableProperty = null;
		for (Keyword keyword : KEYWORDS) {
			if (atom.length() <= keyword.suffix().length() || !atom.endsWith(keyword.suffix())) {
				continue;
			}
			String property = atom.substring(0, atom.length() - keyword.suffix().length());
			Resolved resolved = resolveProperty(property, meta);
			if (resolved == null) {
				if (unresolvableProperty == null) {
					unresolvableProperty = property;
				}
				continue;
			}
			if (keyword.fragment() == null) {
				throw fail(methodName, "'" + keyword.suffix() + "' is not implemented yet - it needs dialect-aware"
						+ " string concatenation; use Like and pass the pattern");
			}
			return new Atom(resolved.wrap(keyword.fragment(), meta), keyword.arity(),
					keyword.suffix().endsWith("In"));
		}
		throw unknownProperty(unresolvableProperty != null ? unresolvableProperty : atom, meta, methodName);
	}

	private static String fieldNames(EntityMetadata meta) {
		return meta.columns().stream().map(Column::field).collect(Collectors.joining(", "));
	}

	/**
	 * 属性名 → SQL 列落点：先按本实体字段匹配，再尝试一层关联路径（{@code OrdersOrderNo} 即
	 * 关联 {@code orders} 的子实体字段 {@code orderNo}）。解析不出返回 null，由调用方决定报错文案。
	 *
	 * <p>关联条件写成相关子查询而不是 JOIN：{@code EXISTS} 天然按存在性过滤，父行不会因一对多重复，
	 * 所以父表的列清单、{@code ROW_MAPPER} 和已有的关联装配都不用改，也不依赖方言。</p>
	 */
	private static Resolved resolveProperty(String property, EntityMetadata meta) {
		Column direct = columnFor(property, meta);
		if (direct != null) {
			return new Resolved(direct.column(), null);
		}
		for (Association association : meta.associations()) {
			String childProperty = childPropertyOf(property, association);
			Column child = childProperty == null ? null : columnFor(childProperty, association.target());
			if (child != null) {
				return new Resolved(association.target().table() + "." + child.column(), association);
			}
		}
		return null;
	}

	/** 关联字段名必须是完整的一段驼峰词（后一位大写），所以 {@code orange…} 不会被关联 {@code order} 抢走前缀。 */
	private static String childPropertyOf(String property, Association association) {
		String field = association.field();
		if (property.length() <= field.length() || !Character.isUpperCase(property.charAt(field.length()))
				|| !property.regionMatches(true, 0, field, 0, field.length())) {
			return null;
		}
		return property.substring(field.length());
	}

	/** 属性解析失败：命中了关联前缀就指出子实体有哪些字段，否则列出本实体的字段与可用关联。 */
	private static DerivedQueryException unknownProperty(String property, EntityMetadata meta, String methodName) {
		for (Association association : meta.associations()) {
			String childProperty = childPropertyOf(property, association);
			if (childProperty != null) {
				return fail(methodName, "association '" + association.field() + "' has no property '" + childProperty
						+ "' on " + association.target().qualifiedName() + " (fields: "
						+ fieldNames(association.target()) + "); only one association level is resolved");
			}
			if (property.equalsIgnoreCase(association.field())) {
				return fail(methodName, "association '" + association.field()
						+ "' needs a property of its own after it, e.g. findBy"
						+ association.field().substring(0, 1).toUpperCase(Locale.ROOT)
						+ association.field().substring(1) + "<Property>");
			}
		}
		String associations = meta.associations().isEmpty() ? ""
				: "; one-level association paths supported: "
						+ meta.associations().stream().map(Association::field).collect(Collectors.joining(", "));
		return fail(methodName, "unknown property '" + property + "' on " + meta.qualifiedName() + " (fields: "
				+ fieldNames(meta) + associations + ")");
	}

	/** 属性名忽略大小写匹配字段；{@code email} / {@code Email} / {@code EMAIL} 都落到同一列。 */
	private static Column columnFor(String property, EntityMetadata meta) {
		for (Column column : meta.columns()) {
			if (column.field().equalsIgnoreCase(property)) {
				return column;
			}
		}
		return null;
	}

	private static String whereClause(List<List<Atom>> branches) {
		if (branches.isEmpty()) {
			return "";
		}
		if (branches.size() == 1) {
			return " WHERE " + branches.get(0).stream().map(Atom::sql).collect(Collectors.joining(" AND "));
		}
		return " WHERE " + branches.stream()
			.map(branch -> branch.size() > 1 ? "(" + branch.stream()
				.map(Atom::sql)
				.collect(Collectors.joining(" AND ")) + ")" : branch.get(0).sql())
			.collect(Collectors.joining(" OR "));
	}

	/** 占位符顺序与 SQL 里的 {@code ?} 严格一致：按谓词出现顺序消费形参，少一个多一个都报错。 */
	private static List<Binding> bind(List<Atom> atoms, List<Parameter> parameters, String methodName) {
		if (atoms.stream().filter(Atom::inList).count() > 1) {
			throw fail(methodName, "only one IN condition per query is expanded at runtime");
		}
		List<Binding> bindings = new ArrayList<>();
		int cursor = 0;
		for (Atom atom : atoms) {
			for (int slot = 0; slot < atom.arity(); slot++) {
				if (cursor == parameters.size()) {
					throw fail(methodName, "the method name needs more arguments than the declaration provides ("
							+ parameters.size() + ")");
				}
				Parameter parameter = parameters.get(cursor++);
				if (atom.inList() && !parameter.collection()) {
					throw fail(methodName, "IN condition needs a Collection argument, but '" + parameter.name()
							+ "' is " + parameter.typeSource());
				}
				bindings.add(new Binding(parameter.name(), atom.inList()));
			}
		}
		if (cursor != parameters.size()) {
			throw fail(methodName, "the method name only binds " + cursor + " of the " + parameters.size()
					+ " declared parameters");
		}
		return bindings;
	}

	/** 返回类型决定结果形态；裸实体首期不生成（0 行时无法表达，规范第 2 节也推荐 {@code Optional}）。 */
	private static Kind kindOf(Subject subject, String returnTypeSource, EntityMetadata meta, String methodName) {
		if (subject.kind() == Kind.COUNT || subject.kind() == Kind.EXISTS) {
			String expected = subject.kind() == Kind.COUNT ? "long" : "boolean";
			if (returnTypeSource.equals(expected) || returnTypeSource.equals(boxed(expected))) {
				return subject.kind();
			}
			throw fail(methodName, "'" + subject.verb() + "' must return " + expected + " (or " + boxed(expected)
					+ "), but the method declares " + returnTypeSource);
		}
		String entity = meta.qualifiedName();
		if (returnTypeSource.equals("java.util.List<" + entity + ">")) {
			return Kind.LIST;
		}
		if (returnTypeSource.equals("java.util.Optional<" + entity + ">")) {
			return Kind.OPTIONAL;
		}
		throw fail(methodName, "derived queries return java.util.List<" + entity + "> or java.util.Optional<" + entity
				+ "> (prefer Optional over the bare entity), but the method declares " + returnTypeSource);
	}

	private static String boxed(String primitive) {
		return switch (primitive) {
			case "long" -> "java.lang.Long";
			case "boolean" -> "java.lang.Boolean";
			default -> primitive;
		};
	}

	private static DerivedQueryException fail(String methodName, String reason) {
		return new DerivedQueryException("derived query '" + methodName + "' is not supported: " + reason);
	}

	/** 关键词表的一项。 */
	private record Keyword(String suffix, int arity, String fragment) {
	}

	/** 切出来的主语。 */
	private record Subject(String verb, Kind kind, String predicateText) {
	}

	/** 拆开的谓词：纯条件片段 + 已渲染的排序片段（无排序时为空串）。 */
	private record Ordering(String predicateText, String sql) {
	}

	/** 一个原子谓词：SQL 片段 + 消耗几个参数 + 值是否需要展开成 IN 列表。 */
	private record Atom(String sql, int arity, boolean inList) {
	}

	/**
	 * 属性落点：本表列，或经一层一对多关联落在子表列上。
	 *
	 * @param columnToken 片段里 {@code %s} 要替换的列引用（关联时已带子表名前缀）
	 * @param via 经由的关联；直接落在本表列时为 null
	 */
	private record Resolved(String columnToken, Association via) {

		String wrap(String fragment, EntityMetadata parent) {
			String condition = String.format(fragment, this.columnToken);
			if (this.via == null) {
				return condition;
			}
			String childTable = this.via.target().table();
			return "EXISTS (SELECT 1 FROM " + childTable + " WHERE " + childTable + "." + this.via.foreignKeyColumn()
					+ " = " + parent.table() + "." + parent.id().column() + " AND " + condition + ")";
		}
	}
}
