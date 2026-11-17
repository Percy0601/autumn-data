package xyz.wewin.autumn.data.bench;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * MyBatis 侧的 Mapper 接口：语句写在 {@code mapper/BenchMapper.xml}，是最常见的用法。
 */
public interface BenchMapper {

	BenchOrder selectOne(@Param("id") long id);

	List<BenchOrder> selectList(@Param("limit") int limit);

	int insert(BenchOrder order);

}
