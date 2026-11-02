package xyz.wewin.autumn.data.using.demo.library;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.repository.CrudRepository;
import xyz.wewin.autumn.data.jdbc.repository.PagingRepository;

/**
 * 图书仓储：同时演示 <b>多对一</b>（{@code Book → Author}）与 <b>n:1:n 多对多</b>
 * （{@code Book → BookCategory → Category}）两种关联路径，以及 BigDecimal / Boolean / Date 上的关键词。
 *
 * <p>关联条件一律编译期展开成相关子查询 {@code EXISTS (SELECT 1 FROM … WHERE …)}：
 * 一对多的连接方向是 {@code 子表.外键 = 父表.主键}，多对一是 {@code 子表.主键 = 父表.外键}；
 * 因此父行不会因一对多重复，也不需要 DISTINCT。</p>
 */
@AutumnRepository
public interface BookRepository extends CrudRepository<Book, Long>, PagingRepository<Book, Long> {

	// ======================================================================
	// 本表字段上的关键词
	// ======================================================================

	List<Book> findByTitle(String title);

	List<Book> findByTitleContaining(String keyword);

	List<Book> findByTitleStartingWith(String prefix);

	List<Book> findByPriceBetween(BigDecimal min, BigDecimal max);

	List<Book> findByPriceGreaterThanEqual(BigDecimal price);

	List<Book> findByPriceLessThan(BigDecimal price);

	/** Integer 可空列：库存未录入的书。 */
	List<Book> findByStockIsNull();

	List<Book> findByStockGreaterThan(Integer stock);

	List<Book> findByPublishedIsTrue();

	List<Book> findByPublishedIsFalse();

	List<Book> findByPublishedAtBefore(Timestamp timestamp);

	List<Book> findByPublishedAtIsNull();

	List<Book> findByPagesGreaterThan(int pages);

	List<Book> findBySoldCountGreaterThanEqual(long soldCount);

	List<Book> findByReviewCountIsNull();

	List<Book> findByIsbnIn(List<String> isbns);

	// ======================================================================
	// 第 2 章形态修饰与排序
	// ======================================================================

	Optional<Book> findFirstByOrderByPriceDesc();

	List<Book> findTop3ByPublishedIsTrueOrderBySoldCountDesc();

	List<Book> findAllOrderByPriceDesc();

	List<Book> findByPublishedIsTrueOrderByPriceAscTitleDesc();

	// ======================================================================
	// 分页 + 排序：Pageable 给页码与页大小，OrderBy 给编译期映射好的排序列
	// ======================================================================

	Page<Book> findAllOrderByPriceDesc(Pageable pageable);

	Page<Book> findByPublishedIsTrueOrderByPriceDesc(Pageable pageable);

	Page<Book> findByAuthorIdOrderBySoldCountDesc(Long authorId, Pageable pageable);

	/** 多对一关联路径 + 分页排序：按作者名过滤、按价格倒序翻页。 */
	Page<Book> findByAuthorNameOrderByPriceDesc(String name, Pageable pageable);

	long countByPublishedIsTrue();

	boolean existsByIsbn(String isbn);

	long deleteByIsbn(String isbn);

	// ======================================================================
	// 多对一关联路径：Book → Author（连接方向 子表.主键 = 父表.外键）
	// ======================================================================

	List<Book> findByAuthorName(String name);

	List<Book> findByAuthorAgeGreaterThan(int age);

	List<Book> findByAuthorActiveIsTrueOrderByPriceDesc();

	long countByAuthorName(String name);

	// ======================================================================
	// n:1:n 多对多关联路径：Book → BookCategory → Category（两层 EXISTS 嵌套）
	// ======================================================================

	List<Book> findByBookCategoriesCategoryName(String name);

	List<Book> findByBookCategoriesCategoryCode(String code);

	List<Book> findByBookCategoriesCategoryEnabledIsTrue();

	long countByBookCategoriesCategoryName(String name);

}
