package xyz.wewin.autumn.data.using.demo.library;

import java.util.List;

import xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository;
import xyz.wewin.autumn.data.jdbc.repository.CrudRepository;
import xyz.wewin.autumn.data.jdbc.repository.PagingRepository;

/**
 * 图书-分类中间表仓储：查询时以它自己为根，因此它的<b>多对一</b>（{@code → Category}）会被装配。
 *
 * <p>这正好补上「以 Book 为根时子实体的多对一只用于查询路径、不生成装配」的那一半：
 * 想拿到分类实体，就以 {@code BookCategory} 为根查一次。</p>
 */
@AutumnRepository
public interface BookCategoryRepository extends CrudRepository<BookCategory, Long>,
		PagingRepository<BookCategory, Long> {

	List<BookCategory> findByBookId(Long bookId);

	List<BookCategory> findByBookIdIn(List<Long> bookIds);

	List<BookCategory> findByWeightGreaterThan(Integer weight);

	List<BookCategory> findAllOrderByWeightDesc();

	/** 多对一关联路径：BookCategory → Category。 */
	List<BookCategory> findByCategoryName(String name);

	List<BookCategory> findByCategoryCode(String code);

	List<BookCategory> findByCategoryEnabledIsTrue();

	long countByCategoryName(String name);

}
