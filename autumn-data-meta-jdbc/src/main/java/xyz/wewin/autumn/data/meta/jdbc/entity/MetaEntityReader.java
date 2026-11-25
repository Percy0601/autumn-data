package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.sql.ResultSet;
import java.sql.SQLException;

import xyz.wewin.autumn.data.common.meta.MetaClass;

/**
 * 当前表结构对应的逐行读取器：按列标签索引从结果集取值，写进通用 {@link MetaClass} 的属性列表。
 *
 * <p>实现体在注册表的 {@code build(reader)} 里一次性捕获列清单，之后每行只做
 * {@code rs.getObject(index)} + 按声明类型转换 + 直接写 {@code MetaProperty}，没有反射。</p>
 */
@FunctionalInterface
public interface MetaEntityReader {

	/** 从当前结果集行读出一个 {@link MetaClass}。 */
	MetaClass read(ResultSet rs) throws SQLException;
}
