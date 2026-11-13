package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityColumns;

/**
 * 按 Mapper 里声明的 {@code <resultMap>} 把结果集映射成 {@link MetaClass}。
 *
 * <p>默认路径（没有 {@code resultMap}）读的是生成实体的 {@code READER}：列标签是元数据里的列名，
 * 逐列直写字段。一旦 SELECT 起了别名（{@code order_no AS no}）、或者列名和属性名对不上，
 * 固定列的 READER 就读不到。这里按映射逐列 {@code getObject}，再走生成类的
 * {@link MetaClass#set(String, Object)} 完成类型收口，所以列标签与属性名可以各自独立命名。</p>
 *
 * <p>逐行只调用生成类的空实体工厂（{@code NEW} 静态字段），列名到下标也只在行内建一次；
 * 真正的反射只发生在模型加载那一刻。</p>
 */
final class MetaMappedRowMapper implements RowMapper<MetaClass> {

	/** @param label 结果集里的列标签（小写）；@param property MetaClass 属性名（元数据列名） */
	record Column(String label, String property) {
		static Column of(String column, String property) {
			return new Column(column.toLowerCase(Locale.ROOT), property);
		}
	}

	private final Supplier<MetaClass> factory;

	private final List<Column> columns;

	MetaMappedRowMapper(Supplier<MetaClass> factory, List<Column> columns) {
		this.factory = factory;
		this.columns = List.copyOf(columns);
	}

	@Override
	public MetaClass mapRow(ResultSet rs, int rowNum) throws SQLException {
		MetaClass entity = this.factory.get();
		Map<String, Integer> labels = MetaEntityColumns.index(rs);
		for (Column column : this.columns) {
			Integer index = labels.get(column.label());
			if (index == null) {
				// SELECT 没投影这一列：属性保持 null，和「列存在但值为 null」区分开
				continue;
			}
			Object raw = rs.getObject(index);
			if (raw != null) {
				entity.set(column.property(), raw);
			}
		}
		// set 会标脏；读出来的行必须和 READER 读出来的一样是干净的，后续 update 只写用户改过的列
		entity.clearChanged();
		return entity;
	}
}
