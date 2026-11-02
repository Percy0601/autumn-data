package xyz.wewin.autumn.data.using.demo.library;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
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
 * 图书馆演示：作者（一对多的父表）。
 *
 * <p>字段刻意覆盖各类 Java 类型，用于验证编译期行映射与参数绑定：
 * {@code int / Integer / long / Long / String / Boolean / BigDecimal / Date} 各来一份。</p>
 *
 * <p>{@code books} 是<b>一对多</b>：编译期展开成「父查询 + 按 {@code author_id} 的 {@code IN} 子查询」两条语句，
 * 取回的子行按外键分组写回集合，行数受 {@code FetchPolicy.associationLimit()} 约束。</p>
 */
@Entity
@Table(name = "lib_author")
public class Author {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** String。 */
	private String name;

	/** String（可空，用于 IsNull / IsEmpty 类关键词）。 */
	private String email;

	/** 基本类型 int。 */
	private int age;

	/** 包装类型 Integer（可空，用于 IsNull / IsNotNull）。 */
	private Integer rating;

	/** 基本类型 long。 */
	private long bookCount;

	/** 包装类型 Long（可空）。 */
	private Long fansCount;

	/** Boolean（用于 IsTrue / IsFalse）。 */
	private Boolean active;

	/** BigDecimal。 */
	private BigDecimal royalty;

	/** Date（java.util.Date，用于 Before / After）。 */
	@Column(name = "birthday")
	private Date birthday;

	/** 一对多：一个作者多本书，子表 {@code lib_book.author_id} 指回本表主键。 */
	@OneToMany
	@JoinColumn(name = "author_id")
	private List<Book> books = new ArrayList<>();

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

	public String getEmail() {
		return this.email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public int getAge() {
		return this.age;
	}

	public void setAge(int age) {
		this.age = age;
	}

	public Integer getRating() {
		return this.rating;
	}

	public void setRating(Integer rating) {
		this.rating = rating;
	}

	public long getBookCount() {
		return this.bookCount;
	}

	public void setBookCount(long bookCount) {
		this.bookCount = bookCount;
	}

	public Long getFansCount() {
		return this.fansCount;
	}

	public void setFansCount(Long fansCount) {
		this.fansCount = fansCount;
	}

	public Boolean getActive() {
		return this.active;
	}

	public void setActive(Boolean active) {
		this.active = active;
	}

	public BigDecimal getRoyalty() {
		return this.royalty;
	}

	public void setRoyalty(BigDecimal royalty) {
		this.royalty = royalty;
	}

	public Date getBirthday() {
		return this.birthday;
	}

	public void setBirthday(Date birthday) {
		this.birthday = birthday;
	}

	public List<Book> getBooks() {
		return this.books;
	}

	public void setBooks(List<Book> books) {
		this.books = books;
	}

}
