package xyz.wewin.autumn.data.using.demo.vo;

import java.math.BigDecimal;

/**
 * 自定义返回类型（VO）示例之一：<b>record 形态</b>。
 *
 * <p>record 组件按声明顺序走规范构造器：{@code orderNo} / {@code amount} 两个成员按实体字段名映射到
 * {@code order_no} / {@code amount} 两列，派生查询的 {@code SELECT} 也只取这两列。</p>
 */
public record OrderSummaryVo(String orderNo, BigDecimal amount) {
}
