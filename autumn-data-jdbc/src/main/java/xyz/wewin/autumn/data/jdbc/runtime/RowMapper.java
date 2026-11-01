package xyz.wewin.autumn.data.jdbc.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 结果集行映射（核心自有，签名对齐 {@code org.springframework.jdbc.core.RowMapper}，
 * 但零 Spring 依赖）。生成代码用它把 {@code ResultSet} 映射成实体。
 *
 * @param <T> 行类型
 */
@FunctionalInterface
public interface RowMapper<T> {

	/** 把当前行映射为一个对象。 */
	T mapRow(ResultSet rs, int rowNum) throws SQLException;
}
