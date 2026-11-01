package xyz.wewin.autumn.data.using.demo;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * 使用方示例实体：单表 basic_user + 一对多 demo_order。
 *
 * <p>{@code @OneToMany} 由编译期展开成「父查询 + 按 {@code user_id} 的 {@code IN} 子查询」两条语句，
 * 取回的子行按外键分组写回 {@code orders}；子表行数受 {@code autumn.data.jdbc.association-limit}
 * （默认 1000）约束。写 {@code fetch = FetchType.LAZY} 则不自动装配。</p>
 */
@Entity
@Table(name = "basic_user")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String username;

	@Column(name = "email")
	private String email;

	@OneToMany
	@JoinColumn(name = "user_id")
	private List<Order> orders = new ArrayList<>();

	public Long getId() {
		return this.id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getUsername() {
		return this.username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getEmail() {
		return this.email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public List<Order> getOrders() {
		return this.orders;
	}

	public void setOrders(List<Order> orders) {
		this.orders = orders;
	}

}
