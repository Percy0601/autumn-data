package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.sql.ResultSet;
import java.sql.SQLException;

import xyz.wewin.autumn.data.common.meta.MetaClass;

/**
 * 运行期生成的实体类对外暴露的「逐行读取器」。
 *
 * <p>生成的类里有一个 {@code public static final MetaEntityReader READER}，实现体内是
 * 逐列写死的 {@code rs.getObject(index)} + 直接 setter 调用，<b>没有反射、没有 Map 中转</b>。
 * 反射只发生在加载生成类时取这一次静态字段，之后每行数据都走编译好的字节码。</p>
 *
 * <p>这是「完全静态类」与「完全动态 Map」之间的分界：结构在运行期才知道，
 * 但一旦知道就立刻固化成静态调用，不需要逐行反射。</p>
 */
@FunctionalInterface
public interface MetaEntityReader {

	/** 从当前结果集行读出一个 {@link MetaClass}（实际类型是生成的实体子类）。 */
	MetaClass read(ResultSet rs) throws SQLException;
}
