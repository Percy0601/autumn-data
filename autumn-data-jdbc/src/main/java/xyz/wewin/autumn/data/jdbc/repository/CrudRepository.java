package xyz.wewin.autumn.data.jdbc.repository;

import java.util.List;
import java.util.Optional;

/**
 * 标准 CRUD 仓储接口，方法清单与 Spring Data {@code CrudRepository} 对齐
 * （见 docs/spring-data-derived-query.md 第 7 节；Iterable 收紧为 List）。
 *
 * <p>实现由 autumn-data-jdbc-compiler 在编译期生成，运行时零反射。</p>
 *
 * @param <T> 实体类型
 * @param <ID> 主键类型
 */
public interface CrudRepository<T, ID> extends Repository<T, ID> {

	<S extends T> S save(S entity);

	<S extends T> List<S> saveAll(List<S> entities);

	Optional<T> findById(ID id);

	boolean existsById(ID id);

	List<T> findAll();

	List<T> findAllById(List<ID> ids);

	long count();

	void deleteById(ID id);

	void delete(T entity);

	void deleteAllById(List<? extends ID> ids);

	void deleteAll(List<? extends T> entities);

	void deleteAll();
}
