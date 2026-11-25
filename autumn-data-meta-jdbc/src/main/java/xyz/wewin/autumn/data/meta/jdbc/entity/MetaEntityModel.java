package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.util.function.Supplier;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;

/**
 * 一张表当前结构对应的实体模型：逐行读取器 + 空实体工厂。
 *
 * @param reader      逐行读取器：按列名把结果集写进通用 {@link MetaClass} 的属性列表
 * @param mapper      交给 {@code AutumnJdbc} 的 RowMapper，直接委托 reader
 * @param fingerprint 表结构指纹；指纹变化就会构建新的模型
 * @param factory     空实体工厂：造一份带当前列描述、无脏标记的 {@link MetaClass}
 */
public record MetaEntityModel(MetaEntityReader reader, RowMapper<MetaClass> mapper, String fingerprint,
		Supplier<MetaClass> factory) {

	public MetaEntityModel(MetaEntityReader reader, String fingerprint, Supplier<MetaClass> factory) {
		this(reader, (rs, rowNum) -> reader.read(rs), fingerprint, factory);
	}
}
