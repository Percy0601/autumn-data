package xyz.wewin.autumn.data.using.demo.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 图书馆演示：图书-分类中间表 —— <b>n:1:n（多对多）</b>的落地形态。
 *
 * <p>它是 {@link Book} 一对多的子表（{@code book_id}），自身又以<b>多对一</b>指向 {@link Category}
 * （{@code category_id}），于是 {@code Book → BookCategory → Category} 构成多对多。
 * 这样可以在不做 {@code @ManyToMany} 装配的前提下完整表达 n:n，并支持两段关联路径查询。</p>
 *
 * <p>多对一按文档第 8 节要求保留标量外键字段 {@code categoryId}（与 {@code category} 同列）。</p>
 */
@Entity
@Table(name = "lib_book_category")
public class BookCategory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 指向 {@code lib_book.id} 的外键（父侧一对多用它分组）。 */
	@Column(name = "book_id")
	private Long bookId;

	/** 指向 {@code lib_category.id} 的外键标量列。 */
	@Column(name = "category_id")
	private Long categoryId;

	/** 包装类型 Integer（可空）：在该分类下的权重。 */
	private Integer weight;

	/** 多对一：一条中间记录对应一个分类。 */
	@ManyToOne
	@JoinColumn(name = "category_id")
	private Category category;

	public Long getId() {
		return this.id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getBookId() {
		return this.bookId;
	}

	public void setBookId(Long bookId) {
		this.bookId = bookId;
	}

	public Long getCategoryId() {
		return this.categoryId;
	}

	public void setCategoryId(Long categoryId) {
		this.categoryId = categoryId;
	}

	public Integer getWeight() {
		return this.weight;
	}

	public void setWeight(Integer weight) {
		this.weight = weight;
	}

	public Category getCategory() {
		return this.category;
	}

	public void setCategory(Category category) {
		this.category = category;
	}

}
