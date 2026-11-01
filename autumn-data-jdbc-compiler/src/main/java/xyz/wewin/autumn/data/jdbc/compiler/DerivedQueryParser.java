package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Binding;
import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Kind;
import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Parameter;
import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Transform;
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
 * <p><b>覆盖</b>：主语 {@code find/read/get/query/search/stream}、{@code count}、{@code exists}、
 * {@code delete/remove}；关键词见 {@link #KEYWORDS}（含 {@code Like} 家族与 {@code IgnoreCase} 修饰符、
 * {@code IsNot/Not}、{@code IsTrue/IsFalse} 等，与 Spring Data 对齐）；
 * {@code And}/{@code Or} 组合与 {@code OrderBy}；属性既可以是实体自己的字段，也可以是一层关联路径
 * （{@code findByOrdersOrderNo} 这类一对多条件，见 {@link #resolveProperty}）。
 * 形态修饰：{@code Distinct}（{@code SELECT DISTINCT}）、{@code First/Top N}（方言 {@code LIMIT} 行数上限，
 * 由运行期拼接到语句后）、{@code deleteBy}（返回 {@code long} 计数 或 {@code List<T>} 已删实体，后者先
 * {@code SELECT} 取回再 {@code DELETE}）。</p>
 */
final class DerivedQueryParser {

	/** 主语动词（规范第 2 节）；{@code delete/remove} 由 {@link #kindOf} 按返回类型决定删计数还是删实体。 */
	private static final List<String> VERBS = List.of("find", "read", "get", "query", "search", "stream", "count",
			"exists", "delete", "remove");

	private static final List<String> ORDER_DIRECTIONS = List.of("Asc", "Desc");

	/** IN 列表的运行期展开标记，与 CRUD 语句用同一个（{@link CrudSqlGenerator#IN_MARKER}）。 */
	private static final String IN_MARKER = CrudSqlGenerator.IN_MARKER;

	/**
	 * 关键词表：方法名后缀 →（消耗参数个数，SQL 片段模板，取数时的 LIKE 包裹形态）。按后缀长度倒序匹配，
	 * 保证 {@code GreaterThanEqual} 不被 {@code GreaterThan} 抢走、{@code IsNotNull} 不被 {@code NotNull} 截断、
	 * {@code NotContains} 不被 {@code Contains} 截断、{@code IsNot} 不被 {@code Not} 截断。
	 * {@code StartingWith/EndingWith/Containing} 这类本来要方言拼接字符串的，这里统一用内联
	 * {@code %?%} / {@code ?%} / {@code %?} 模式在运行期包裹实参（见 {@link #bind} 与
	 * {@link xyz.wewin.autumn.data.jdbc.compiler.ImplementationSource}），不再报错；
	 * {@code fragment} 为 null 仅作保留位，当前没有任何关键词命中它。
	 */
	private static final List<Keyword> KEYWORDS = List
		.of(new Keyword("GreaterThanEqual", 1, "%s >= ?", Transform.NONE),
			new Keyword("LessThanEqual", 1, "%s <= ?", Transform.NONE),
			new Keyword("NotBetween", 2, "%s NOT BETWEEN ? AND ?", Transform.NONE),
			new Keyword("NotContaining", 1, "%s NOT LIKE ?", Transform.CONTAINS),
			new Keyword("NotContains", 1, "%s NOT LIKE ?", Transform.CONTAINS),
			new Keyword("StartingWith", 1, "%s LIKE ?", Transform.STARTS_WITH),
			new Keyword("EndingWith", 1, "%s LIKE ?", Transform.ENDS_WITH),
			new Keyword("Contains", 1, "%s LIKE ?", Transform.CONTAINS),
			new Keyword("Containing", 1, "%s LIKE ?", Transform.CONTAINS),
			new Keyword("StartsWith", 1, "%s LIKE ?", Transform.STARTS_WITH),
			new Keyword("EndsWith", 1, "%s LIKE ?", Transform.ENDS_WITH),
			new Keyword("IsNotNull", 0, "%s IS NOT NULL", Transform.NONE),
			new Keyword("NotNull", 0, "%s IS NOT NULL", Transform.NONE),
			new Keyword("NotGreaterThan", 1, "%s <= ?", Transform.NONE),
			new Keyword("NotLessThan", 1, "%s >= ?", Transform.NONE),
			new Keyword("GreaterThan", 1, "%s > ?", Transform.NONE),
			new Keyword("LessThan", 1, "%s < ?", Transform.NONE),
			new Keyword("NotLike", 1, "%s NOT LIKE ?", Transform.NONE),
			new Keyword("Between", 2, "%s BETWEEN ? AND ?", Transform.NONE),
			new Keyword("IsNotEmpty", 0, "%s <> ''", Transform.NONE),
			new Keyword("NotEqual", 1, "%s <> ?", Transform.NONE),
			new Keyword("NotIn", 1, "%s NOT IN (" + IN_MARKER + ")", Transform.NONE),
			new Keyword("Equals", 1, "%s = ?", Transform.NONE),
			new Keyword("IsNull", 0, "%s IS NULL", Transform.NONE),
			new Keyword("IsNot", 1, "%s <> ?", Transform.NONE),
			new Keyword("Not", 1, "%s <> ?", Transform.NONE),
			new Keyword("After", 1, "%s > ?", Transform.NONE),
			new Keyword("Before", 1, "%s < ?", Transform.NONE),
			new Keyword("Equal", 1, "%s = ?", Transform.NONE),
			new Keyword("Like", 1, "%s LIKE ?", Transform.NONE),
			new Keyword("IsTrue", 0, "%s = TRUE", Transform.NONE),
			new Keyword("IsFalse", 0, "%s = FALSE", Transform.NONE),
			new Keyword("True", 0, "%s = TRUE", Transform.NONE),
			new Keyword("False", 0, "%s = FALSE", Transform.NONE),
			new Keyword("Null", 0, "%s IS NULL", Transform.NONE),
			new Keyword("Empty", 0, "%s = ''", Transform.NONE),
			new Keyword("IsEmpty", 0, "%s = ''", Transform.NONE),
			new Keyword("In", 1, "%s IN (" + IN_MARKER + ")", Transform.NONE))
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
		boolean isCountOrExists = kind == Kind.COUNT || kind == Kind.EXISTS;
		boolean isDelete = kind == Kind.DELETE || kind == Kind.DELETE_LIST;
		// 计数/存在性判断与删除没有行序概念（前者在 PostgreSQL 还会因非聚合列报错），带上 ORDER BY 无意义
		String orderSql = (isCountOrExists || isDelete || ordering.sql().isEmpty()) ? ""
				: " ORDER BY " + ordering.sql();
		String where = whereClause(branches);
		String distinctPrefix = subject.distinct() ? "DISTINCT " : "";
		String selectSql;
		String mainSql;
		if (isDelete) {
			// deleteBy 返回实体列表时，先 SELECT 把要删的行取回再 DELETE，两条语句共用同一 WHERE
			selectSql = "SELECT " + distinctPrefix + columns(meta) + " FROM " + meta.table() + where + orderSql;
			mainSql = "DELETE FROM " + meta.table() + where;
		}
		else {
			String projection = isCountOrExists ? "COUNT(*)" : distinctPrefix + columns(meta);
			mainSql = "SELECT " + projection + " FROM " + meta.table() + where + orderSql;
			selectSql = "";
		}
		return new DerivedQuery(methodName, kind, returnTypeSource, parameters, bindings, mainSql, selectSql,
				subject.distinct(), subject.limit(), false);
	}

	/**
	 * 从 {@code @Query} 给出的 SQL 直接构造派生查询结果：不解析方法名，参数按声明顺序绑定到 {@code ?} 占位符。
	 *
	 * @param methodName 接口方法名（决定生成类里的 SQL 常量名）
	 * @param sql 注解里写死的 SQL
	 * @param returnTypeSource 返回类型源码形态
	 * @param parameters 形参表（顺序即绑定顺序）
	 * @throws DerivedQueryException 返回类型不在支持集合内
	 */
	static DerivedQuery custom(String methodName, String sql, String returnTypeSource, List<Parameter> parameters) {
		Kind kind;
		boolean select = isSelect(sql);
		if (returnTypeSource.startsWith("java.util.List<")) {
			kind = Kind.LIST;
		}
		else if (returnTypeSource.startsWith("java.util.Optional<")) {
			kind = Kind.OPTIONAL;
		}
		else if (returnTypeSource.equals("void")) {
			if (select) {
				throw new DerivedQueryException("derived query '" + methodName
						+ "' with @Query returns void but the SQL is a SELECT - return a List/Optional or change the SQL");
			}
			kind = Kind.DELETE;
		}
		else if (returnTypeSource.equals("long") || returnTypeSource.equals("java.lang.Long")
				|| returnTypeSource.equals("int") || returnTypeSource.equals("java.lang.Integer")) {
			kind = select ? Kind.COUNT : Kind.DELETE;
		}
		else {
			throw new DerivedQueryException("derived query '" + methodName + "' with @Query must return"
					+ " java.util.List<Entity>, java.util.Optional<Entity>, void, or a numeric count/update result,"
					+ " but declares " + returnTypeSource);
		}
		List<Binding> bindings = new ArrayList<>();
		for (Parameter parameter : parameters) {
			bindings.add(new Binding(parameter.name(), parameter.collection(), Transform.NONE));
		}
		return new DerivedQuery(methodName, kind, returnTypeSource, parameters, bindings, sql, "", false, 0L, true);
	}

	/** SQL 是否以 {@code SELECT} 开头（忽略前导空白、忽略大小写）。 */
	private static boolean isSelect(String sql) {
		int i = 0;
		while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
			i++;
		}
		return i + 6 <= sql.length() && sql.regionMatches(true, i, "select", 0, 6);
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
					"unrecognised subject; derived queries start with find/read/get/query/search/stream/count/exists/delete");
		}
		String rest = methodName.substring(verb.length());
		boolean distinct = false;
		if (rest.startsWith("Distinct")) {
			distinct = true;
			rest = rest.substring("Distinct".length());
		}
		// First/Top 行数上限：{@code findFirstBy} / {@code findTopBy} 默认 1，{@code findTop3By} 取 3；
		// 数字由编译期解析成常量，运行期经方言拼 {@code LIMIT}
		long limit = 0L;
		if (rest.startsWith("First") || rest.startsWith("Top")) {
			int prefix = rest.startsWith("First") ? "First".length() : "Top".length();
			String after = rest.substring(prefix);
			int end = 0;
			while (end < after.length() && Character.isDigit(after.charAt(end))) {
				end++;
			}
			String digits = after.substring(0, end);
			limit = digits.isEmpty() ? 1L : Long.parseLong(digits);
			rest = after.substring(end);
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
		return new Subject(verb, kind, predicateText, distinct, limit);
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

	/** 一个原子谓词：先剥离 {@code IgnoreCase} 修饰符，再拿整个片段当属性名试（等值），最后按最长关键词后缀切。 */
	private static Atom atomOf(String atom, EntityMetadata meta, String methodName) {
		boolean ignoreCase = false;
		String base = atom;
		if (base.endsWith("IgnoreCase")) {
			ignoreCase = true;
			base = base.substring(0, base.length() - "IgnoreCase".length());
		}
		else if (base.endsWith("IgnoringCase")) {
			ignoreCase = true;
			base = base.substring(0, base.length() - "IgnoringCase".length());
		}
		Resolved exact = resolveProperty(base, meta);
		if (exact != null) {
			return new Atom(exact.wrap("%s = ?", meta, ignoreCase), 1, false, Transform.NONE);
		}
		String unresolvableProperty = null;
		for (Keyword keyword : KEYWORDS) {
			if (base.length() <= keyword.suffix().length() || !base.endsWith(keyword.suffix())) {
				continue;
			}
			String property = base.substring(0, base.length() - keyword.suffix().length());
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
			return new Atom(resolved.wrap(keyword.fragment(), meta, ignoreCase), keyword.arity(),
					keyword.suffix().endsWith("In"), keyword.transform());
		}
		throw unknownProperty(unresolvableProperty != null ? unresolvableProperty : base, meta, methodName);
	}

	private static String fieldNames(EntityMetadata meta) {
		return meta.columns().stream().map(Column::field).collect(Collectors.joining(", "));
	}

	/**
	 * 属性名 → SQL 列落点：先按本实体字段匹配，再沿关联路径走（{@code OrdersOrderNo} 即关联
	 * {@code orders} 的子实体字段 {@code orderNo}；{@code OrdersItemsSku} 再下一层到 {@code Item.sku}）。
	 * 解析不出返回 null，由调用方决定报错文案。
	 *
	 * <p>关联条件写成相关子查询而不是 JOIN：{@code EXISTS} 天然按存在性过滤，父行不会因一对多重复，
	 * 所以父表的列清单、{@code ROW_MAPPER} 和已有的关联装配都不用改，也不依赖方言。多对一与一对多在
	 * 子查询里的连接方向不同（前者 {@code child.id = parent.fk}，后者 {@code child.fk = parent.id}），
	 * 由 {@link Resolved#wrap} 按每层关联的形态拼出。</p>
	 */
	private static Resolved resolveProperty(String property, EntityMetadata meta) {
		return resolvePath(property, meta, List.of());
	}

	/** 沿 <b>已走过的关联</b> 递归解析：当前实体的直接列优先，否则试每一层关联再下钻到子实体。 */
	private static Resolved resolvePath(String property, EntityMetadata meta, List<Association> prefix) {
		Column direct = columnFor(property, meta);
		if (direct != null) {
			// 根实体上的直接列不带表前缀（避免 SELECT 列名重复）；子实体上的列带表前缀以免歧义
			String token = prefix.isEmpty() ? direct.column() : meta.table() + "." + direct.column();
			return new Resolved(token, prefix);
		}
		for (Association association : meta.associations()) {
			String childProperty = childPropertyOf(property, association);
			if (childProperty == null) {
				continue;
			}
			List<Association> next = new ArrayList<>(prefix);
			next.add(association);
			Resolved child = resolvePath(childProperty, association.target(), next);
			if (child != null) {
				return child;
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
					+ fieldNames(association.target()) + ")"
					+ (association.target().associations().isEmpty()
							? "; deeper paths need " + association.target().qualifiedName()
									+ " to declare its own associations"
							: ""));
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
				bindings.add(new Binding(parameter.name(), atom.inList(), atom.transform()));
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
		if (subject.verb().equals("delete") || subject.verb().equals("remove")) {
			String entity = meta.qualifiedName();
			if (returnTypeSource.equals("java.util.List<" + entity + ">")) {
				return Kind.DELETE_LIST;
			}
			if (returnTypeSource.equals("long") || returnTypeSource.equals("java.lang.Long")) {
				return Kind.DELETE;
			}
			throw fail(methodName, "'" + subject.verb() + "By' must return java.util.List<" + entity
					+ "> (the deleted rows) or long (the number of deleted rows), but the method declares "
					+ returnTypeSource);
		}
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
	private record Keyword(String suffix, int arity, String fragment, Transform transform) {
	}

	/** 切出来的主语：动词 + 结果形态 + 谓词 + Distinct/First-Top 修饰。 */
	private record Subject(String verb, Kind kind, String predicateText, boolean distinct, long limit) {
	}

	/** 拆开的谓词：纯条件片段 + 已渲染的排序片段（无排序时为空串）。 */
	private record Ordering(String predicateText, String sql) {
	}

	/** 一个原子谓词：SQL 片段 + 消耗几个参数 + 值是否需要展开成 IN 列表 + 取数时是否做 LIKE 包裹。 */
	private record Atom(String sql, int arity, boolean inList, Transform transform) {
	}

	/**
	 * 属性落点：本表列，或经若干层关联（一对多 / 多对一）落在子表列上。
	 *
	 * @param columnToken 片段里 {@code %s} 要替换的列引用（子表列已带表名前缀）
	 * @param path 途经的关联（从根到叶）；空列表表示直接落在本表列
	 */
	private record Resolved(String columnToken, List<Association> path) {

		/**
		 * 生成最终的 SQL 条件：最里层是列比较，外层按途经的每一层关联包一层 {@code EXISTS} 相关子查询。
		 * 每层的连接方向由关联形态决定：一对多是 {@code child.fk = parent.id}，多对一是 {@code child.id = parent.fk}。
		 *
		 * @param fragment 形如 {@code "%s = ?"} 的 SQL 模板
		 * @param root 根实体元数据（最外层关联的父表）
		 * @param ignoreCase 是否用 {@code LOWER} 包裹列与参数
		 */
		String wrap(String fragment, EntityMetadata root, boolean ignoreCase) {
			String column = ignoreCase ? "LOWER(" + this.columnToken + ")" : this.columnToken;
			String condition = String.format(fragment, column);
			if (ignoreCase) {
				condition = condition.replace("?", "LOWER(?)");
			}
			if (this.path.isEmpty()) {
				return condition;
			}
			String inner = condition;
			for (int i = this.path.size() - 1; i >= 0; i--) {
				Association hop = this.path.get(i);
				EntityMetadata parent = (i == 0) ? root : this.path.get(i - 1).target();
				String parentTable = parent.table();
				String childTable = hop.target().table();
				String join = hop.toOne()
						? childTable + "." + hop.target().id().column() + " = " + parentTable + "."
								+ hop.foreignKeyColumn()
						: childTable + "." + hop.foreignKeyColumn() + " = " + parentTable + "."
								+ ((i == 0) ? root.id().column() : this.path.get(i - 1).target().id().column());
				inner = "EXISTS (SELECT 1 FROM " + childTable + " WHERE " + join + " AND " + inner + ")";
			}
			return inner;
		}
	}
}
