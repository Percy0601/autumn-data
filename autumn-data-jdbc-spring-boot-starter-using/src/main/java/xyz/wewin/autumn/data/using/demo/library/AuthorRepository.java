package xyz.wewin.autumn.data.using.demo.library;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.repository.CrudRepository;
import xyz.wewin.autumn.data.jdbc.repository.PagingRepository;

/**
 * 作者仓储：集中演示文档第 2 章（Subject 前缀）与第 3 章（关键词）的派生查询。
 *
 * <p>方法名在编译期被解析成 SQL 常量内联进 {@code AuthorRepositoryImpl}，运行期零解析、零反射；
 * 解析失败（属性不存在、参数个数不符、返回类型不符）直接在方法声明处报编译错误。</p>
 *
 * <p>覆盖约定见 docs/spring-data-derived-query.md：
 * 第 2 节主语、第 3 节关键词表、第 4 节 And/Or、第 6 节 OrderBy、第 8 节关联路径。</p>
 */
@AutumnRepository
public interface AuthorRepository extends CrudRepository<Author, Long>, PagingRepository<Author, Long> {

	// ======================================================================
	// 第 2 章：Subject 前缀（查询动词）
	// ======================================================================

	List<Author> findByName(String name);

	List<Author> readByName(String name);

	List<Author> getByName(String name);

	List<Author> queryByName(String name);

	List<Author> searchByName(String name);

	/** {@code stream} 动词被识别，但首期按 {@code List} 返回（尚未支持 {@code Stream<T>}）。 */
	List<Author> streamByName(String name);

	/** {@code All} 是可选中缀：等价于无额外谓词。 */
	List<Author> findAllByActiveIsTrue();

	// ======================================================================
	// 第 2 章：Distinct / First / Top N
	// ======================================================================

	List<Author> findDistinctByName(String name);

	Optional<Author> findFirstByOrderByAgeDesc();

	List<Author> findTop3ByOrderByAgeDesc();

	List<Author> findFirst2ByActiveIsTrueOrderByNameAsc();

	// ======================================================================
	// 第 2 章：count / exists / delete / remove
	// ======================================================================

	long countByActiveIsTrue();

	long countByAgeGreaterThan(int age);

	boolean existsByName(String name);

	/** 返回删除行数。 */
	long deleteByName(String name);

	/** 返回被删实体：先 SELECT 取回、再 DELETE，两条语句共用同一 WHERE。 */
	List<Author> removeByActiveIsFalse();

	// ======================================================================
	// 第 3 章：关键词（PropertyKeyword）
	// ======================================================================

	List<Author> findByAgeBetween(int min, int max);

	List<Author> findByRatingIsNull();

	List<Author> findByRatingIsNotNull();

	List<Author> findByAgeLessThan(int age);

	List<Author> findByAgeLessThanEqual(int age);

	List<Author> findByAgeGreaterThan(int age);

	List<Author> findByAgeGreaterThanEqual(int age);

	List<Author> findByBirthdayBefore(Date date);

	List<Author> findByBirthdayAfter(Date date);

	List<Author> findByNameLike(String pattern);

	List<Author> findByNameNotLike(String pattern);

	List<Author> findByNameStartingWith(String prefix);

	List<Author> findByNameEndingWith(String suffix);

	List<Author> findByEmailIsEmpty();

	List<Author> findByEmailIsNotEmpty();

	List<Author> findByNameContaining(String keyword);

	List<Author> findByNameNotContaining(String keyword);

	/** 一次查询只允许一个 IN（运行期展开占位符）。 */
	List<Author> findByAgeIn(List<Integer> ages);

	List<Author> findByAgeNotIn(List<Integer> ages);

	List<Author> findByActiveIsTrue();

	List<Author> findByActiveIsFalse();

	List<Author> findByNameNot(String name);

	/**
	 * 等值既可以用裸属性名（{@code findByName}），也可以用 {@code Equals} 后缀。
	 * 注意：关键字表里没有单独的 {@code Is} 后缀（{@code findByNameIs} 会报「unknown property」），
	 * 这是当前实现与文档第 3 节 {@code Is / Equals} 描述的差异点。
	 */
	List<Author> findByNameEquals(String name);

	List<Author> findByNameIgnoreCase(String name);

	// ======================================================================
	// 第 4 章：And / Or（And 优先级高于 Or）
	// ======================================================================

	List<Author> findByNameAndAge(String name, int age);

	List<Author> findByNameOrEmail(String name, String email);

	/** {@code (name = ? AND age = ?) OR email = ?} */
	List<Author> findByNameAndAgeOrEmail(String name, int age, String email);

	// ======================================================================
	// 第 6 章：排序（OrderBy … Asc/Desc）
	// ======================================================================

	List<Author> findByActiveIsTrueOrderByAgeDesc();

	/** 只有排序、没有条件的方法名同样支持。 */
	List<Author> findAllOrderByAgeDesc();

	// ======================================================================
	// 分页 + 排序：Pageable 给页码与页大小，OrderBy 给编译期映射好的排序列
	// （返回 Page<T> 且末位形参是 Pageable 即触发）
	// ======================================================================

	Page<Author> findAllOrderByAgeDesc(Pageable pageable);

	Page<Author> findByActiveIsTrueOrderByAgeDesc(Pageable pageable);

	Page<Author> findByAgeGreaterThanOrderByAgeDescNameAsc(int age, Pageable pageable);

	List<Author> findByActiveOrderByAgeAscNameDesc(Boolean active);

	// ======================================================================
	// 第 8 章：一对多关联路径（Author → Book，编译期展开成 EXISTS 半连接）
	// ======================================================================

	List<Author> findByBooksTitle(String title);

	List<Author> findByBooksPriceGreaterThan(BigDecimal price);

	long countByBooksTitleIsNotNull();

}
