package xyz.wewin.autumn.data.bench;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MyBatis / 原生 JDBC 侧的对照实体：普通 POJO，属性与列名一一对应。
 *
 * <p>刻意不继承任何框架类型，也不使用 Lombok：它就是 MyBatis 最常用的那种
 * {@code resultType} 目标类，赋值只能走 setter 反射。</p>
 */
public class BenchOrder {

	private Long id;

	private Long userId;

	private String orderNo;

	private BigDecimal amount;

	private Integer status;

	private LocalDateTime createTime;

	public Long getId() {
		return this.id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getUserId() {
		return this.userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getOrderNo() {
		return this.orderNo;
	}

	public void setOrderNo(String orderNo) {
		this.orderNo = orderNo;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public Integer getStatus() {
		return this.status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public LocalDateTime getCreateTime() {
		return this.createTime;
	}

	public void setCreateTime(LocalDateTime createTime) {
		this.createTime = createTime;
	}

}
