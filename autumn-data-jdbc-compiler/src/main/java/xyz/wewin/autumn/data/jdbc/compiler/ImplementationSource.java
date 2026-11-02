package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Binding;
import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Kind;
import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Transform;
import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Association;
import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Column;

/**
 * 生成 {@code <名称>Impl} 的 Java 源码文本。
 *
 * <p>运行期形态：构造注入核心 {@code AutumnJdbc}（唯一的运行期协作者，里面收着方言与取数上限），
 * SQL 在编译期直接内联为生成类里的 {@code private static final String} 常量（不再落盘 properties、不再运行期加载），
 * 结果集映射是生成的显式取值代码 —— 全程零反射、运行期零资源 I/O。</p>
 *
 * <p>一对多：一条父查询 + 一条按外键 {@code IN} 的子查询，再按外键分组写回集合；
 * 子查询行数由 {@code AutumnJdbc#queryLimited} 按 {@code associationLimit} 截断。
 * 分页装配额外按关联加载模式分级（见 {@code findAllPageableBody}）：
 * DEFAULT（20 条分页级联）= 页大小不超过阈值（默认 20）eager 加载 1:n / n:1，超过阈值不自动级联；
 * CASCADE = 任何页大小强制级联；DISABLED = 任何页大小强制关闭关联（不查询）。</p>
 *
 * <p>字段投影（{@code FieldProjection}）：编译期每条取实体行的语句都额外内联一份「投影模板」
 * （列清单换成 {@code {cols}}），运行期只有设了投影才把标记换成精简列清单并走投影映射器 ——
 * <b>不设投影的默认路径仍是全列常量、零拼接</b>。主键与多对一装配要读的外键列编译期判定为不可省略，
 * 恒进 {@code SELECT}，因此裁剪列不会打断级联。</p>
 */
final class ImplementationSource {

	/** 运行期仅保留的 SQL 动态拼接助手（把 {@code IN (:ids)} 按参数个数展开成 {@code ?}）。 */
	private static final String SQL_HELPER = "xyz.wewin.autumn.data.jdbc.sql.Sql";

	private static final String AUTUMN_JDBC = "xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc";

	private static final String PAGE = "xyz.wewin.autumn.data.jdbc.page.Page";

	private static final String PAGEABLE = "xyz.wewin.autumn.data.jdbc.page.Pageable";

	private static final String ROW_MAPPER = "xyz.wewin.autumn.data.jdbc.runtime.RowMapper";

	/** 分页关联加载分级策略的上下文持有器与值对象（编译期生成的分页代码按线程读取它）。 */
	private static final String CONTEXT_HOLDER = "xyz.wewin.autumn.data.jdbc.runtime.AutumnDataContextHolder";

	private static final String AUTUMN_DATA_CONTEXT = "xyz.wewin.autumn.data.jdbc.runtime.AutumnDataContext";

	private static final String ASSOCIATION_MODE = "xyz.wewin.autumn.data.jdbc.runtime.AssociationMode";

	/** 一次性字段投影：设了它才把编译期的全列语句换成 {@code {cols}} 模板拼精简列。 */
	private static final String FIELD_PROJECTION = "xyz.wewin.autumn.data.jdbc.runtime.FieldProjection";

	/** 投影模板常量名后缀，与 {@code CrudSqlGenerator.PROJECTION} 生成的常量名一致。 */
	private static final String PROJECTION_SUFFIX = "_PROJECTION";

	/** 投影路径的行映射器：把线程上生效的投影带进 {@code __rowProjected}。 */
	private static final String PROJECTED_MAPPER = "__projectedMapper(__projection)";

	private ImplementationSource() {
	}

	static String generate(String interfaceSimpleName, String implSimpleName, String entity, String id,
			EntityMetadata meta, EntityMetadata sqlMeta, Map<String, String> statements, List<DerivedQuery> queries,
			boolean paging) {
		return new Generation(interfaceSimpleName, implSimpleName, entity, id, meta, sqlMeta, statements, queries,
				paging).render();
	}

	/** 一次生成的上下文，方法各自返回一段源码文本。 */
	private static final class Generation {

		private final String interfaceSimpleName;

		private final String implSimpleName;

		private final String entity;

		private final String id;

		private final EntityMetadata meta;

		/** 同一元数据的方言引号视图：投影时拼进 {@code SELECT} 的列名要用它，取值标签仍用 {@code meta} 的裸列名。 */
		private final EntityMetadata sqlMeta;

		private final Map<String, String> statements;

		private final List<DerivedQuery> queries;

		private final boolean paging;

		private Generation(String interfaceSimpleName, String implSimpleName, String entity, String id,
				EntityMetadata meta, EntityMetadata sqlMeta, Map<String, String> statements,
				List<DerivedQuery> queries, boolean paging) {
			this.interfaceSimpleName = interfaceSimpleName;
			this.implSimpleName = implSimpleName;
			this.entity = entity;
			this.id = id;
			this.meta = meta;
			this.sqlMeta = sqlMeta;
			this.statements = statements;
			this.queries = queries;
			this.paging = paging;
		}

		private List<Association> eagerAssociations() {
			return this.meta.associations().stream().filter(Association::eager).toList();
		}

		/** 实体是否存在可裁剪的列（主键与级联必需列之外）；全为必需列时不生成投影相关代码。 */
		private boolean projectable() {
			return this.meta.projectionEligible();
		}

		/** {@code 常量名 → 常量名_PROJECTION}：{@code deleteBy} 返实体列表时模板挂在 {@code _SELECT} 语句上。 */
		private static String projectionConstOf(String constName, Kind kind) {
			return (kind == Kind.DELETE_LIST ? constName + "_SELECT" : constName) + PROJECTION_SUFFIX;
		}

		/** CRUD 取实体语句的投影模板常量名；实体无可裁剪列时返回 {@code null}（该语句只有全列形态）。 */
		private String crudProjectionConst(String statementKey) {
			return projectable() ? sqlConst(statementKey + CrudSqlGenerator.PROJECTION) : null;
		}

		/** 集合条件要把 {@code IN (:ids)} 按参数个数展开；全列与投影两条形态包同一层。参数个数表达式为 null 时不展开。 */
		private static String inWrapped(String sqlExpr, String sizeExpression) {
			return sizeExpression == null ? sqlExpr
					: SQL_HELPER + ".inClause(" + sqlExpr + ", " + sizeExpression + ")";
		}

		private String inWrapped(String sqlExpr, Binding collection) {
			return inWrapped(sqlExpr, collection == null ? null : collection.expression() + ".size()");
		}

		/** 投影形态的 SQL 表达式：把编译期内联的模板里的 {@code {cols}} 换成运行期挑出的精简列清单。 */
		private static String projectedColumns(String projectionConst) {
			return SQL_HELPER + ".withColumns(" + projectionConst + ", __projectionColumns(__projection))";
		}

		/**
		 * 取实体行的调用：默认走编译期写定的全列常量 + {@code ROW_MAPPER}（绝大多数查询，运行期零拼接）；
		 * 设了字段投影才走 {@code {cols}} 模板拼出的精简列 + 投影映射器。
		 *
		 * @param receiver {@code AutumnJdbc} 上的方法前缀，如 {@code this.autumn.query}
		 * @param fullSql 全列形态里排在行映射器之前的实参（分页时是「主语句, 计数语句」两段）
		 * @param projectionSql 投影形态的同位置实参；该语句不裁剪列时传 {@code null}
		 * @param tailArguments 行映射器之后的其余实参（自带前导逗号，无则空串）
		 */
		private String fetch(String receiver, String fullSql, String projectionSql, String tailArguments) {
			return fetch(receiver, "", fullSql, projectionSql, tailArguments);
		}

		/**
		 * 同上，额外允许在 SQL 之前放一段实参前缀（{@code First/Top} 的重载把行数上限排在 SQL 前面）。
		 */
		private String fetch(String receiver, String prefix, String fullSql, String projectionSql,
				String tailArguments) {
			String full = receiver + "(" + prefix + fullSql + ", ROW_MAPPER" + tailArguments + ")";
			if (projectionSql == null) {
				return full;
			}
			return "(__projection == null) ? " + full + " : " + receiver + "(" + prefix + projectionSql + ", "
					+ PROJECTED_MAPPER + tailArguments + ")";
		}

		/** 标准 CRUD 取实体行的调用（{@code sizeExpression} 非空表示该语句有 {@code IN} 待展开）。 */
		private String crudFetch(String receiver, String statementKey, String sizeExpression, String tailArguments) {
			String projectionConst = crudProjectionConst(statementKey);
			return fetch(receiver, inWrapped(sqlConst(statementKey), sizeExpression),
					projectionConst == null ? null
							: inWrapped(projectedColumns(projectionConst), sizeExpression),
					tailArguments);
		}

		/** 方法体首行：读线程上生效的一次性字段投影（实体无可裁剪列时不需要）。 */
		private String projectionPrelude() {
			return projectable()
					? FIELD_PROJECTION + " __projection = " + CONTEXT_HOLDER + ".projection();\n"
					: "";
		}

		/** 一次性上下文（级联模式与字段投影）被最近一次取实体的查询消费掉，之后自动回到编译期默认形态。 */
		private static String consumePending() {
			return CONTEXT_HOLDER + ".consumePending();\n";
		}

		/** 投影三元组：属性名 / 进 {@code SELECT} 的列名（方言引号形态）/ 是否不可省略。 */
		private String projectionColumnArrays() {
			Set<String> required = this.meta.projectionRequiredFields();
			List<String> fields = new ArrayList<>();
			List<String> columns = new ArrayList<>();
			List<String> requiredFlags = new ArrayList<>();
			for (int i = 0; i < this.meta.columns().size(); i++) {
				Column column = this.meta.columns().get(i);
				fields.add('"' + column.field() + '"');
				// 方言引号形态的列名里可能带双引号（PostgreSQL/H2 用 " 包裹），必须转义后再写进字面量
				columns.add('"' + escapeJava(this.sqlMeta.columns().get(i).column()) + '"');
				requiredFlags.add(String.valueOf(required.contains(column.field())));
			}
			return "\t/** 字段投影的列清单：属性名、进 SELECT 的列名（方言引号形态）、该列是否不可省略。 */\n"
					+ "\tprivate static final String[] __PROJ_FIELDS = { " + String.join(", ", fields) + " };\n\n"
					+ "\tprivate static final String[] __PROJ_COLUMNS = { " + String.join(", ", columns)
					+ " };\n\n" + "\tprivate static final boolean[] __PROJ_REQUIRED = { "
					+ String.join(", ", requiredFlags) + " };\n\n";
		}

		/** 按投影挑列：白/黑名单由 {@code FieldProjection} 判定，不可省略列（主键、级联外键）恒进清单。 */
		private String projectionColumnsMethod() {
			List<String> body = List.of("StringBuilder columns = new StringBuilder();",
					"for (int i = 0; i < __PROJ_FIELDS.length; i++) {",
					"\tif (__projection.projects(__PROJ_FIELDS[i], __PROJ_REQUIRED[i])) {",
					"\t\tif (columns.length() > 0) {",
					"\t\t\tcolumns.append(\", \");",
					"\t\t}",
					"\t\tcolumns.append(__PROJ_COLUMNS[i]);",
					"\t}",
					"}",
					"return columns.toString();");
			return "\tprivate static String __projectionColumns(" + FIELD_PROJECTION + " __projection) {\n"
					+ indent(String.join("\n", body)) + "\t}\n\n";
		}

		/** 投影版实体构造：只取被选中的列，未选中的属性留在实体默认值上。 */
		private String projectedRowMethod() {
			Set<String> required = this.meta.projectionRequiredFields();
			List<String> lines = new ArrayList<>();
			lines.add(this.meta.qualifiedName() + " row = new " + this.meta.qualifiedName() + "();");
			for (Column column : this.meta.columns()) {
				lines.add("if (projection.projects(\"" + column.field() + "\", " + required.contains(column.field())
						+ ")) {");
				lines.add("\trow." + column.setter() + "(" + readColumn(column) + ");");
				lines.add("}");
			}
			lines.add("return row;");
			return "\tprivate static " + this.entity + " __rowProjected(java.sql.ResultSet rs,"
					+ " " + FIELD_PROJECTION + " projection) throws java.sql.SQLException {\n"
					+ indent(String.join("\n", lines)) + "\t}\n\n";
		}

		/** 投影版映射器：把生效投影带进 {@code __rowProjected}，与 {@code ROW_MAPPER} 并存。 */
		private String projectedMapperMethod() {
			return "\tprivate static " + ROW_MAPPER + '<' + this.entity + "> __projectedMapper(" + FIELD_PROJECTION
					+ " projection) {\n\t\treturn (rs, rowNum) -> __rowProjected(rs, projection);\n\t}\n\n";
		}

		private String render() {
			StringBuilder sb = new StringBuilder();
			List<String> queryConstNames = derivedConstNames();
			sb.append("/** 由 autumn-data-jdbc-compiler 生成，请勿手工修改。 */\n");
		sb.append("@javax.annotation.processing.Generated(\"")
			.append(AutoRepositoryProcessor.class.getName())
			.append("\")\n");
		sb.append("public class ").append(this.implSimpleName).append(" implements ")
			.append(this.interfaceSimpleName).append(" {\n\n");
		// SQL 在编译期内联为常量：运行期零资源加载、零解析（仅 IN 子句占位符展开留在 Sql.inClause）
		for (Entry<String, String> statement : this.statements.entrySet()) {
			sb.append("\tprivate static final String ").append(sqlConst(statement.getKey())).append(" = \"")
				.append(escapeJava(statement.getValue())).append("\";\n\n");
		}
		for (int i = 0; i < this.queries.size(); i++) {
			DerivedQuery query = this.queries.get(i);
			sb.append("\tprivate static final String ").append(queryConstNames.get(i)).append(" = \"")
				.append(escapeJava(query.sql())).append("\";\n\n");
			// deleteBy 返回实体列表时，另存一条 SELECT 常量：运行期先取回要删的行再删
			if (query.kind() == Kind.DELETE_LIST) {
				sb.append("\tprivate static final String ").append(queryConstNames.get(i)).append("_SELECT = \"")
					.append(escapeJava(query.selectSql())).append("\";\n\n");
			}
			// 分页派生查询另存一条计数常量：与主语句同 WHERE、不带 ORDER BY
			if (query.kind() == Kind.PAGE) {
				sb.append("\tprivate static final String ").append(queryConstNames.get(i)).append("_COUNT = \"")
					.append(escapeJava(query.countSql())).append("\";\n\n");
			}
			// 取实体行的派生查询再存一条投影模板：与全列语句同形，只把列清单换成 {cols}
			if (query.projectable()) {
				sb.append("\tprivate static final String ")
					.append(projectionConstOf(queryConstNames.get(i), query.kind()))
					.append(" = \"")
					.append(escapeJava(query.projectionSql()))
					.append("\";\n\n");
			}
		}
		sb.append(rowMapperField(this.meta, "ROW_MAPPER", this.entity));
			for (Association association : eagerAssociations()) {
				sb.append(association.toOne() ? childRowMapperFieldToOne(association) : childRowMapperField(association));
			}
			if (projectable()) {
				sb.append(projectionColumnArrays());
				sb.append(projectionColumnsMethod());
				sb.append(projectedRowMethod());
				sb.append(projectedMapperMethod());
			}
			sb.append("\tprivate final " + AUTUMN_JDBC + " autumn;\n\n");
			sb.append(constructor());
			sb.append(method("<S extends " + this.entity + "> S save(S entity)", saveBody()));
			sb.append(method("<S extends " + this.entity + "> java.util.List<S> saveAll(java.util.List<S> entities)",
					saveAllBody()));
			sb.append(method("java.util.Optional<" + this.entity + "> findById(" + this.id + " id)",
					findByIdBody()));
			sb.append(method("boolean existsById(" + this.id + " id)",
					"return this.autumn.count(" + sqlConst(CrudSqlGenerator.EXISTS_BY_ID) + ", id) > 0;"));
			sb.append(method("java.util.List<" + this.entity + "> findAll()",
					loaded(crudFetch("this.autumn.query", CrudSqlGenerator.SELECT_ALL, null, ""))));
			if (this.paging) {
				sb.append(method(PAGE + "<" + this.entity + "> findAll(" + PAGEABLE + " pageable)",
						findAllPageableBody()));
			}
			sb.append(method("java.util.List<" + this.entity + "> findAllById(java.util.List<" + this.id + "> ids)",
					findAllByIdBody()));
			sb.append(method("long count()",
					"return this.autumn.count(" + sqlConst(CrudSqlGenerator.COUNT) + ");"));
			sb.append(method("void deleteById(" + this.id + " id)",
					"this.autumn.update(" + sqlConst(CrudSqlGenerator.DELETE_BY_ID) + ", id);"));
			sb.append(method("void delete(" + this.entity + " entity)",
					"deleteById(entity." + this.meta.id().getter() + "());"));
			sb.append(method("void deleteAllById(java.util.List<? extends " + this.id + "> ids)",
					deleteAllByIdBody()));
			sb.append(method("void deleteAll(java.util.List<? extends " + this.entity + "> entities)",
					deleteAllBody()));
			sb.append(method("void deleteAll()",
					"this.autumn.update(" + sqlConst(CrudSqlGenerator.DELETE_ALL) + ");"));
			for (int i = 0; i < this.queries.size(); i++) {
				DerivedQuery query = this.queries.get(i);
				String constName = queryConstNames.get(i);
				String selectConstName = query.kind() == Kind.DELETE_LIST ? constName + "_SELECT" : "";
				String countConstName = query.kind() == Kind.PAGE ? constName + "_COUNT" : "";
				String projectionConstName = query.projectable() ? projectionConstOf(constName, query.kind()) : "";
				sb.append(method(derivedSignature(query),
						derivedBody(query, constName, selectConstName, countConstName, projectionConstName)));
			}
			sb.append(privateMethod("insert", "void", this.entity + " entity", insertBody()));
			for (Association association : eagerAssociations()) {
				sb.append(childRowMethod(association));
				sb.append(association.toOne() ? attachToOneMethod(association) : attachMethod(association));
			}
			sb.append("}\n");
			return sb.toString();
		}

		private String method(String signature, String body) {
			return "\t@Override\n\tpublic " + signature + " {\n" + indent(body) + "\t}\n\n";
		}

		private String privateMethod(String name, String returnType, String parameters, String body) {
			return "\tprivate " + returnType + " " + name + "(" + parameters + ") {\n" + indent(body) + "\t}\n\n";
		}

		private static String indent(String body) {
			StringBuilder sb = new StringBuilder();
			for (String line : body.split("\n", -1)) {
				if (!line.isEmpty()) {
					sb.append("\t\t");
				}
				sb.append(line).append('\n');
			}
			return sb.toString();
		}

		/** SQL 常量名：{@code <key>} → {@code SQL_<KEY 大写，驼峰边界加下划线>}，作生成类里的常量名。 */
		private static String sqlConst(String key) {
			StringBuilder sb = new StringBuilder("SQL_");
			for (int i = 0; i < key.length(); i++) {
				char c = key.charAt(i);
				if (i > 0 && Character.isUpperCase(c)) {
					sb.append('_');
				}
				sb.append(Character.toUpperCase(c));
			}
			return sb.toString();
		}

		/**
		 * 派生查询的 SQL 常量名：按方法名走同一套命名，并避开 CRUD 已占用的名字；
		 * 同名重载（{@code findByName(String)} 与 {@code findByName(Long)}）追加序号，保证常量名唯一。
		 */
		private List<String> derivedConstNames() {
			Set<String> taken = new HashSet<>();
			for (String key : this.statements.keySet()) {
				taken.add(sqlConst(key));
			}
			List<String> names = new ArrayList<>();
			for (DerivedQuery query : this.queries) {
				String base = sqlConst(query.methodName());
				String name = base;
				for (int ordinal = 2; taken.contains(name); ordinal++) {
					name = base + "_" + ordinal;
				}
				taken.add(name);
				names.add(name);
			}
			return names;
		}

		/** 派生查询的方法签名，直接沿用接口上的返回类型与形参表。 */
		private static String derivedSignature(DerivedQuery query) {
			String parameters = query.parameters().stream()
				.map(parameter -> parameter.typeSource() + " " + parameter.name())
				.collect(Collectors.joining(", "));
			return query.returnTypeSource() + " " + query.methodName() + "(" + parameters + ")";
		}

		/**
		 * 派生查询的方法体：结果形态决定调 {@code query / queryOptional / count / update}。
		 *
		 * <p>带集合条件时，SQL 走 {@code Sql.inClause} 按集合大小展开占位符，实参先收进一个列表再整体传入
		 * —— 标量与集合混在一起时 varargs 拼不出来。结果实体若配了一对多关联，复用 {@link #loaded} 的装配路径，
		 * 所以派生查询和 {@code findAll} 一样不会出 N+1。</p>
		 *
		 * <p>{@code Distinct} 已在编译期写进 SELECT 投影；{@code First/Top N} 把行数上限作为字面量传给
		 * {@code AutumnJdbc} 的带 {@code limit} 重载，由方言在运行期拼 {@code LIMIT}；{@code deleteBy} 返回
		 * {@code long} 走 {@code update} 拿受影响行数，返回 {@code List<T>} 先 {@code SELECT} 取回再 {@code DELETE}。</p>
		 */
		private String derivedBody(DerivedQuery query, String constName, String selectConstName,
				String countConstName, String projectionConstName) {
			List<Binding> bindings = query.bindings();
			Binding collection = bindings.stream().filter(Binding::collectionValues).findFirst().orElse(null);
			StringBuilder prelude = new StringBuilder();
			String sqlArgument = constName;
			String valueArguments;
			if (collection == null) {
				valueArguments = bindings.stream().map(this::valueExpressionFor).collect(Collectors.joining(", "));
			}
			else {
				sqlArgument = SQL_HELPER + ".inClause(" + constName + ", " + collection.expression() + ".size())";
				prelude.append("java.util.List<Object> values = new java.util.ArrayList<>();\n");
				for (Binding binding : bindings) {
					prelude.append(binding.collectionValues() ? "values.addAll(" : "values.add(")
						.append(valueExpressionFor(binding))
						.append(");\n");
				}
				valueArguments = "values.toArray()";
			}
			// AutumnJdbc 的形参顺序是 (sql, mapper, args...)，取值实参在行映射器之后
			String tailArguments = bindings.isEmpty() ? "" : ", " + valueArguments;
			// deleteBy 返回实体列表：SELECT 与 DELETE 共用同一 WHERE，因此走同一个 inClause 展开与取值实参
			String selectArgument = query.kind() == Kind.DELETE_LIST ? inWrapped(selectConstName, collection)
					: sqlArgument;
			// 投影模板与全列语句同 WHERE，只是列清单换成 {cols}；该查询不可裁剪列时没有投影形态
			String projectionArgument = projectionConstName.isEmpty() ? null
					: inWrapped(projectedColumns(projectionConstName), collection);
			// 分页查询的计数语句：与主语句同一 WHERE、不受投影影响
			String countSqlArgument = query.kind() == Kind.PAGE ? inWrapped(countConstName, collection) : sqlArgument;
			String countArguments = countSqlArgument + tailArguments;
			long limit = query.limit();
			if (limit > 0 && (query.kind() == Kind.LIST || query.kind() == Kind.OPTIONAL)) {
				// First/Top：行数上限作为字面量传给带 limit 的重载（上限排在 SQL 之前），由方言拼 LIMIT
				String receiver = query.kind() == Kind.LIST ? "this.autumn.query" : "this.autumn.queryOptional";
				String limitPrefix = limit + "L, ";
				return prelude + (query.kind() == Kind.LIST
						? loaded(fetch(receiver, limitPrefix, selectArgument, projectionArgument, tailArguments))
						: derivedOptional(
								fetch(receiver, limitPrefix, selectArgument, projectionArgument, tailArguments)));
			}
			String selectCall = fetch("this.autumn.query", selectArgument, projectionArgument, tailArguments);
			return switch (query.kind()) {
				// @Query 的 SQL 由用户写死：既不裁剪列、也不装配关联，因此不参与一次性上下文（不读也不消费）
				case LIST -> query.custom()
						? prelude + "return " + fetch("this.autumn.query", selectArgument, null, tailArguments) + ";"
						: prelude + loaded(selectCall);
				case OPTIONAL -> query.custom()
						? prelude + "return "
								+ fetch("this.autumn.queryOptional", selectArgument, null, tailArguments) + ";"
						: prelude + derivedOptional(
								fetch("this.autumn.queryOptional", selectArgument, projectionArgument, tailArguments));
				case COUNT -> prelude + "return this.autumn.count(" + countArguments + ");";
				case EXISTS -> prelude + "return this.autumn.count(" + countArguments + ") > 0;";
				case DELETE -> prelude + (query.returnTypeSource().equals("void") ? "" : "return ")
						+ "this.autumn.update(" + countArguments + ");";
				case DELETE_LIST -> prelude + projectionPrelude()
						+ "java.util.List<" + this.entity + "> rows = " + selectCall + ";\n"
						+ "if (!rows.isEmpty()) {\n\tthis.autumn.update(" + countArguments + ");\n}\n" + consumePending()
						+ "return rows;";
				case PAGE -> prelude + pagedBody(fetch("this.autumn.page",
						selectArgument + ", " + countSqlArgument,
						projectionArgument == null ? null : projectionArgument + ", " + countSqlArgument,
						", pageable" + tailArguments));
			};
		}

		/**
		 * 分页派生查询：末位 {@code Pageable} 提供页码与页大小，排序来自编译期映射好的 {@code ORDER BY}
		 * （方法名的 {@code OrderBy}），因此「分页 + 排序」组合无需运行期拼串。
		 * 关联装配与 {@code findAll(Pageable)} 一样受 {@code AssociationMode} 三态控制。
		 */
		private String pagedBody(String pageCall) {
			StringBuilder sb = new StringBuilder(projectionPrelude())
				.append(PAGE + "<" + this.entity + "> page = ")
				.append(pageCall)
				.append(";\n");
			if (eagerAssociations().isEmpty()) {
				return sb.append(consumePending()).append("return page;").toString();
			}
			sb.append("java.util.List<")
				.append(this.entity)
				.append("> rows = page.content();\n")
				.append(AUTUMN_DATA_CONTEXT + " __ctx = " + CONTEXT_HOLDER + ".get();\n")
				.append("boolean __cascade;\n")
				.append("switch (__ctx.mode()) {\n")
				.append("\tcase " + ASSOCIATION_MODE + ".CASCADE: __cascade = true; break;\n")
				.append("\tcase " + ASSOCIATION_MODE + ".DISABLED: __cascade = false; break;\n")
				.append("\tdefault: __cascade = pageable.size() <= __ctx.largePageThreshold();\n")
				.append("}\n")
				.append("if (__cascade) {\n");
			for (Association association : eagerAssociations()) {
				sb.append("\t").append(association.attachMethod()).append("(rows);\n");
			}
			sb.append("}\n")
				// 一次性上下文（级联模式 / 字段投影）被「最近一次」查询消费后自动清除，无需手动 clear
				.append(consumePending());
			return sb.append("return page;").toString();
		}

		/** 取数实参：{@code Containing/StartingWith/EndingWith} 这类按关键词把实参包成模式串；其余原样。 */
	private String valueExpressionFor(Binding binding) {
		return switch (binding.transform()) {
			case CONTAINS -> "\"" + "%" + "\" + " + binding.expression() + " + \"%" + "\"";
			case STARTS_WITH -> binding.expression() + " + \"%" + "\"";
			case ENDS_WITH -> "\"" + "%" + "\" + " + binding.expression();
			case NONE -> binding.expression();
		};
	}

		/** 单行结果：查到就按父主键装配一对多，查不到返回空 {@code Optional}；取完消费一次性上下文。 */
		private String derivedOptional(String queryCall) {
			String read = "java.util.Optional<" + this.entity + "> found = " + queryCall + ";\n";
			if (eagerAssociations().isEmpty()) {
				return projectionPrelude() + read + consumePending() + "return found;";
			}
			List<String> attach = eagerAssociations().stream()
				.map(association -> "\t" + association.attachMethod() + "(java.util.List.of(row));")
				.toList();
			return projectionPrelude() + read + "found.ifPresent(row -> {\n" + String.join("\n", attach)
					+ "\n});\n" + consumePending() + "return found;";
		}

		/** 把 SQL 文本转义成 Java 字符串字面量片段（仅处理会破坏字符串的字符）。 */
		private static String escapeJava(String value) {
			StringBuilder sb = new StringBuilder(value.length());
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				switch (c) {
					case '"' -> sb.append("\\\"");
					case '\\' -> sb.append("\\\\");
					case '\n' -> sb.append("\\n");
					case '\r' -> sb.append("\\r");
					case '\t' -> sb.append("\\t");
					default -> sb.append(c);
				}
			}
			return sb.toString();
		}

		/** 实体行映射器：逐列显式取值，无反射。 */
		private String rowMapperField(EntityMetadata meta, String fieldName, String type) {
			return "\tprivate static final " + ROW_MAPPER + '<' + type + "> " + fieldName
					+ " = (rs, rowNum) -> {\n" + indent(rowConstruction(meta)) + "\t};\n\n";
		}

		/** 子行映射器：把外键值和子实体成对带回，分组就靠这个键，子实体不必暴露外键字段。 */
		private String childRowMapperField(Association association) {
			String keyed = "java.util.Map.Entry<java.lang.Object, " + association.elementType() + ">";
			return "\tprivate static final " + ROW_MAPPER + '<' + keyed + "> " + association.mapperField()
					+ " = (rs, rowNum) -> new java.util.AbstractMap.SimpleEntry<>((java.lang.Object) rs.getObject(\""
					+ association.foreignKeyColumn() + "\", " + this.meta.idValueType() + ".class), "
					+ association.rowMethod() + "(rs));\n\n";
		}

		private String childRowMethod(Association association) {
			return "\tprivate static " + association.elementType() + " " + association.rowMethod()
					+ "(java.sql.ResultSet rs) throws java.sql.SQLException {\n"
					+ indent(rowConstruction(association.target())) + "\t}\n\n";
		}

		/** {@code ResultSet -> 实体} 的取值代码，父/子实体共用。 */
		private String rowConstruction(EntityMetadata meta) {
			List<String> lines = new ArrayList<>();
			lines.add(meta.qualifiedName() + " row = new " + meta.qualifiedName() + "();");
			for (Column column : meta.columns()) {
				lines.add("row." + column.setter() + "(" + readColumn(column) + ");");
			}
			lines.add("return row;");
			return String.join("\n", lines);
		}

		/** 基本类型用专用取值（NULL 退化为 0/false），其余走类型化 {@code getObject}。 */
		private String readColumn(Column column) {
			String columnLabel = '"' + column.column() + '"';
			switch (column.javaType()) {
				case "int" :
					return "rs.getInt(" + columnLabel + ")";
				case "long" :
					return "rs.getLong(" + columnLabel + ")";
				case "short" :
					return "rs.getShort(" + columnLabel + ")";
				case "byte" :
					return "rs.getByte(" + columnLabel + ")";
				case "double" :
					return "rs.getDouble(" + columnLabel + ")";
				case "float" :
					return "rs.getFloat(" + columnLabel + ")";
				case "boolean" :
					return "rs.getBoolean(" + columnLabel + ")";
				case "char" :
					return "rs.getString(" + columnLabel + ").charAt(0)";
				default :
					return "rs.getObject(" + columnLabel + ", " + column.javaType() + ".class)";
			}
		}

		/**
		 * 关联装配：两条语句中的第二条。一次把本次父主键对应的子行全部取回（行数受
		 * {@code associationLimit} 约束），按外键分组后写回各自集合，因此不会有 N+1。
		 */
		private String attachMethod(Association association) {
			String elementType = association.elementType();
			String idGetter = this.meta.id().getter();
			List<String> body = List.of("if (parents.isEmpty()) {",
					"\treturn;",
					"}",
					"java.util.List<Object> parentIds = new java.util.ArrayList<>(parents.size());",
					"for (" + this.entity + " parent : parents) {",
					"\tparentIds.add(parent." + idGetter + "());",
					"}",
					"java.util.Map<Object, java.util.List<" + elementType + ">> grouped = new java.util.HashMap<>();",
					"for (java.util.Map.Entry<Object, " + elementType + "> entry : this.autumn.queryLimited("
							+ SQL_HELPER + ".inClause(" + sqlConst(association.statementKey()) + ", parentIds.size()), "
							+ association.mapperField() + ", parentIds.toArray())) {",
					"\tgrouped.computeIfAbsent(entry.getKey(), key -> new java.util.ArrayList<>())"
							+ ".add(entry.getValue());",
					"}",
					"for (" + this.entity + " parent : parents) {",
					"\tjava.util.List<" + elementType + "> loaded = grouped.get(parent." + idGetter + "());",
					"\tparent." + association.setter() + "(" + collectionValue(association, "loaded") + ");",
					"}");
			return privateMethod(association.attachMethod(), "void", "java.util.List<" + this.entity + "> parents",
					String.join("\n", body));
		}

		/** 关联集合为空时给可变空集合；Set 形态从分组结果转换。 */
		private String collectionValue(Association association, String loaded) {
			String elementType = association.elementType();
			if (association.collectionType().equals("java.util.Set")) {
				return "(" + loaded + " == null) ? new java.util.LinkedHashSet<" + elementType + ">() : new"
						+ " java.util.LinkedHashSet<" + elementType + ">(" + loaded + ")";
			}
			return "(" + loaded + " == null) ? new java.util.ArrayList<" + elementType + ">() : " + loaded;
		}

		/**
		 * 多对一（单值）的行映射器：直接映射子实体，不需要像一对多那样把外键成对带回。
		 */
		private String childRowMapperFieldToOne(Association association) {
			return "\tprivate static final " + ROW_MAPPER + '<' + association.elementType() + "> " + association.mapperField()
					+ " = (rs, rowNum) -> " + association.rowMethod() + "(rs);\n\n";
		}

		/**
		 * 多对一装配：用父表上的外键值 {@code IN} 子表主键一次取回子实体，按子表主键建索引后挂回父表外键对应字段。
		 * 同样是两条语句、没有 {@code JOIN}，父表每一行最多挂一个子实体。
		 */
		private String attachToOneMethod(Association association) {
			String elementType = association.elementType();
			String foreignKeyGetter = association.foreignKeyGetter();
			String childIdGetter = association.target().id().getter();
			List<String> body = List.of("if (parents.isEmpty()) {",
					"\treturn;",
					"}",
					"java.util.List<Object> keys = new java.util.ArrayList<>(parents.size());",
					"for (" + this.entity + " parent : parents) {",
					"\tkeys.add(parent." + foreignKeyGetter + "());",
					"}",
					"java.util.Map<Object, " + elementType + "> byKey = new java.util.HashMap<>();",
					"for (" + elementType + " child : this.autumn.query(" + SQL_HELPER + ".inClause("
							+ sqlConst(association.statementKey()) + ", keys.size()), " + association.mapperField()
							+ ", keys.toArray())) {",
					"\tbyKey.put(child." + childIdGetter + "(), child);",
					"}",
					"for (" + this.entity + " parent : parents) {",
					"\tparent." + association.setter() + "(byKey.get(parent." + foreignKeyGetter + "()));",
					"}");
			return privateMethod(association.attachMethod(), "void", "java.util.List<" + this.entity + "> parents",
					String.join("\n", body));
		}

		private String constructor() {
			return "\tpublic " + this.implSimpleName + "(" + AUTUMN_JDBC + " autumn) {\n"
					+ "\t\tthis.autumn = autumn;\n" + "\t}\n\n";
		}

		/** 自增主键实体的判定：主键为空即新行，走 INSERT 并回填生成键。 */
		private String saveBody() {
			String update = updateCall();
			if (this.meta.id().generated()) {
				return "if (entity." + this.meta.id().getter() + "() == null) {\n" + "\tinsert(entity);\n"
						+ "}\nelse {\n\t" + update + "\n}\nreturn entity;";
			}
			return "if (" + trimUpdate(update) + " == 0) {\n" + "\tinsert(entity);\n" + "}\nreturn entity;";
		}

		private String updateCall() {
			return "this.autumn.update(" + sqlConst(CrudSqlGenerator.UPDATE) + ", "
					+ valueArguments(this.meta.updateColumns()) + ", entity." + this.meta.id().getter() + "());";
		}

		private String trimUpdate(String updateCall) {
			return updateCall.substring(0, updateCall.length() - 1);
		}

		/**
		 * 批量写：按主键是否为空分两拨 —— 新行走一条带生成键回流的批量 INSERT，已有行走一条批量 UPDATE；
		 * 主键非自增的实体无法从数据库取键，只能逐条 upsert。
		 */
		private String saveAllBody() {
			if (!this.meta.id().generated()) {
				return "java.util.List<S> saved = new java.util.ArrayList<>(entities.size());\n"
						+ "for (S entity : entities) {\n" + "\tsaved.add(save(entity));\n" + "}\n" + "return saved;";
			}
			List<String> body = List.of("java.util.List<S> inserts = new java.util.ArrayList<>();",
					"java.util.List<S> updates = new java.util.ArrayList<>();",
					"for (S entity : entities) {",
					"\tif (entity." + this.meta.id().getter() + "() == null) {",
					"\t\tinserts.add(entity);",
					"\t}",
					"\telse {",
					"\t\tupdates.add(entity);",
					"\t}",
					"}",
					"if (!inserts.isEmpty()) {",
					"\tjava.util.List<Object[]> rows = new java.util.ArrayList<>(inserts.size());",
					"\tfor (S entity : inserts) {",
					"\t\trows.add(new Object[] { " + valueArguments(this.meta.insertColumns()) + " });",
					"\t}",
					"\tjava.util.List<java.lang.Number> keys = this.autumn.batchInsertReturningKeys("
							+ sqlConst(CrudSqlGenerator.INSERT) + ", \"" + this.meta.id().column() + "\", rows);",
					"\t" + backfillKeys("inserts", "keys"),
					"}",
					"if (!updates.isEmpty()) {",
					"\tjava.util.List<Object[]> rows = new java.util.ArrayList<>(updates.size());",
					"\tfor (S entity : updates) {",
					"\t\trows.add(new Object[] { " + valueArguments(this.meta.updateColumns()) + ", entity."
							+ this.meta.id().getter() + "() });",
					"\t}",
					"\tthis.autumn.batchUpdate(" + sqlConst(CrudSqlGenerator.UPDATE) + ", rows);",
					"}",
					"return entities;");
			return String.join("\n", body);
		}

		/** 数据库按序回吐生成键时逐个回填；行数不吻合（驱动只给汇总键）就保持实体原样，不误填。 */
		private String backfillKeys(String targets, String keys) {
			String assign = assignGeneratedKey(targets + ".get(i)", keys + ".get(i)");
			if (assign == null) {
				return "// " + this.meta.id().javaType() + " is not assignable from a generated key, ids stay as-is";
			}
			return "if (" + keys + ".size() == " + targets + ".size()) {\n" + "\t\tfor (int i = 0; i < " + keys
					+ ".size(); i++) {\n" + "\t\t\t" + assign + "\n" + "\t\t}\n" + "\t}";
		}

		/** 单主键取数：与派生的 {@code Optional} 查询同一条装配路径（投影 + 级联 + 一次性上下文消费）。 */
		private String findByIdBody() {
			return derivedOptional(
					crudFetch("this.autumn.queryOptional", CrudSqlGenerator.SELECT_BY_ID, null, ", id"));
		}

		/** {@code List<Entity> rows = <query>; } 后逐条关联装配，最后消费一次性上下文；无关联时只取数与消费。 */
		private String loaded(String queryCall) {
			StringBuilder sb = new StringBuilder(projectionPrelude());
			sb.append("java.util.List<").append(this.entity).append("> rows = ").append(queryCall).append(";\n");
			for (Association association : eagerAssociations()) {
				sb.append(association.attachMethod()).append("(rows);\n");
			}
			sb.append(consumePending());
			return sb.append("return rows;").toString();
		}

		/**
		 * 分页装配：1:n / n:1 仍走两条语句（无 N+1），但按 {@link AssociationMode} 分级：
		 * <ul>
		 *     <li>DEFAULT：页大小 ≤ 阈值（默认 20）eager 加载关联；超过阈值不自动级联。</li>
		 *     <li>CASCADE：任何页大小强制级联。</li>
		 *     <li>DISABLED：任何页大小强制关闭关联（不查询）。</li>
		 * </ul>
		 * 模式经 {@link AutumnDataContextHolder#get()} 读取，一次性上下文被「最近一次」查询消费后自动清除。
		 */
		private String findAllPageableBody() {
			String countSql = sqlConst(CrudSqlGenerator.COUNT);
			String projectionConst = crudProjectionConst(CrudSqlGenerator.PAGE_ALL);
			return pagedBody(fetch("this.autumn.page", sqlConst(CrudSqlGenerator.PAGE_ALL) + ", " + countSql,
					projectionConst == null ? null : projectedColumns(projectionConst) + ", " + countSql,
					", pageable"));
		}

		private String findAllByIdBody() {
			String query = crudFetch("this.autumn.query", CrudSqlGenerator.SELECT_BY_IDS, "ids.size()",
					", ids.toArray()");
			return "if (ids.isEmpty()) {\n" + "\treturn java.util.List.of();\n" + "}\n" + loaded(query);
		}

		private String deleteAllByIdBody() {
			return "if (!ids.isEmpty()) {\n" + "\tthis.autumn.update(" + SQL_HELPER + ".inClause("
					+ sqlConst(CrudSqlGenerator.DELETE_BY_IDS) + ", ids.size()), ids.toArray());\n" + "}";
		}

		/** 批量删：一条 {@code IN} 语句，不再逐行 DELETE。 */
		private String deleteAllBody() {
			return "if (entities.isEmpty()) {\n" + "\treturn;\n" + "}\n"
					+ "java.util.List<Object> ids = new java.util.ArrayList<>(entities.size());\n"
					+ "for (" + this.entity + " entity : entities) {\n" + "\tids.add(entity." + this.meta.id().getter()
					+ "());\n" + "}\n" + "this.autumn.update(" + SQL_HELPER + ".inClause("
					+ sqlConst(CrudSqlGenerator.DELETE_BY_IDS) + ", ids.size()), ids.toArray());";
		}

		private String insertBody() {
			List<Column> columns = this.meta.insertColumns();
			if (!this.meta.id().generated()) {
				return "this.autumn.update(" + sqlConst(CrudSqlGenerator.INSERT) + ", " + valueArguments(columns)
						+ ");";
			}
			StringBuilder sb = new StringBuilder("java.lang.Number generatedKey = this.autumn.insertReturningKey(")
				.append(sqlConst(CrudSqlGenerator.INSERT))
				.append(", \"")
				.append(this.meta.id().column())
				.append("\", ")
				.append(valueArguments(columns))
				.append(");");
			String assign = assignGeneratedKey("entity", "generatedKey");
			if (assign != null) {
				sb.append("\nif (generatedKey != null) {\n").append("\t").append(assign).append("\n}");
			}
			return sb.toString();
		}

		/** 把数据库生成键写回实体主键；无法转换的主键类型返回 {@code null}，保留原值。 */
		private String assignGeneratedKey(String target, String keyExpression) {
			String number = switch (this.meta.id().javaType()) {
				case "long", "java.lang.Long" -> keyExpression + ".longValue()";
				case "int", "java.lang.Integer" -> keyExpression + ".intValue()";
				case "short", "java.lang.Short" -> keyExpression + ".shortValue()";
				case "java.math.BigInteger" -> "java.math.BigInteger.valueOf(" + keyExpression + ".longValue())";
				default -> null;
			};
			return (number == null) ? null : target + "." + this.meta.id().setter() + "(" + number + ");";
		}

		private String valueArguments(List<Column> columns) {
			List<String> arguments = new ArrayList<>(columns.size());
			for (Column column : columns) {
				arguments.add("entity." + column.getter() + "()");
			}
			return arguments.stream().collect(Collectors.joining(", "));
		}
	}
}
