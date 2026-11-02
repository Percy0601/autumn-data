package xyz.wewin.autumn.data.using.demo.controller;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AssociationMode;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnDataContext;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnDataContextHolder;
import xyz.wewin.autumn.data.using.demo.library.Author;
import xyz.wewin.autumn.data.using.demo.library.AuthorRepository;
import xyz.wewin.autumn.data.using.demo.library.Book;
import xyz.wewin.autumn.data.using.demo.library.BookCategory;
import xyz.wewin.autumn.data.using.demo.library.BookCategoryRepository;
import xyz.wewin.autumn.data.using.demo.library.BookRepository;
import xyz.wewin.autumn.data.using.demo.library.Category;
import xyz.wewin.autumn.data.using.demo.library.CategoryRepository;

/**
 * 图书馆演示控制器：把 1:n / n:1 / n:1:n 三种关联、分页、排序、关键词查询与批量保存都暴露成 HTTP 接口，
 * 便于手工验证（对应 docs/spring-data-derived-query.md 第 2、3、6、8 节）。
 *
 * <p>启动：{@code mvn spring-boot:run -Dspring-boot.run.profiles=demo}（连本地 H2）。</p>
 */
@RestController
@RequestMapping("/library")
public class LibraryController {

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	private final AuthorRepository authorRepository;

	private final BookRepository bookRepository;

	private final CategoryRepository categoryRepository;

	private final BookCategoryRepository bookCategoryRepository;

	public LibraryController(AuthorRepository authorRepository, BookRepository bookRepository,
			CategoryRepository categoryRepository, BookCategoryRepository bookCategoryRepository) {
		this.authorRepository = authorRepository;
		this.bookRepository = bookRepository;
		this.categoryRepository = categoryRepository;
		this.bookCategoryRepository = bookCategoryRepository;
	}

	// ==================================================================
	// 关联：1:n（作者 → 书）、n:1（书 → 作者）、n:1:n（书 → 中间表 → 分类）
	// ==================================================================

	/** 一对多：作者连同其书籍一起装配（两条语句，无 N+1）。 */
	@GetMapping("/authors")
	public List<Author> authors() {
		List<Author> authors = this.authorRepository.findAll();
		authors.forEach(author -> this.log.info("author {} has {} books", author.getName(), author.getBooks().size()));
		return authors;
	}

	/** 多对一 + 一对多同时装配：每本书带出作者，也带出中间表记录。 */
	@GetMapping("/books")
	public List<Book> books() {
		List<Book> books = this.bookRepository.findAll();
		books.forEach(book -> this.log.info("book {} -> author {}, categories {}",
				book.getTitle(), (book.getAuthor() == null) ? null : book.getAuthor().getName(),
				book.getBookCategories().size()));
		return books;
	}

	/**
	 * n:1:n（多对多）：以中间表为根查询，多对一会被装配，于是拿到完整的「书-分类」对应关系。
	 * 这是拿分类实体的推荐姿势 —— 以 Book 为根时，子实体的多对一只用于查询路径、不生成装配。
	 */
	@GetMapping("/book-categories")
	public List<BookCategory> bookCategories(@RequestParam(required = false) Long bookId) {
		List<BookCategory> rows = (bookId == null) ? this.bookCategoryRepository.findAll()
				: this.bookCategoryRepository.findByBookId(bookId);
		rows.forEach(row -> this.log.info("book {} -> category {}", row.getBookId(),
				(row.getCategory() == null) ? null : row.getCategory().getName()));
		return rows;
	}

	/** n:1:n 关联路径查询：按分类名反查图书（两层 EXISTS 嵌套，父行不重复）。 */
	@GetMapping("/books/by-category")
	public List<Book> booksByCategory(@RequestParam String categoryName) {
		return this.bookRepository.findByBookCategoriesCategoryName(categoryName);
	}

	/** 多对一关联路径查询：按作者名查书。 */
	@GetMapping("/books/by-author")
	public List<Book> booksByAuthor(@RequestParam String authorName) {
		return this.bookRepository.findByAuthorName(authorName);
	}

	// ==================================================================
	// 分页（Pageable）与排序（OrderBy…Asc/Desc）
	// ==================================================================

	/** 分页：按主键顺序翻页并回查总行数。 */
	@GetMapping("/books/page")
	public Page<Book> booksPage(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "3") int size) {
		Page<Book> result = this.bookRepository.findAll(Pageable.of(page, size));
		this.log.info("page {} size {} total {} totalPages {}", page, size, result.total(), result.totalPages());
		return result;
	}

	/**
	 * 分页 + 关联加载三态：{@code mode} 取值 0 DEFAULT / 1 CASCADE / 2 DISABLED。
	 * 一次性上下文只作用于这一次查询，执行后由生成代码自动清除，无需手动 clear。
	 */
	@GetMapping("/books/page/mode")
	public Page<Book> booksPageWithMode(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "3") int size,
			@RequestParam(defaultValue = "0") int mode) {
		AutumnDataContextHolder.startContext(new AutumnDataContext(AssociationMode.of(mode), 20));
		return this.bookRepository.findAll(Pageable.of(page, size));
	}

	/** 排序：OrderBy 编译期映射成列名（不走用户输入拼串），多属性按声明顺序。 */
	@GetMapping("/books/sorted")
	public List<Book> booksSorted(@RequestParam(defaultValue = "price") String by) {
		return switch (by) {
			case "price" -> this.bookRepository.findAllOrderByPriceDesc();
			case "sold" -> this.bookRepository.findTop3ByPublishedIsTrueOrderBySoldCountDesc();
			case "priceTitle" -> this.bookRepository.findByPublishedIsTrueOrderByPriceAscTitleDesc();
			default -> this.bookRepository.findAll();
		};
	}

	@GetMapping("/authors/sorted")
	public List<Author> authorsSorted(@RequestParam(defaultValue = "age") String by) {
		return switch (by) {
			case "age" -> this.authorRepository.findAllOrderByAgeDesc();
			case "active" -> this.authorRepository.findByActiveIsTrueOrderByAgeDesc();
			default -> this.authorRepository.findByActiveOrderByAgeAscNameDesc(true);
		};
	}

	@GetMapping("/authors/page")
	public Page<Author> authorsPage(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "2") int size) {
		return this.authorRepository.findAll(Pageable.of(page, size));
	}

	/**
	 * 分页 + 排序：返回 {@code Page<T>} 且末位形参是 {@code Pageable} 即触发。
	 * 排序列来自方法名的 {@code OrderBy}（编译期映射），页码与页大小来自 {@code Pageable}，二者组合仍无拼串风险。
	 */
	@GetMapping("/books/page-sorted")
	public Page<Book> booksPageSorted(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "2") int size,
			@RequestParam(required = false) Long authorId) {
		Pageable pageable = Pageable.of(page, size);
		Page<Book> result = (authorId == null) ? this.bookRepository.findAllOrderByPriceDesc(pageable)
				: this.bookRepository.findByAuthorIdOrderBySoldCountDesc(authorId, pageable);
		this.log.info("page {} size {} total {} totalPages {}", page, size, result.total(), result.totalPages());
		return result;
	}

	// ==================================================================
	// 第 2 章：Subject 前缀 / Distinct / First-Top / count / exists
	// ==================================================================

	@GetMapping("/derived/verbs")
	public List<Author> verbs(@RequestParam String name) {
		this.log.info("find={} read={} get={} query={} search={} stream={}",
				this.authorRepository.findByName(name).size(), this.authorRepository.readByName(name).size(),
				this.authorRepository.getByName(name).size(), this.authorRepository.queryByName(name).size(),
				this.authorRepository.searchByName(name).size(), this.authorRepository.streamByName(name).size());
		return this.authorRepository.findByName(name);
	}

	@GetMapping("/derived/first-top")
	public List<Author> firstTop() {
		this.log.info("first by age desc = {}", this.authorRepository.findFirstByOrderByAgeDesc()
			.map(Author::getName)
			.orElse(null));
		return this.authorRepository.findTop3ByOrderByAgeDesc();
	}

	@GetMapping("/derived/distinct")
	public List<Author> distinct(@RequestParam String name) {
		return this.authorRepository.findDistinctByName(name);
	}

	@GetMapping("/derived/count-exists")
	public String countExists(@RequestParam String name) {
		long activeAuthors = this.authorRepository.countByActiveIsTrue();
		long withBooks = this.authorRepository.countByBooksTitleIsNotNull();
		boolean exists = this.authorRepository.existsByName(name);
		this.log.info("active={} withBooks={} exists({})={}", activeAuthors, withBooks, name, exists);
		return "active=" + activeAuthors + ", withBooks=" + withBooks + ", exists(" + name + ")=" + exists;
	}

	// ==================================================================
	// 第 3 章：关键词
	// ==================================================================

	@GetMapping("/derived/between")
	public List<Author> between(@RequestParam int min, @RequestParam int max) {
		return this.authorRepository.findByAgeBetween(min, max);
	}

	@GetMapping("/derived/null")
	public List<Author> nullKeyword(@RequestParam(defaultValue = "isNull") String kind) {
		return switch (kind) {
			case "isNotNull" -> this.authorRepository.findByRatingIsNotNull();
			case "emailEmpty" -> this.authorRepository.findByEmailIsEmpty();
			case "emailNotEmpty" -> this.authorRepository.findByEmailIsNotEmpty();
			default -> this.authorRepository.findByRatingIsNull();
		};
	}

	@GetMapping("/derived/compare")
	public List<Author> compare(@RequestParam(defaultValue = "60") int age) {
		this.log.info("lt={} le={} gt={} ge={}", this.authorRepository.findByAgeLessThan(age).size(),
				this.authorRepository.findByAgeLessThanEqual(age).size(),
				this.authorRepository.findByAgeGreaterThan(age).size(),
				this.authorRepository.findByAgeGreaterThanEqual(age).size());
		return this.authorRepository.findByAgeGreaterThanEqual(age);
	}

	@GetMapping("/derived/date")
	public List<Author> date(@RequestParam(defaultValue = "1900-01-01") String date) {
		Date parsed = Date.valueOf(date);
		this.log.info("before {}: {} , after: {}", date, this.authorRepository.findByBirthdayBefore(parsed).size(),
				this.authorRepository.findByBirthdayAfter(parsed).size());
		return this.authorRepository.findByBirthdayAfter(parsed);
	}

	@GetMapping("/derived/like")
	public List<Author> like(@RequestParam(defaultValue = "鲁") String keyword) {
		this.log.info("containing={} startingWith={} endingWith={} like={} notLike={}",
				this.authorRepository.findByNameContaining(keyword).size(),
				this.authorRepository.findByNameStartingWith(keyword).size(),
				this.authorRepository.findByNameEndingWith(keyword).size(),
				this.authorRepository.findByNameLike("%" + keyword + "%").size(),
				this.authorRepository.findByNameNotLike("%" + keyword + "%").size());
		return this.authorRepository.findByNameContaining(keyword);
	}

	@GetMapping("/derived/boolean")
	public List<Author> booleanKeyword(@RequestParam(defaultValue = "true") boolean active) {
		return active ? this.authorRepository.findByActiveIsTrue() : this.authorRepository.findByActiveIsFalse();
	}

	@GetMapping("/derived/in")
	public List<Author> in(@RequestParam List<Integer> ages) {
		return this.authorRepository.findByAgeIn(ages);
	}

	@GetMapping("/derived/negate")
	public List<Author> negate(@RequestParam String name) {
		this.log.info("not={} equals={} ignoreCase={}", this.authorRepository.findByNameNot(name).size(),
				this.authorRepository.findByNameEquals(name).size(),
				this.authorRepository.findByNameIgnoreCase(name).size());
		return this.authorRepository.findByNameNot(name);
	}

	@GetMapping("/derived/logic")
	public List<Author> logic(@RequestParam String name, @RequestParam int age, @RequestParam String email) {
		this.log.info("and={} or={} andOr={}", this.authorRepository.findByNameAndAge(name, age).size(),
				this.authorRepository.findByNameOrEmail(name, email).size(),
				this.authorRepository.findByNameAndAgeOrEmail(name, age, email).size());
		return this.authorRepository.findByNameAndAgeOrEmail(name, age, email);
	}

	// ==================================================================
	// 图书/分类侧关键词（BigDecimal / Boolean / Timestamp / 可空 Integer）
	// ==================================================================

	@GetMapping("/books/price-range")
	public List<Book> priceRange(@RequestParam BigDecimal min, @RequestParam BigDecimal max) {
		return this.bookRepository.findByPriceBetween(min, max);
	}

	@GetMapping("/books/expensive")
	public List<Book> expensive(@RequestParam(defaultValue = "50.00") BigDecimal price) {
		return this.bookRepository.findByPriceGreaterThanEqual(price);
	}

	@GetMapping("/books/stock-null")
	public List<Book> stockNull() {
		return this.bookRepository.findByStockIsNull();
	}

	@GetMapping("/books/published")
	public List<Book> published(@RequestParam(defaultValue = "true") boolean published) {
		return published ? this.bookRepository.findByPublishedIsTrue() : this.bookRepository.findByPublishedIsFalse();
	}

	@GetMapping("/books/published-before")
	public List<Book> publishedBefore(@RequestParam(defaultValue = "1950-01-01 00:00:00") String dateTime) {
		return this.bookRepository.findByPublishedAtBefore(Timestamp.valueOf(dateTime));
	}

	@GetMapping("/books/isbn-in")
	public List<Book> isbnIn(@RequestParam List<String> isbns) {
		return this.bookRepository.findByIsbnIn(isbns);
	}

	@GetMapping("/categories")
	public List<Category> categories(@RequestParam(defaultValue = "true") boolean enabled) {
		return enabled ? this.categoryRepository.findByEnabledIsTrue() : this.categoryRepository.findAll();
	}

	// ==================================================================
	// 批量保存（saveAll 走批量 INSERT，自增主键逐个回填）
	// ==================================================================

	@PostMapping("/authors/batch")
	public List<Author> batchAuthors(@RequestParam(defaultValue = "3") int count) {
		List<Author> authors = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			Author author = new Author();
			author.setName("批量作者-" + System.nanoTime() + "-" + i);
			author.setEmail("batch" + i + "@example.com");
			author.setAge(20 + i);
			author.setRating(3);
			author.setBookCount(0L);
			author.setFansCount((long) i);
			author.setActive(Boolean.TRUE);
			author.setRoyalty(new BigDecimal("1.00"));
			author.setBirthday(Date.valueOf("1990-01-01"));
			authors.add(author);
		}
		List<Author> saved = this.authorRepository.saveAll(authors);
		this.log.info("batch saved authors: {}", saved.stream().map(Author::getId).toList());
		return saved;
	}

	@PostMapping("/books/batch")
	public List<Book> batchBooks(@RequestParam(defaultValue = "3") int count,
			@RequestParam(defaultValue = "1") long authorId) {
		List<Book> books = new ArrayList<>(count);
		long stamp = System.nanoTime();
		for (int i = 0; i < count; i++) {
			Book book = new Book();
			book.setAuthorId(authorId);
			book.setTitle("批量图书-" + stamp + "-" + i);
			book.setIsbn("BULK-" + stamp + "-" + i);
			book.setPrice(new BigDecimal("19.90"));
			book.setPages(100 + i);
			book.setStock(10);
			book.setSoldCount(0L);
			book.setReviewCount((long) i);
			book.setPublished(Boolean.TRUE);
			book.setPublishedAt(new Timestamp(System.currentTimeMillis()));
			books.add(book);
		}
		List<Book> saved = this.bookRepository.saveAll(books);
		this.log.info("batch saved books: {}", saved.stream().map(Book::getId).toList());
		return saved;
	}

	@PostMapping("/book-categories/batch")
	public List<BookCategory> batchBookCategories(@RequestParam long bookId, @RequestParam long categoryId,
			@RequestParam(defaultValue = "1") int weight) {
		BookCategory row = new BookCategory();
		row.setBookId(bookId);
		row.setCategoryId(categoryId);
		row.setWeight(weight);
		List<BookCategory> saved = this.bookCategoryRepository.saveAll(List.of(row));
		this.log.info("batch saved book_category: {}", saved.stream().map(BookCategory::getId).toList());
		return saved;
	}

	// ==================================================================
	// 概览
	// ==================================================================

	@GetMapping("/summary")
	public String summary() {
		return "authors=" + this.authorRepository.count() + ", books=" + this.bookRepository.count()
				+ ", categories=" + this.categoryRepository.count()
				+ ", bookCategories=" + this.bookCategoryRepository.count()
				+ ", publishedBooks=" + this.bookRepository.countByPublishedIsTrue();
	}

}
