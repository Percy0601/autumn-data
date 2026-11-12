package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 生成实体类的读取辅助：把结果集列标签建成「小写标签 → 列序号」索引。
 *
 * <p>命名语句（手写 Mapper 文件）常做投影，只 SELECT 部分列；按列名硬取会在缺列时抛
 * {@code SQLException}。索引让生成代码可以先判断列在不在，缺列就跳过、字段保持 null，
 * 与原先「按结果集列标签逐列收集」的行为一致。同时兼容 H2/Oracle 给大写列名、
 * MySQL 习惯写小写列名的差异。</p>
 */
public final class MetaEntityColumns {

	private MetaEntityColumns() {
	}

	public static Map<String, Integer> index(ResultSet rs) throws SQLException {
		ResultSetMetaData metaData = rs.getMetaData();
		Map<String, Integer> index = new LinkedHashMap<>();
		for (int i = 1; i <= metaData.getColumnCount(); i++) {
			String label = metaData.getColumnLabel(i);
			if (label != null) {
				index.putIfAbsent(label.toLowerCase(Locale.ROOT), i);
			}
		}
		return index;
	}
}
