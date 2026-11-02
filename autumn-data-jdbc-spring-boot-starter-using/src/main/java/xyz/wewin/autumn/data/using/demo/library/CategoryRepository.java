package xyz.wewin.autumn.data.using.demo.library;

import java.util.List;

import xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository;
import xyz.wewin.autumn.data.jdbc.repository.CrudRepository;
import xyz.wewin.autumn.data.jdbc.repository.PagingRepository;

/** 分类仓储：n:1:n 多对多的另一端。 */
@AutumnRepository
public interface CategoryRepository extends CrudRepository<Category, Long>, PagingRepository<Category, Long> {

	List<Category> findByCode(String code);

	List<Category> findByNameStartingWith(String prefix);

	List<Category> findByEnabledIsTrue();

	List<Category> findByEnabledIsFalse();

	List<Category> findBySortOrderGreaterThan(int sortOrder);

	List<Category> findByDepthIsNull();

	List<Category> findAllOrderBySortOrderAsc();

	List<Category> findByEnabledIsTrueOrderBySortOrderDesc();

	long countByEnabledIsTrue();

	boolean existsByCode(String code);

}
