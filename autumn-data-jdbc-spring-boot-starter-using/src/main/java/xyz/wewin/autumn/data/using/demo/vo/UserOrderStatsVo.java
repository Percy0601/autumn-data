package xyz.wewin.autumn.data.using.demo.vo;

import java.math.BigDecimal;

/**
 * 自定义返回类型（VO）示例之二：{@code @Query} 聚合结果。
 *
 * <p>列标签按成员名推导：{@code userId} → {@code user_id}，{@code orderCount} → {@code order_count}，
 * {@code totalAmount} → {@code total_amount}；SQL 里的别名要与它对齐，或在该成员上写
 * {@code @Column(name = "…")} 显式指定。</p>
 */
public record UserOrderStatsVo(Long userId, long orderCount, BigDecimal totalAmount) {
}
