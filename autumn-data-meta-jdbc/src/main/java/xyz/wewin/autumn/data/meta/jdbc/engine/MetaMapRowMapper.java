package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityColumns;

/**
 * 把「结果集列标签 → 值」装进 {@link Map} 的行映射器，供 {@code resultType="map"} 的命名语句使用。
 *
 * <p>为什么需要它：默认路径按<b>锚定表</b>的生成实体读列，跨表 JOIN 时结果里会出现锚定表没有的列
 * （{@code customer_name}、{@code total_amount} 之类），固定列的 READER 只能跳过它们。动态层的表结构
 * 是运行期才有的，无法像编译期那样预先生成一个 Java VO，所以跨表 / 聚合查询的返回值就是
 * {@code Map}：列标签即键，驱动给什么类型就带什么类型，不做臆测的强转。</p>
 *
 * <p>键统一转小写：H2 / Oracle 的元数据默认给大写标签，MySQL 习惯小写，降成一种形状后调用方不必
 * 关心方言。列顺序按 SELECT 的投影顺序保留（{@link LinkedHashMap}），JSON 输出稳定。</p>
 *
 * <p>时序类型做一次归一：{@code java.sql.Timestamp/Date/Time} → {@code java.time.LocalDateTime/LocalDate/LocalTime}，
 * 与生成实体读取同一列时的类型保持一致，序列化也不至于变成一串毫秒数。mapper 没有状态，
 * 可被多条语句、多个连接、多个线程共用。</p>
 */
final class MetaMapRowMapper implements RowMapper<Map<String, Object>> {

	/** 无状态，单例即可。 */
	static final MetaMapRowMapper INSTANCE = new MetaMapRowMapper();

	private MetaMapRowMapper() {
	}

	@Override
	public Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
		// MetaEntityColumns.index 已经把标签降成小写，这里直接沿用那份索引
		Map<String, Integer> labels = MetaEntityColumns.index(rs);
		Map<String, Object> row = new LinkedHashMap<>(Math.max(4, labels.size()));
		for (Map.Entry<String, Integer> label : labels.entrySet()) {
			row.put(label.getKey(), value(rs.getObject(label.getValue())));
		}
		return row;
	}

	/** JDBC 时序类型归一成 {@code java.time}；其它类型原样返回。 */
	private static Object value(Object raw) {
		if (raw instanceof java.sql.Timestamp timestamp) {
			return timestamp.toLocalDateTime();
		}
		if (raw instanceof java.sql.Date date) {
			return date.toLocalDate();
		}
		if (raw instanceof java.sql.Time time) {
			return time.toLocalTime();
		}
		return raw;
	}
}
