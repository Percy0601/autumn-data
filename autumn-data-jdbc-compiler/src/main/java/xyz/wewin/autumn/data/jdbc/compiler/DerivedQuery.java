package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.List;

/**
 * 一个派生查询方法在编译期的完整描述：解析好的 SQL、运行期绑定顺序、返回类型契约。
 *
 * <p>交给 {@link ImplementationSource} 渲染成生成类里的方法；SQL 与 CRUD 语句一样内联成
 * {@code private static final String} 常量，运行期不做任何方法名解析。</p>
 *
 * @param methodName 接口上的方法名，同时决定生成类里的 SQL 常量名
 * @param kind 结果形态，决定调用 {@code query / queryOptional / count / update}
 * @param returnTypeSource 返回类型的源码形态，用于生成方法签名
 * @param parameters 形参表，用于生成方法签名
 * @param bindings 占位符的取值来源，顺序与 SQL 里的 {@code ?} 严格一致
 * @param sql 解析出的主语句：{@code SELECT}/{@code COUNT(*)} 或 {@code deleteBy} 的 {@code DELETE}
 * @param projectionSql 与主语句同形的<em>投影模板</em>：列清单换成 {@code {cols}} 标记，运行期设了字段投影才用；
 *            计数/存在性/删除语句与 {@code @Query} 手写 SQL 不裁剪列，此字段为空串
 * @param countSql 仅 {@link Kind#PAGE} 用：同一 WHERE 的计数语句（不带 ORDER BY），其余形态为空串
 * @param selectSql 仅 {@code deleteBy} 返回实体列表时用：先按条件 {@code SELECT} 把要删的行取回再删，
 *            其余形态为空串
 * @param distinct 主语是否带 {@code Distinct}
 * @param limit {@code First/Top} 施加的行数上限（{@code 0} 表示不限制，由方言在运行期拼 {@code LIMIT}）
 * @param custom 是否来自 {@code @Query}：是则跳过一对多装配（自定义 SQL 未必选了外键列）
 */
record DerivedQuery(String methodName, DerivedQuery.Kind kind, String returnTypeSource, List<Parameter> parameters,
		List<DerivedQuery.Binding> bindings, String sql, String projectionSql, String countSql, String selectSql,
		boolean distinct, long limit, boolean custom) {

	/** 非分页查询：没有独立的计数语句。 */
	DerivedQuery(String methodName, Kind kind, String returnTypeSource, List<Parameter> parameters,
			List<Binding> bindings, String sql, String selectSql, boolean distinct, long limit, boolean custom) {
		this(methodName, kind, returnTypeSource, parameters, bindings, sql, "", "", selectSql, distinct, limit,
				custom);
	}

	/** 该查询取实体行的语句常量名后缀：有投影模板时才生成，运行期用它替换 {@code {cols}}。 */
	boolean projectable() {
		return !this.projectionSql.isEmpty();
	}

	/** 生成方法的结果形态。 */
	enum Kind {

		/** 多行实体。 */
		LIST,

		/** 单行实体（0 或 1 条）。 */
		OPTIONAL,

		/** 计数。 */
		COUNT,

	/** 存在性判断。 */
	EXISTS,

	/** 删除并返因删除条数（{@code long}）。 */
	DELETE,

	/** 删除并返回被删实体（{@code List<T>}）。 */
	DELETE_LIST,

	/**
	 * 分页：末位形参是 {@code Pageable}，返回 {@code Page<T>}。
	 * 排序仍由方法名的 {@code OrderBy} 在编译期映射成列名，因此兼具分页与排序且无拼串注入风险。
	 */
	PAGE
	}

	/**
	 * 取数时对实参做的 LIKE 模式包裹：{@code Containing/StartsWith/EndsWith} 一类在运行期把实参包成
	 * {@code %v%} / {@code v%} / {@code %v}，其余原样传参。
	 */
	enum Transform {

		/** 原样传参。 */
		NONE,

		/** 包裹为 {@code %value%}（Containing/Contains）。 */
		CONTAINS,

		/** 包裹为 {@code value%}（StartingWith/StartsWith）。 */
		STARTS_WITH,

		/** 包裹为 {@code %value}（EndingWith/EndsWith）。 */
		ENDS_WITH
	}

	/**
	 * 一个占位符的绑定。
	 *
	 * @param expression 取值表达式，即接口方法里的形参名
	 * @param collectionValues 该参数的值是集合，运行期展开成 IN 列表
	 * @param transform 取值后是否做 LIKE 模式包裹
	 */
	record Binding(String expression, boolean collectionValues, Transform transform) {
	}

	/**
	 * 接口方法的一个形参。
	 *
	 * @param typeSource 类型的源码形态（全限定名，含类型实参）
	 * @param name 参数名
	 * @param collection 类型是否为 {@code java.util.Collection} 的子型，由处理器用 {@code Types} 精确判定
	 */
	record Parameter(String typeSource, String name, boolean collection) {
	}
}
