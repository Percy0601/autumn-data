package xyz.wewin.autumn.data.using.demo.library;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AssociationMode;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnDataContext;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnDataContextHolder;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图书馆演示验证（不起 Spring 容器，直接实例化编译期生成的 Impl 连内存 H2）：
 * 批量保存、{@code Pageable} 分页、{@code OrderBy} 排序、以及 1:n / n:1 / n:1:n 三种关联。
 */
class LibraryDemoTest {

	private final DataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:autumn-library;DB_CLOSE_DELAY=-1", "sa", "");

	private JdbcTemplate jdbcTemplate;

	private AuthorRepository authorRepository;

	private BookRepository bookRepository;

	private CategoryRepository categoryRepository;

	private BookCategoryRepository bookCategoryRepository;

	@BeforeEach
	void setUp() {
		this.jdbcTemplate = new JdbcTemplate(this.dataSource);
		createSchema();
		seed();
		AutumnJdbc autumn = new AutumnJdbc(new JdbcConnectionSource(this.dataSource), new H2Dialect(),
				FetchPolicy.defaults());
		this.authorRepository = new AuthorRepositoryImpl(autumn);
		this.bookRepository = new BookRepositoryImpl(autumn);
		this.categoryRepository = new CategoryRepositoryImpl(autumn);
		this.bookCategoryRepository = new BookCategoryRepositoryImpl(autumn);
		// 上一个用例若在断言前抛错，一次性上下文就不会被消费；用例开始前清一次，避免串台
		AutumnDataContextHolder.clear();
	}

	private void createSchema() {
		this.jdbcTemplate.execute("DROP TABLE IF EXISTS lib_book_category");
		this.jdbcTemplate.execute("DROP TABLE IF EXISTS lib_book");
		this.jdbcTemplate.execute("DROP TABLE IF EXISTS lib_category");
		this.jdbcTemplate.execute("DROP TABLE IF EXISTS lib_author");
		this.jdbcTemplate.execute("CREATE TABLE lib_author (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
				+ " name VARCHAR(64) NOT NULL, email VARCHAR(128), age INT, rating INT, book_count BIGINT,"
				+ " fans_count BIGINT, active BOOLEAN, royalty DECIMAL(12,2), birthday DATE)");
		this.jdbcTemplate.execute("CREATE TABLE lib_book (id BIGINT AUTO_INCREMENT PRIMARY KEY, author_id BIGINT,"
				+ " title VARCHAR(128) NOT NULL, isbn VARCHAR(32), price DECIMAL(12,2), pages INT, stock INT,"
				+ " sold_count BIGINT, review_count BIGINT, published BOOLEAN, published_at TIMESTAMP)");
		this.jdbcTemplate.execute("CREATE TABLE lib_category (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
				+ " name VARCHAR(64) NOT NULL, code VARCHAR(32), sort_order INT, depth INT, enabled BOOLEAN)");
		this.jdbcTemplate.execute("CREATE TABLE lib_book_category (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
				+ " book_id BIGINT, category_id BIGINT, weight INT)");
	}

	private void seed() {
		this.jdbcTemplate.update("INSERT INTO lib_author (id, name, email, age, rating, book_count, fans_count,"
				+ " active, royalty, birthday) VALUES (1,'鲁迅','luxun@example.com',55,5,3,100000,TRUE,128000.00,"
				+ " DATE '1881-09-25')");
		this.jdbcTemplate.update("INSERT INTO lib_author (id, name, email, age, rating, book_count, fans_count,"
				+ " active, royalty, birthday) VALUES (2,'金庸','jinyong@example.com',94,5,2,500000,TRUE,990000.00,"
				+ " DATE '1924-03-10')");
		this.jdbcTemplate.update("INSERT INTO lib_author (id, name, email, age, rating, book_count, fans_count,"
				+ " active, royalty, birthday) VALUES (3,'无名氏',NULL,30,NULL,1,NULL,FALSE,0.00,NULL)");

		this.jdbcTemplate.update("INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count,"
				+ " review_count, published, published_at) VALUES (1,1,'呐喊','ISBN-1001',39.90,320,100,12000,3000,"
				+ " TRUE, TIMESTAMP '1923-01-01 00:00:00')");
		this.jdbcTemplate.update("INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count,"
				+ " review_count, published, published_at) VALUES (2,1,'彷徨','ISBN-1002',42.00,280,50,8000,1500,"
				+ " TRUE, TIMESTAMP '1926-01-01 00:00:00')");
		this.jdbcTemplate.update("INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count,"
				+ " review_count, published, published_at) VALUES (3,2,'射雕英雄传','ISBN-2001',88.00,1200,200,500000,"
				+ " 120000, TRUE, TIMESTAMP '1957-01-01 00:00:00')");
		this.jdbcTemplate.update("INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count,"
				+ " review_count, published, published_at) VALUES (4,2,'神雕侠侣','ISBN-2002',92.50,1100,NULL,400000,"
				+ " 98000, TRUE, TIMESTAMP '1959-01-01 00:00:00')");
		this.jdbcTemplate.update("INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count,"
				+ " review_count, published, published_at) VALUES (5,3,'无名书稿','ISBN-3001',9.90,100,0,10,NULL,FALSE,"
				+ " NULL)");
		this.jdbcTemplate.update("INSERT INTO lib_book (id, author_id, title, isbn, price, pages, stock, sold_count,"
				+ " review_count, published, published_at) VALUES (6,1,'绝版孤本','ISBN-1003',199.00,500,5,100,20,"
				+ " FALSE, TIMESTAMP '1930-01-01 00:00:00')");

		this.jdbcTemplate.update("INSERT INTO lib_category (id, name, code, sort_order, depth, enabled)"
				+ " VALUES (1,'文学','LIT',1,1,TRUE)");
		this.jdbcTemplate.update("INSERT INTO lib_category (id, name, code, sort_order, depth, enabled)"
				+ " VALUES (2,'武侠','WUXIA',2,1,TRUE)");
		this.jdbcTemplate.update("INSERT INTO lib_category (id, name, code, sort_order, depth, enabled)"
				+ " VALUES (3,'现代文学','MODERN',3,2,TRUE)");
		this.jdbcTemplate.update("INSERT INTO lib_category (id, name, code, sort_order, depth, enabled)"
				+ " VALUES (4,'绝版','RARE',4,NULL,FALSE)");

		this.jdbcTemplate.update("INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (1,1,1,10)");
		this.jdbcTemplate.update("INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (2,1,3,5)");
		this.jdbcTemplate.update("INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (3,2,1,8)");
		this.jdbcTemplate.update("INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (4,2,3,6)");
		this.jdbcTemplate.update("INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (5,3,2,10)");
		this.jdbcTemplate.update("INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (6,4,2,9)");
		this.jdbcTemplate.update("INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (7,5,3,1)");
		this.jdbcTemplate.update("INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (8,6,1,2)");
		this.jdbcTemplate.update("INSERT INTO lib_book_category (id, book_id, category_id, weight) VALUES (9,6,4,10)");

		// 种子用了固定主键 1~9，自增序列必须让开，否则批量插入生成的主键会撞主键
		this.jdbcTemplate.execute("ALTER TABLE lib_author ALTER COLUMN id RESTART WITH 100");
		this.jdbcTemplate.execute("ALTER TABLE lib_book ALTER COLUMN id RESTART WITH 100");
		this.jdbcTemplate.execute("ALTER TABLE lib_category ALTER COLUMN id RESTART WITH 100");
		this.jdbcTemplate.execute("ALTER TABLE lib_book_category ALTER COLUMN id RESTART WITH 100");
	}

	// ==================================================================
	// 批量保存
	// ==================================================================

	@Test
	void batchSaveAuthorsBackfillsGeneratedKeys() {
		List<Author> authors = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Author author = new Author();
			author.setName("批量作者-" + i);
			author.setAge(20 + i);
			author.setActive(Boolean.TRUE);
			author.setRoyalty(new BigDecimal("1.00"));
			authors.add(author);
		}

		List<Author> saved = this.authorRepository.saveAll(authors);

		assertThat(saved).hasSize(3);
		// 自增主键逐个回填
		assertThat(saved).allSatisfy(author -> assertThat(author.getId()).isNotNull());
		assertThat(saved).extracting(Author::getId).doesNotHaveDuplicates();
		assertThat(this.authorRepository.count()).isEqualTo(6);
	}

	@Test
	void batchSaveBooksBackfillsGeneratedKeysAndPersistsAllTypes() {
		List<Book> books = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Book book = new Book();
			book.setAuthorId(1L);
			book.setTitle("批量图书-" + i);
			book.setIsbn("BULK-" + i);
			book.setPrice(new BigDecimal("19.90"));
			book.setPages(100 + i);
			book.setStock(10);
			book.setSoldCount(0L);
			book.setReviewCount((long) i);
			book.setPublished(Boolean.TRUE);
			book.setPublishedAt(Timestamp.valueOf("2024-01-01 00:00:00"));
			books.add(book);
		}

		List<Book> saved = this.bookRepository.saveAll(books);

		assertThat(saved).hasSize(4).allSatisfy(book -> assertThat(book.getId()).isNotNull());
		// 回读确认各类型字段都落库（int/Integer/long/Long/String/Boolean/BigDecimal/Timestamp）
		Book reloaded = this.bookRepository.findById(saved.get(0).getId()).orElseThrow();
		assertThat(reloaded.getTitle()).isEqualTo("批量图书-0");
		assertThat(reloaded.getPrice()).isEqualByComparingTo("19.90");
		assertThat(reloaded.getPages()).isEqualTo(100);
		assertThat(reloaded.getStock()).isEqualTo(10);
		assertThat(reloaded.getSoldCount()).isZero();
		assertThat(reloaded.getReviewCount()).isZero();
		assertThat(reloaded.getPublished()).isTrue();
		assertThat(reloaded.getPublishedAt()).isEqualTo(Timestamp.valueOf("2024-01-01 00:00:00"));
	}

	@Test
	void batchSaveBookCategoriesBackfillsKeys() {
		BookCategory row = new BookCategory();
		row.setBookId(5L);
		row.setCategoryId(2L);
		row.setWeight(7);

		List<BookCategory> saved = this.bookCategoryRepository.saveAll(List.of(row));

		assertThat(saved).singleElement().satisfies(saved1 -> {
			assertThat(saved1.getId()).isNotNull();
			assertThat(saved1.getBookId()).isEqualTo(5L);
		});
	}

	// ==================================================================
	// 分页（Pageable）
	// ==================================================================

	@Test
	void pageableReturnsRequestedWindowAndTotal() {
		Page<Author> first = this.authorRepository.findAll(Pageable.of(0, 2));

		assertThat(first.content()).hasSize(2);
		assertThat(first.total()).isEqualTo(3);
		assertThat(first.totalPages()).isEqualTo(2);
		// 分页按主键顺序稳定输出
		assertThat(first.content()).extracting(Author::getId).containsExactly(1L, 2L);

		Page<Author> second = this.authorRepository.findAll(Pageable.of(1, 2));
		assertThat(second.content()).extracting(Author::getId).containsExactly(3L);
	}

	// ==================================================================
	// 排序（OrderBy…Asc/Desc，编译期映射列名）
	// ==================================================================

	@Test
	void orderByDescSortsByDeclaredColumn() {
		List<Book> books = this.bookRepository.findAllOrderByPriceDesc();

		assertThat(books).extracting(Book::getTitle).startsWith("绝版孤本");
		// 声明的是 Desc，因此按降序比较器校验
		assertThat(books).extracting(Book::getPrice)
			.isSortedAccordingTo(java.util.Comparator.<BigDecimal>reverseOrder());
		assertThat(books).extracting(Book::getPrice)
			.containsExactly(new BigDecimal("199.00"), new BigDecimal("92.50"), new BigDecimal("88.00"),
					new BigDecimal("42.00"), new BigDecimal("39.90"), new BigDecimal("9.90"));
	}

	@Test
	void orderByWithMultipleProperties() {
		List<Author> authors = this.authorRepository.findByActiveOrderByAgeAscNameDesc(true);
		assertThat(authors).extracting(Author::getAge).containsExactly(55, 94);

		List<Author> byAgeDesc = this.authorRepository.findAllOrderByAgeDesc();
		assertThat(byAgeDesc).extracting(Author::getAge).containsExactly(94, 55, 30);
	}

	// ==================================================================
	// 关联：1:n / n:1 / n:1:n
	// ==================================================================

	@Test
	void oneToManyAssemblesChildren() {
		Author luxun = this.authorRepository.findById(1L).orElseThrow();

		assertThat(luxun.getBooks()).extracting(Book::getTitle)
			.containsExactlyInAnyOrder("呐喊", "彷徨", "绝版孤本");
	}

	@Test
	void manyToOneAssemblesParent() {
		Book book = this.bookRepository.findById(1L).orElseThrow();

		// 多对一：父表外键 author_id → 子表主键，第二条语句取回后挂到单值字段
		assertThat(book.getAuthor()).isNotNull();
		assertThat(book.getAuthor().getName()).isEqualTo("鲁迅");
	}

	@Test
	void nToOneNQueryPathFiltersByNestedAssociation() {
		// Book → BookCategory → Category，两层 EXISTS 嵌套；父行不因一对多重复
		List<Book> wuxia = this.bookRepository.findByBookCategoriesCategoryName("武侠");
		assertThat(wuxia).extracting(Book::getTitle).containsExactlyInAnyOrder("射雕英雄传", "神雕侠侣");

		List<Book> literature = this.bookRepository.findByBookCategoriesCategoryCode("LIT");
		assertThat(literature).extracting(Book::getTitle).containsExactlyInAnyOrder("呐喊", "彷徨", "绝版孤本");
	}

	@Test
	void nToOneNAssemblesCategoryWhenJoinIsRoot() {
		// 以中间表为根查询时，它的多对一会被装配 → 拿到完整「书-分类」对应
		List<BookCategory> rows = this.bookCategoryRepository.findByBookId(1L);

		assertThat(rows).hasSize(2);
		assertThat(rows).allSatisfy(row -> assertThat(row.getCategory()).isNotNull());
		assertThat(rows).extracting(row -> row.getCategory().getName())
			.containsExactlyInAnyOrder("文学", "现代文学");
	}

	// ==================================================================
	// 分页关联加载三态
	// ==================================================================

	@Test
	void defaultModeSkipsAssociationOnLargePage() {
		// 默认（20 条分页级联）：页大小 25 > 阈值 20 → 不自动级联
		Page<Book> large = this.bookRepository.findAll(Pageable.of(0, 25));
		assertThat(large.content()).allSatisfy(book -> {
			assertThat(book.getAuthor()).isNull();
			assertThat(book.getBookCategories()).isEmpty();
		});
	}

	@Test
	void cascadeModeForcesAssociationOnLargePage() {
		AutumnDataContextHolder.startContext(new AutumnDataContext(AssociationMode.CASCADE, 20));
		Page<Book> large = this.bookRepository.findAll(Pageable.of(0, 25));

		assertThat(large.content()).allSatisfy(book -> assertThat(book.getAuthor()).isNotNull());
		assertThat(large.content()).extracting(Book::getTitle).contains("呐喊");
	}

	@Test
	void disabledModeSkipsAssociationEvenOnSmallPage() {
		AutumnDataContextHolder.startContext(new AutumnDataContext(AssociationMode.DISABLED, 20));
		Page<Book> small = this.bookRepository.findAll(Pageable.of(0, 2));

		assertThat(small.content()).allSatisfy(book -> {
			assertThat(book.getAuthor()).isNull();
			assertThat(book.getBookCategories()).isEmpty();
		});
	}

	// ==================================================================
	// 分页 + 排序（Page<T> 返回 + 末位 Pageable）
	// ==================================================================

	@Test
	void pagedDerivedQuerySortsAndPaginates() {
		Page<Book> first = this.bookRepository.findAllOrderByPriceDesc(Pageable.of(0, 2));

		assertThat(first.total()).isEqualTo(6);
		assertThat(first.totalPages()).isEqualTo(3);
		assertThat(first.content()).extracting(Book::getPrice)
			.containsExactly(new BigDecimal("199.00"), new BigDecimal("92.50"));

		Page<Book> second = this.bookRepository.findAllOrderByPriceDesc(Pageable.of(1, 2));
		assertThat(second.content()).extracting(Book::getPrice)
			.containsExactly(new BigDecimal("88.00"), new BigDecimal("42.00"));
	}

	@Test
	void pagedDerivedQueryAppliesPredicateBeforePaging() {
		// 先按作者过滤、再按销量倒序，最后才翻页 → total 是过滤后的行数
		Page<Book> page = this.bookRepository.findByAuthorIdOrderBySoldCountDesc(1L, Pageable.of(0, 2));

		assertThat(page.total()).isEqualTo(3);
		assertThat(page.content()).extracting(Book::getTitle).containsExactly("呐喊", "彷徨");
	}

	@Test
	void pagedDerivedQueryRespectsAssociationMode() {
		// 页大小 2 <= 阈值 20 → DEFAULT 模式级联，关联正常装配
		Page<Book> small = this.bookRepository.findAllOrderByPriceDesc(Pageable.of(0, 2));
		assertThat(small.content()).allSatisfy(book -> assertThat(book.getAuthor()).isNotNull());

		// 一次性切到 DISABLED：即便小页也不装配
		AutumnDataContextHolder.startContext(new AutumnDataContext(AssociationMode.DISABLED, 20));
		Page<Book> disabled = this.bookRepository.findAllOrderByPriceDesc(Pageable.of(0, 2));
		assertThat(disabled.content()).allSatisfy(book -> assertThat(book.getAuthor()).isNull());
	}

	// ==================================================================
	// 第 3 章关键词抽样
	// ==================================================================

	@Test
	void keywordsCoverNullLikeInAndBoolean() {
		assertThat(this.authorRepository.findByRatingIsNull()).extracting(Author::getName).containsExactly("无名氏");
		assertThat(this.authorRepository.findByRatingIsNotNull()).hasSize(2);
		assertThat(this.authorRepository.findByActiveIsTrue()).hasSize(2);
		assertThat(this.authorRepository.findByAgeBetween(30, 60)).hasSize(2);
		assertThat(this.authorRepository.findByAgeIn(List.of(55, 94))).hasSize(2);
		assertThat(this.authorRepository.findByNameContaining("鲁")).extracting(Author::getName).containsExactly("鲁迅");
		assertThat(this.authorRepository.findByNameStartingWith("鲁")).hasSize(1);
		// IsEmpty / IsNotEmpty 针对空串：种子里 email 均为 NULL 或真实值
		assertThat(this.authorRepository.findByEmailIsEmpty()).isEmpty();
		assertThat(this.authorRepository.findByEmailIsNotEmpty()).hasSize(2);
	}

	@Test
	void countExistsAndFirstTopWork() {
		assertThat(this.authorRepository.countByActiveIsTrue()).isEqualTo(2);
		assertThat(this.authorRepository.existsByName("金庸")).isTrue();
		assertThat(this.authorRepository.countByBooksTitleIsNotNull()).isEqualTo(3);
		assertThat(this.authorRepository.findFirstByOrderByAgeDesc()).get()
			.extracting(Author::getName)
			.isEqualTo("金庸");
		assertThat(this.authorRepository.findTop3ByOrderByAgeDesc()).hasSize(3);
	}

	// ==================================================================
	// 字段投影：一次性上下文只裁剪主实体标量列
	// ==================================================================

	@Test
	void projectionKeepsForeignKeySoNToOneAssemblySurvivesColumnTrimming() {
		AutumnDataContextHolder.selectFields("title");
		Book projected = this.bookRepository.findById(1L).orElseThrow();
		// 只取 title 与不可省略的 id / author_id：其余列留在实体默认值（基本类型退化为 0，包装类型退化为 null）
		assertThat(projected.getTitle()).isEqualTo("呐喊");
		assertThat(projected.getIsbn()).isNull();
		assertThat(projected.getPrice()).isNull();
		assertThat(projected.getPages()).isZero();
		assertThat(projected.getSoldCount()).isZero();
		// 多对一靠父表上的外键标量列装配，该列编译期判定为不可省略 → 裁剪列不会打断级联
		assertThat(projected.getAuthor()).extracting(Author::getName).isEqualTo("鲁迅");

		// 一次性上下文被这次查询消费：下一次回到编译期的全列形态
		assertThat(this.bookRepository.findById(1L).orElseThrow().getIsbn()).isEqualTo("ISBN-1001");
	}

	@Test
	void projectionAppliesToPageableAndDerivedReadsWithoutBreakingOneToMany() {
		AutumnDataContextHolder.excludeFields("isbn", "publishedAt");
		Page<Book> page = this.bookRepository.findAll(Pageable.of(0, 2));
		// 一对多的第二条语句按父主键取子行：父主键恒取，因此关联照旧装配
		assertThat(page.content()).hasSize(2).allSatisfy(book -> {
			assertThat(book.getIsbn()).isNull();
			assertThat(book.getPublishedAt()).isNull();
			assertThat(book.getTitle()).isNotNull();
			assertThat(book.getBookCategories()).isNotNull();
		});

		// 派生查询同样可裁剪；计数语句不带列清单，total 不受投影影响
		AutumnDataContextHolder.selectFields("price");
		Page<Book> filtered = this.bookRepository.findByAuthorIdOrderBySoldCountDesc(1L, Pageable.of(0, 2));
		assertThat(filtered.total()).isEqualTo(3);
		assertThat(filtered.content()).allSatisfy(book -> {
			assertThat(book.getPrice()).isNotNull();
			assertThat(book.getTitle()).isNull();
		});
	}

	@Test
	void projectionComposesWithDisabledCascadeOnSameOneShotContext() {
		// 同一个一次性上下文里同时设定模式与投影：本次分页查询既不装配关联，也只取需要的列
		AutumnDataContextHolder.disableCascade();
		AutumnDataContextHolder.selectFields("title");
		Page<Book> disabled = this.bookRepository.findAll(Pageable.of(0, 2));
		assertThat(disabled.content()).hasSize(2).allSatisfy(book -> {
			assertThat(book.getTitle()).isNotNull();
			assertThat(book.getIsbn()).isNull();
			// 模式只管分页装配（单行查询按编译期形态恒级联），投影只管列清单
			assertThat(book.getAuthor()).isNull();
			assertThat(book.getBookCategories()).isEmpty();
		});

		// 两项设置一起被消费：下一次回到默认（小页级联 + 全列）
		Page<Book> restored = this.bookRepository.findAll(Pageable.of(0, 2));
		assertThat(restored.content()).allSatisfy(book -> {
			assertThat(book.getIsbn()).isNotNull();
			assertThat(book.getAuthor()).isNotNull();
		});
	}

}
