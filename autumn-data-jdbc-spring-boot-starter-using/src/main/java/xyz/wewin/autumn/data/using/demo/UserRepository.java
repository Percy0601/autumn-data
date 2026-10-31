package xyz.wewin.autumn.data.using.demo;

import xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository;
import xyz.wewin.autumn.data.jdbc.repository.CrudRepository;

/**
 * 使用方式一（约定）：接口上标 {@code @AutumnRepository} 并继承 {@code CrudRepository}，
 * 编译期由 autumn-data-jdbc-compiler 生成 {@code UserRepositoryImpl}。
 *
 * <p>下一步会在这里追加派生查询示例：{@code findByNameAndAge} 等
 * （约定见 docs/spring-data-derived-query.md）。</p>
 */
@AutumnRepository
public interface UserRepository extends CrudRepository<User, Long> {
}
