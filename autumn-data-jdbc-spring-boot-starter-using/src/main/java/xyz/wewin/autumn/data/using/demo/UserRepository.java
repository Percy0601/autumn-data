package xyz.wewin.autumn.data.using.demo;

import xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository;
import xyz.wewin.autumn.data.jdbc.repository.CrudRepository;
import xyz.wewin.autumn.data.jdbc.repository.PagingRepository;

/**
 * 使用方式一（约定）：接口上标 {@code @AutumnRepository} 并继承 {@code CrudRepository}，
 * 编译期由 autumn-data-jdbc-compiler 生成 {@code UserRepositoryImpl}。
 *
 * <p>多继承一个 {@code PagingRepository} 即可拿到 {@code findAll(Pageable)}：生成的实现按主键排序、
 * 用方言拼分页片段，并回查总行数。实体的 {@code orders} 是一对多，查询时按外键批量装配。</p>
 *
 * <p>下一步在这里追加派生查询示例：{@code findByNameAndAge} 等
 * （约定见 docs/spring-data-derived-query.md）。</p>
 */
@AutumnRepository
public interface UserRepository extends CrudRepository<User, Long>, PagingRepository<User, Long> {
}
