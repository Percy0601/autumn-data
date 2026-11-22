package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.util.List;
import java.util.function.Supplier;

import xyz.wewin.autumn.data.common.meta.MetaClass;

/**
 * 一条自定义结果形状语句在某一版列结构下对应的模型。
 *
 * <p>CODE 模式里 {@link #type()} 是运行期生成、javac 编译、内存加载的具体 {@link MetaClass} 子类，
 * 因此每一行都能直接回答 {@code row.getClass().getName()}、{@code row.getClass().getPackageName()}，
 * 也有具体类型的 getter；GENERIC 模式回退到 {@link MetaClass} 本身（包名/类名就是基类），
 * 但属性列表仍然带每列的类型描述。</p>
 *
 * @param type          行对象的 Java 类型
 * @param reader        逐行读取器：CODE 是生成类的静态 {@code read}，GENERIC 按序号写属性
 * @param factory       空行工厂
 * @param packageName   生成类所在包；GENERIC 为 {@code java.lang}
 * @param className     生成类简单名；GENERIC 为 {@code MetaClass}
 * @param qualifiedName 全限定类名
 * @param fingerprint   列形状指纹（列顺序、名字、JDBC 类型、可空）
 * @param columns       这一版的列描述
 */
public record MetaShapeModel(Class<? extends MetaClass> type, MetaEntityReader reader,
		Supplier<MetaClass> factory, String packageName, String className, String qualifiedName,
		String fingerprint, List<MetaShapeColumn> columns) {

	/** 每行对象的实际类名，便于调用方/诊断直接展示具体类型。 */
	public String rowType() {
		return this.qualifiedName;
	}
}
