package xyz.wewin.autumn.data.using.demo;

import xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository;
import xyz.wewin.autumn.data.jdbc.repository.CrudRepository;

/**
 * 子表仓储：订单自身按 {@code userId} 存外键（父侧不级联，写订单走这里），
 * 供 {@code saveAll} 批量插入与分页示例使用。
 */
@AutumnRepository
public interface OrderRepository extends CrudRepository<Order, Long> {
}
