package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;

/**
 * 一张表当前结构对应的「已编译实体」。
 *
 * @param entityType  运行期生成并加载的 {@link MetaClass} 子类
 * @param reader      生成类里的逐行读取器（零反射）
 * @param mapper      交给 {@code AutumnJdbc} 的 RowMapper，直接委托 reader
 * @param source      生成的 Java 源码（诊断 / 落盘 / 固化成静态类用）
 * @param fingerprint 表结构指纹；指纹变化就会生成新的类
 * @param qualifiedName 生成类的全限定名
 */
public record MetaEntityModel(Class<? extends MetaClass> entityType, MetaEntityReader reader,
		RowMapper<MetaClass> mapper, String source, String fingerprint, String qualifiedName) {

	public MetaEntityModel(Class<? extends MetaClass> entityType, MetaEntityReader reader, String source,
			String fingerprint, String qualifiedName) {
		this(entityType, reader, (rs, rowNum) -> reader.read(rs), source, fingerprint, qualifiedName);
	}
}
