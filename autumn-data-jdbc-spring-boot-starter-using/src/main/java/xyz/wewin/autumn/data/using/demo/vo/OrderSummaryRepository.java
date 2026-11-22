package xyz.wewin.autumn.data.using.demo.vo;

import java.util.List;
import java.util.Optional;

import xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository;
import xyz.wewin.autumn.data.jdbc.annotation.Query;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.repository.CrudRepository;
import xyz.wewin.autumn.data.using.demo.Order;

/**
 * 自定义返回类型（VO / DTO 投影）示例。
 *
 * <p>返回类型不再限定实体：{@code List<Vo>} / {@code Optional<Vo>} / {@code Page<Vo>} 都按 VO 处理。
 * 派生查询把 VO 成员名当实体字段名解析，{@code SELECT} 只取 VO 要的列（不再拖全表列）；
 * {@code @Query} 则按结果集列标签取（成员名转下划线，{@code @Column} 可覆盖）。两种形态都生成
 * 显式的构造 / setter 调用，运行期零反射、零列名匹配。</p>
 */
@AutumnRepository
public interface OrderSummaryRepository extends CrudRepository<Order, Long> {

	/** 派生查询 + record VO：只查 {@code order_no, amount}，排序仍由方法名编译期映射到列。 */
	List<OrderSummaryVo> findByUserIdOrderByAmountDesc(Long userId);

	/** 单行 VO：查不到返回 {@code Optional.empty()}；{@code First} 在运行期由方言拼 {@code LIMIT 1}。 */
	Optional<OrderSummaryVo> findFirstByUserIdOrderByAmountDesc(Long userId);

	/** 分页 VO：内容按 VO 映射，总数走同一 WHERE 的 {@code COUNT(*)}。 */
	Page<OrderSummaryVo> findByUserIdOrderByAmountDesc(Long userId, Pageable pageable);

	/**
	 * {@code @Query} + 聚合 VO：JOIN / GROUP BY 这类派生查询表达不了的 SQL 写在注解里，
	 * 结果按列标签映射——别名与成员名转下划线后的形态一致。
	 */
	@Query("SELECT user_id, COUNT(*) AS order_count, SUM(amount) AS total_amount"
			+ " FROM demo_order WHERE user_id = ? GROUP BY user_id")
	List<UserOrderStatsVo> statsByUser(Long userId);

}
