package xyz.wewin.autumn.data.using.demo;

import java.math.BigDecimal;
import java.util.List;

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
 * <p>派生查询按 Spring Data 的命名约定在编译期解析成 SQL（约定见
 * docs/spring-data-derived-query.md）。条件既可以落在本表字段（{@code findByUsername}），
 * 也可以走一层关联路径（{@code findByOrdersOrderNo}）：后者编译期生成
 * {@code EXISTS (SELECT 1 FROM demo_order WHERE demo_order.user_id = basic_user.id AND …)}
 * 相关子查询，所以父行不因一对多重复，结果照样装配 {@code orders}。</p>
 */
@AutumnRepository
public interface UserRepository extends CrudRepository<User, Long>, PagingRepository<User, Long> {

    List<User> findByUsername(String username);

    /** 条件落在子表：查「有某个单号的用户」。 */
    List<User> findByOrdersOrderNo(String orderNo);

    /** 关联路径同样支持关键词与多条件组合。 */
    List<User> findByOrdersAmountGreaterThanAndUsernameIsNotNull(BigDecimal amount);

    /** 关联路径 + count：统计「下过单的用户数」。 */
    long countByOrdersOrderNoIsNotNull();

}
