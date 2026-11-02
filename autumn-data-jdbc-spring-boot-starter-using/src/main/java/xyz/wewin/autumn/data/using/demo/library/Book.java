package xyz.wewin.autumn.data.using.demo.library;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * 图书馆演示：图书 —— 同时承载 <b>多对一</b>（指向作者）与 <b>一对多</b>（指向中间表）。
 *
 * <p>{@code author} 是<b>多对一</b>：按文档第 8 节，父表上必须有映射同一外键列的标量字段 {@code authorId}
 * 供第二条语句读外键，因此 {@code authorId} 与 {@code author} 都映射到 {@code author_id} 列。</p>
 *
 * <p>{@code bookCategories} 是<b>一对多</b>，指向中间表 {@code lib_book_category}；
 * 中间表再以多对一指向 {@link Category}，串起来就是 <b>n:1:n（多对多）</b>：
 * {@code Book → BookCategory → Category}。</p>
 */
@Entity
@Table(name = "lib_book")
public class Book {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 外键标量列：多对一加载时用它读外键值，与 {@code author} 指向同一列。 */
	@Column(name = "author_id")
	private Long authorId;

	/** String。 */
	private String title;

	/** String。 */
	private String isbn;

	/** BigDecimal。 */
	private BigDecimal price;

	/** 基本类型 int。 */
	private int pages;

	/** 包装类型 Integer（可空）。 */
	private Integer stock;

	/** 基本类型 long。 */
	private long soldCount;

	/** 包装类型 Long（可空）。 */
	private Long reviewCount;

	/** Boolean。 */
	private Boolean published;

	/** Date 家族（java.sql.Timestamp 即 java.util.Date 子类，JDBC 原生安全）。 */
	@Column(name = "published_at")
	private Timestamp publishedAt;

	/** 多对一：多本书属于一个作者。 */
	@ManyToOne
	@JoinColumn(name = "author_id")
	private Author author;

	/** 一对多：一本书打多个分类标签（经中间表）。 */
	@OneToMany
	@JoinColumn(name = "book_id")
	private List<BookCategory> bookCategories = new ArrayList<>();

	public Long getId() {
		return this.id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getAuthorId() {
		return this.authorId;
	}

	public void setAuthorId(Long authorId) {
		this.authorId = authorId;
	}

	public String getTitle() {
		return this.title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getIsbn() {
		return this.isbn;
	}

	public void setIsbn(String isbn) {
		this.isbn = isbn;
	}

	public BigDecimal getPrice() {
		return this.price;
	}

	public void setPrice(BigDecimal price) {
		this.price = price;
	}

	public int getPages() {
		return this.pages;
	}

	public void setPages(int pages) {
		this.pages = pages;
	}

	public Integer getStock() {
		return this.stock;
	}

	public void setStock(Integer stock) {
		this.stock = stock;
	}

	public long getSoldCount() {
		return this.soldCount;
	}

	public void setSoldCount(long soldCount) {
		this.soldCount = soldCount;
	}

	public Long getReviewCount() {
		return this.reviewCount;
	}

	public void setReviewCount(Long reviewCount) {
		this.reviewCount = reviewCount;
	}

	public Boolean getPublished() {
		return this.published;
	}

	public void setPublished(Boolean published) {
		this.published = published;
	}

	public Timestamp getPublishedAt() {
		return this.publishedAt;
	}

	public void setPublishedAt(Timestamp publishedAt) {
		this.publishedAt = publishedAt;
	}

	public Author getAuthor() {
		return this.author;
	}

	public void setAuthor(Author author) {
		this.author = author;
	}

	public List<BookCategory> getBookCategories() {
		return this.bookCategories;
	}

	public void setBookCategories(List<BookCategory> bookCategories) {
		this.bookCategories = bookCategories;
	}

}
