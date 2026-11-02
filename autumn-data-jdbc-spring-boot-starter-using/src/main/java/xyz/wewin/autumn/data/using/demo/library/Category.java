package xyz.wewin.autumn.data.using.demo.library;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 图书馆演示：分类（多对多的一端，另一端是 {@link Book}，经 {@link BookCategory} 中间表关联）。
 */
@Entity
@Table(name = "lib_category")
public class Category {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** String。 */
	private String name;

	/** String。 */
	private String code;

	/** 基本类型 int。 */
	@jakarta.persistence.Column(name = "sort_order")
	private int sortOrder;

	/** 包装类型 Integer（可空）。 */
	private Integer depth;

	/** Boolean。 */
	private Boolean enabled;

	public Long getId() {
		return this.id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getCode() {
		return this.code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public int getSortOrder() {
		return this.sortOrder;
	}

	public void setSortOrder(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public Integer getDepth() {
		return this.depth;
	}

	public void setDepth(Integer depth) {
		this.depth = depth;
	}

	public Boolean getEnabled() {
		return this.enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}

}
