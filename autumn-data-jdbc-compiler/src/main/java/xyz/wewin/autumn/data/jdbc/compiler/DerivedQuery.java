package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.List;

/**
 * 一个派生查询方法在编译期的完整描述：解析好的 SQL、运行期绑定顺序、返回类型契约。
 *
 * <p>交给 {@link ImplementationSource} 渲染成生成类里的方法；SQL 与 CRUD 语句一样内联成
 * {@code private static final String} 常量，运行期不做任何方法名解析。</p>
 *
 * @param methodName 接口上的方法名，同时决定生成类里的 SQL 常量名
 * @param kind 结果形态，决定调用 {@code query / queryOptional / count}
 * @param returnTypeSource 返回类型的源码形态，用于生成方法签名
 * @param parameters 形参表，用于生成方法签名
 * @param bindings 占位符的取值来源，顺序与 SQL 里的 {@code ?} 严格一致
 * @param sql 解析出的完整语句，集合条件保留 {@code :ids} 标记由运行期展开
 */
record DerivedQuery(String methodName, DerivedQuery.Kind kind, String returnTypeSource, List<Parameter> parameters,
		List<DerivedQuery.Binding> bindings, String sql) {

	/** 生成方法的结果形态。 */
	enum Kind {

		/** 多行实体。 */
		LIST,

		/** 单行实体（0 或 1 条）。 */
		OPTIONAL,

		/** 计数。 */
		COUNT,

		/** 存在性判断。 */
		EXISTS
	}

	/**
	 * 一个占位符的绑定。
	 *
	 * @param expression 取值表达式，即接口方法里的形参名
	 * @param collectionValues 该参数的值是集合，运行期展开成 IN 列表
	 */
	record Binding(String expression, boolean collectionValues) {
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
