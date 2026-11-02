package xyz.wewin.autumn.data.jdbc.repository;

import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;

/**
 * 分页仓储接口：方法由 autumn-data-jdbc-compiler 编译期生成实现。
 *
 * <p>只暴露翻页，不含按业务字段排序 —— 排序字段需要在编译期映射成列名才安全，
 * 随派生查询一起提供（docs/spring-data-derived-query.md 第 6 节）。</p>
 *
 * <p>需要「分页 + 排序」时，在自己接口里声明返回 {@link Page} 且末位形参是 {@link Pageable} 的方法，
 * 排序由方法名的 {@code OrderBy} 编译期映射成列名，例如
 * {@code Page<Book> findAllOrderByPriceDesc(Pageable pageable)}。</p>
 *
 * @param <T> 实体类型
 * @param <ID> 主键类型
 */
public interface PagingRepository<T, ID> extends Repository<T, ID> {

	/** 按主键顺序翻第 {@code pageable.page()} 页，同时回查满足条件的总行数。 */
	Page<T> findAll(Pageable pageable);
}
