package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.util.function.Supplier;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;

/**
 * 一张表当前结构对应的「实体模型」。
 *
 * @param entityType  实体类型：CODE 模式是运行期生成并加载的 {@link MetaClass} 子类；
 *                    GENERIC 模式恒为 {@link MetaClass} 本身
 * @param reader      逐行读取器：CODE 是生成类里的零反射读取，GENERIC 按列名写属性列表
 * @param mapper      交给 {@code AutumnJdbc} 的 RowMapper，直接委托 reader
 * @param source      生成的 Java 源码（诊断 / 落盘用）；GENERIC 模式为 {@code null}
 * @param fingerprint 表结构指纹；指纹变化就会构建新的模型
 * @param qualifiedName 实体全限定名；GENERIC 模式是 {@code MetaClass} 的类名
 * @param factory     空实体工厂；CODE 是生成类的 {@code NEW} 静态字段，GENERIC 是通用容器工厂
 */
public record MetaEntityModel(Class<? extends MetaClass> entityType, MetaEntityReader reader,
		RowMapper<MetaClass> mapper, String source, String fingerprint, String qualifiedName,
		Supplier<MetaClass> factory) {

	public MetaEntityModel(Class<? extends MetaClass> entityType, MetaEntityReader reader, String source,
			String fingerprint, String qualifiedName, Supplier<MetaClass> factory) {
		this(entityType, reader, (rs, rowNum) -> reader.read(rs), source, fingerprint, qualifiedName, factory);
	}
}
