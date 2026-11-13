package xyz.wewin.autumn.data.using;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcOperations;

import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.sql.MarkedSql;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery.Direction;
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery.Op;
import xyz.wewin.autumn.data.common.meta.MetaClass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上层用法示例：一个 Spring Boot 应用里，不写实体、不改代码，怎么用动态层做单据 CRUD 和动态 SQL。
 *
 * <p>引擎由 {@code autumn-data-jdbc-spring-boot-starter} 自动装配并装进 {@link MetaMapper} 门面，
 * 使用方只配了数据源（见 {@code src/test/resources/application.properties}）；
 * 工作目录配成 {@code autumn.data.meta.mapper.meta-directory=target/autumn-meta}，
 * 三类产物落在它的固定子目录里（{@code meta-class} 元类定义 / {@code entity} 生成的实体源码 /
 * {@code mapper} Mapper 文件），生成的文件不污染源码目录。</p>
 *
 * <p>操作对象始终是 {@link MetaClass}：写用的实体由 {@link MetaMapper#newEntity(String)} 按当前表结构
 * 生成，读回来的行也是同一个类的实例，「读出来 — 改几列 — 写回去」中间不需要任何转换。</p>
 *
 * <p>三组入口各走一条路，示例按这个分：</p>
 * <ul>
 *   <li>{@link #crudNeedsNoMapperFile()}：{@link MetaQuery} 驱动的 CRUD，SQL 按元数据现拼，
 *       <b>完全不读 Mapper 文件</b>；</li>
 *   <li>{@link #generatedMapperFileIsWrittenOnceThenOwnedByTheUser()}：命名语句第一次访问时
 *       按表结构生成 {@code <表名>.xml}，之后归使用方编辑；</li>
 *   <li>{@link #handWrittenMapperFileDrivesDynamicSql()}：文件已经存在（本模块的
 *       {@code src/test/resources/meta-mappers/demo_order.xml}）就只用文件，不再次生成。</li>
 * </ul>
 */
@SpringBootTest(classes = UsingApplication.class)
class MetaMapperXmlUsingTest {

	/** 运行期建出来的表：演示「文件不存在才生成一次」。 */
	private static final String DYN = "dyn_form_demo";

	/** 建表脚本里已有的表：它的 Mapper 文件是手写的。 */
	private static final String ORDER = "demo_order";

	private static final Path MAPPER_DIRECTORY = Path.of("target/autumn-meta/mapper");

	@Autowired
	JdbcOperations jdbcOperations;

	@BeforeEach
	void prepareTables() {
		this.jdbcOperations.execute("DROP TABLE IF EXISTS " + DYN);
		this.jdbcOperations.execute("CREATE TABLE " + DYN
				+ " (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(32) NOT NULL, amount DECIMAL(12,2),"
				+ " status INT, remark VARCHAR(64), channel VARCHAR(32), deleted_flag INT DEFAULT 0)");
		this.jdbcOperations.execute("DELETE FROM " + ORDER + " WHERE order_no LIKE 'SO-EX-%'");
		deleteMapperFile(DYN);
		MetaMapper.refresh(DYN);
		MetaMapper.defaultClient().refresh(ORDER);
	}

	/**
	 * ① CRUD：只需要数据源，一条 XML 都不写。列名、类型、主键全从数据库元数据来，
	 * 所以运行期建的表立刻能用。
	 */
	@Test
	void crudNeedsNoMapperFile() {
		MetaClass order = MetaMapper.save(form(DYN, "order_no", "SO-1", "amount", "12.50", "status", 1));
		assertThat(order.get("id")).isNotNull(); // 自增主键回填在实体上

		MetaClass saved = MetaMapper.findById(DYN, order.get("id")).orElseThrow();
		assertThat(saved.getString("order_no")).isEqualTo("SO-1");
		assertThat(saved.getBigDecimal("amount")).isEqualByComparingTo("12.50");
		// 动态行就是「运行期实体」，直接能序列化给前端
		assertThat(saved.toJson()).contains("SO-1");
		assertThat(saved.toMap()).containsValue("SO-1");

		// 批量：写过的列一致走 JDBC batch，不一致退化成逐条 save，返回值都按入参顺序
		List<MetaClass> batch = MetaMapper.saveAll(List.of(form(DYN, "order_no", "SO-2", "status", 1),
				form(DYN, "order_no", "SO-3", "status", 2, "amount", "88")));
		assertThat(batch).hasSize(2);
		assertThat(MetaMapper.findById(DYN, batch.get(0).get("id")).orElseThrow().getString("order_no"))
				.isEqualTo("SO-2");

		// 部分更新：没写过的列保持原值；写 null 才是显式把这一列置空
		saved.set("remark", "加急");
		assertThat(MetaMapper.update(saved)).isEqualTo(1);
		assertThat(MetaMapper.findById(DYN, order.get("id")).orElseThrow().getBigDecimal("amount"))
				.isEqualByComparingTo("12.50");
		MetaClass cleared = MetaMapper.findById(DYN, order.get("id")).orElseThrow();
		cleared.set("remark", null);
		assertThat(MetaMapper.update(cleared)).isEqualTo(1);
		assertThat(MetaMapper.findById(DYN, order.get("id")).orElseThrow().get("remark")).isNull();

		// 条件运算符是开集：比较、模糊、IN、排序都不需要预先写死在文件里
		assertThat(MetaMapper.findList(DYN, MetaQuery.by("amount", Op.GE, "50")))
				.extracting(row -> row.getString("order_no")).containsExactly("SO-3");
		assertThat(MetaMapper.findList(DYN, MetaQuery.all().contains("order_no", "SO-").isNotNull("amount")
				.orderBy("amount", Direction.DESC)))
						.extracting(row -> row.getString("order_no")).containsExactly("SO-3", "SO-1");
		assertThat(MetaMapper.findList(DYN,
				MetaQuery.by("id", Op.IN, order.get("id"), batch.get(0).get("id")))).hasSize(2);

		// 分页：select 与 count 两条语句同一次调用
		Page<MetaClass> page = MetaMapper.findPage(DYN, MetaQuery.by("status", 1), Pageable.of(0, 1));
		assertThat(page.total()).isEqualTo(2);
		assertThat(page.content()).hasSize(1);
		assertThat(MetaMapper.exists(DYN, MetaQuery.by("order_no", "SO-2"))).isTrue();

		// 这条路不读 Mapper 文件，也就不会替谁生成一份
		assertThat(mapperFile(DYN)).isNull();
	}

	/**
	 * ② 命名语句 + 自动生成的 Mapper 文件：第一次访问按表结构生成，之后文件归使用方，
	 * 改表不会回头覆盖它。
	 */
	@Test
	void generatedMapperFileIsWrittenOnceThenOwnedByTheUser() {
		// 多数据源就把池名换掉：MetaMapper.of("tenant-a")
		MetaMapperClient client = MetaMapper.of("default");

		MetaClass order = MetaMapper.save(form(DYN, "order_no", "SO-1", "amount", "12.50", "status", 1));

		// 生成只由「第一次走命名语句」触发
		assertThat(client.selectCount(DYN, "selectCount", params())).isEqualTo(1);
		Path file = mapperFile(DYN);
		assertThat(file).isNotNull();
		String generated = read(file);
		assertThat(generated).contains("generated-by: autumn-data-meta-jdbc");
		assertThat(generated).contains("<select id=\"selectList\">", "<select id=\"selectCount\">",
				"<insert id=\"insert\">", "<update id=\"updateByKey\">");

		// 生成的四条语句照常能跑
		assertThat(client.selectList(DYN, "selectList", params("status", 1)))
				.extracting(row -> row.getString("order_no")).containsExactly("SO-1");
		assertThat(client.execute(DYN, "insert", params("order_no", "SO-2", "status", 2))).isEqualTo(1);
		assertThat(client.selectOne(DYN, "selectList", params("order_no", "SO-2"))).isPresent();
		assertThat(client.execute(DYN, "updateByKey", params("id", order.get("id"), "remark", "用生成语句改的")))
				.isEqualTo(1);
		assertThat(MetaMapper.findById(DYN, order.get("id")).orElseThrow().getString("remark"))
				.isEqualTo("用生成语句改的");

		// 自增主键不进列清单，没传的列交给数据库默认值
		MarkedSql inserted = client.sqlFor(DYN, "insert", params("order_no", "SO-3"));
		assertThat(inserted.sql()).doesNotContainIgnoringCase("\"id\"").containsIgnoringCase("order_no");
		assertThat(inserted.values()).containsExactly("SO-3");

		// 逻辑删除列被写进了文件：作废的行两条路都查不出来
		MetaClass voided = MetaMapper.save(form(DYN, "order_no", "SO-9", "status", 1));
		voided.set("deleted_flag", 1);
		assertThat(MetaMapper.update(voided)).isEqualTo(1);
		assertThat(client.selectList(DYN, "selectList", params("status", 1)))
				.extracting(row -> row.getString("order_no")).doesNotContain("SO-9");
		assertThat(MetaMapper.findList(DYN, MetaQuery.by("status", 1)))
				.extracting(row -> row.getString("order_no")).doesNotContain("SO-9");

		// 改表加列：refresh 之后 CRUD 立刻认得新列，Mapper 文件一个字都不被改写
		this.jdbcOperations.execute("ALTER TABLE " + DYN + " ADD COLUMN courier VARCHAR(32)");
		MetaMapper.refresh(DYN);
		MetaClass courierRow = MetaMapper.findById(DYN, order.get("id")).orElseThrow();
		courierRow.set("courier", "SF");
		assertThat(MetaMapper.update(courierRow)).isEqualTo(1);
		assertThat(MetaMapper.findById(DYN, order.get("id")).orElseThrow().getString("courier")).isEqualTo("SF");
		assertThat(read(mapperFile(DYN))).isEqualTo(generated);
		assertThat(client.sqlFor(DYN, "updateByKey", params("id", order.get("id"), "status", 3)).sql())
				.doesNotContainIgnoringCase("courier");

		// 要让新列进语句就手工编辑文件；想整个按新表结构重来，删掉文件即可
		client.execute(DYN, "updateByKey", params("id", order.get("id"), "status", 3));
		deleteMapperFile(DYN);
		MetaMapper.refresh(DYN);
		assertThat(client.sqlFor(DYN, "selectList", params("courier", "SF")).sql())
				.containsIgnoringCase("courier");
	}

	/**
	 * ③ 命名语句 + 手写 Mapper 文件：文件存在就只用文件，动态 SQL 的写法全在 XML 里。
	 * 语句来自 {@code src/test/resources/meta-mappers/demo_order.xml}。
	 */
	@Test
	void handWrittenMapperFileDrivesDynamicSql() {
		MetaMapperClient client = MetaMapper.defaultClient();
		assertThat(client.execute(ORDER, "quickAdd",
				params("order_no", "SO-EX-1", "user_id", 9001, "amount", "10.00"))).isEqualTo(1);
		assertThat(client.execute(ORDER, "quickAdd",
				params("order_no", "SO-EX-2", "user_id", 9001, "amount", "90.00"))).isEqualTo(1);
		// 手写文件优先，且不会在 meta-directory 的 mapper 子目录里再落一份
		assertThat(mapperFile(ORDER)).isNull();

		// <where> + <if>：给了什么条件拼什么条件；排序字段走 ${} 标识符
		assertThat(client.selectList(ORDER, "search", params("user_id", 9001, "orderBy", "amount")))
				.extracting(row -> row.getString("order_no")).containsExactly("SO-EX-1", "SO-EX-2");
		assertThat(client.selectList(ORDER, "search", params("user_id", 9001, "minAmount", "50")))
				.extracting(row -> row.getString("order_no")).containsExactly("SO-EX-2");
		assertThat(client.selectList(ORDER, "search", params("order_no", "%SO-EX%"))).hasSize(2);

		// 一个条件都不给：<where> 整段连 WHERE 带它的绑定一起退回
		MarkedSql plain = client.sqlFor(ORDER, "search", params());
		assertThat(plain.sql()).doesNotContain("WHERE").doesNotContain("ORDER BY");
		assertThat(plain.values()).isEmpty();
		// 没有绑定槽 = 纯 ${} 拼出的成品 SQL，执行层走 Statement
		assertThat(plain.literalOnly()).isTrue();

		// <foreach> 展开 IN：占位符个数跟着集合长度走，值仍然只进绑定
		List<Long> ids = client.selectList(ORDER, "search", params("user_id", 9001)).stream()
				.map(row -> row.getLong("id")).sorted().toList();
		MarkedSql in = client.sqlFor(ORDER, "search", params("ids", ids));
		assertThat(in.sql()).contains("(?,?)");
		assertThat(in.values()).containsExactly(ids.get(0), ids.get(1));
		assertThat(client.selectList(ORDER, "search", params("ids", ids))).hasSize(2);

		// <choose>：同一个开关换一种排序方向
		List<String> asc = orderNos(client.selectList(ORDER, "listSorted", params("latest", false)));
		assertThat(orderNos(client.selectList(ORDER, "listSorted", params("latest", true))))
				.containsExactlyElementsOf(asc.reversed());

		// <trim prefix="SET ">：patch 只改传了值的列；不判空的 setAmount 传 null 就是把这一列置空
		Long first = ids.get(0);
		assertThat(client.execute(ORDER, "patch", params("id", first, "amount", "88.88"))).isEqualTo(1);
		assertThat(client.selectOne(ORDER, "byOrderNo", params("order_no", "SO-EX-1"))
				.orElseThrow().getBigDecimal("amount")).isEqualByComparingTo("88.88");
		assertThat(client.execute(ORDER, "setAmount", params("id", first, "amount", null))).isEqualTo(1);
		assertThat(client.selectOne(ORDER, "byOrderNo", params("order_no", "SO-EX-1"))
				.orElseThrow().get("amount")).isNull();

		// 参数对象也可以是实体本身：列名按元数据对上，其余名字照旧进语句作用域
		MetaClass args = MetaMapper.newEntity(ORDER);
		args.set("user_id", 9001);
		args.set("orderBy", "amount");
		assertThat(client.selectList(ORDER, "search", args)).hasSize(2);

		// selectCount 吃返回单个数字的 <select>
		assertThat(client.selectCount(ORDER, "countMatching", params("user_id", 9001))).isEqualTo(2);
	}

	/**
	 * ④ 入口与语句类型要对得上：写错不会静默跑偏，id 拼错时把文件里可用的 id 一起报出来。
	 */
	@Test
	void namedStatementsEnforceTheirTypeAndIds() {
		MetaMapperClient client = MetaMapper.defaultClient();

		assertThatThrownBy(() -> client.execute(ORDER, "search", params()))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("is a <select>");
		assertThatThrownBy(() -> client.selectList(ORDER, "patch", params("id", 1L, "amount", "1.00")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("is not a <select>");

		// 物理删除可以写在文件里，但动态层拒绝执行，行还在
		assertThatThrownBy(() -> client.execute(ORDER, "purgeById", params("id", 1L)))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("does not run physical deletes");
		assertThat(MetaMapper.findById(ORDER, 1L)).isPresent();

		assertThatThrownBy(() -> client.selectList(ORDER, "byOrderNO", params("order_no", "SO-1001")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("has no statement 'byOrderNO'")
				.hasMessageContaining("byOrderNo");
	}

	/**
	 * ⑤ 防注入的两条边界：{@code #{}} 的值永远不进 SQL 文本，{@code ${}} 只让标识符过白名单。
	 */
	@Test
	void valuesBindAndIdentifierFragmentsAreWhitelisted() {
		MetaMapperClient client = MetaMapper.defaultClient();
		String payload = "SO-1001' OR '1'='1";

		MarkedSql bound = client.sqlFor(ORDER, "byOrderNo", params("order_no", payload));
		assertThat(bound.sql()).doesNotContain(payload).doesNotContain("'").contains("?");
		assertThat(bound.values()).containsExactly(payload);
		assertThat(client.selectList(ORDER, "byOrderNo", params("order_no", payload))).isEmpty();

		// ${} 是拼接，所以先过标识符白名单：分号、注释符、比较符都进不来
		assertThatThrownBy(() -> client.sqlFor(ORDER, "search", params("orderBy", "amount; DROP TABLE demo_order")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");
		assertThatThrownBy(() -> client.sqlFor(ORDER, "search", params("orderBy", "amount -- sort")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");
		assertThatThrownBy(() -> client.sqlFor(ORDER, "search", params("orderBy", "1=1")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");

		// CRUD 这条路：列名必须是元数据里真实存在的列，用户字符串根本进不了 SQL 文本
		assertThatThrownBy(() -> MetaMapper.findList(ORDER, MetaQuery.by("order_no = 'x' OR 1=1", "y")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("unknown column");

		// 上面这些尝试都没动到数据，表也还在
		assertThat(client.selectCount(ORDER, "countMatching", params("user_id", 9001))).isEqualTo(0);
		assertThat(MetaMapper.findList(ORDER, MetaQuery.by("order_no", "SO-1001"))).hasSize(1);
	}

	/** 待写入的实体：形参是「列名, 值」成对，写过的列才是这次 INSERT/UPDATE 的范围。 */
	private static MetaClass form(String table, Object... nameValues) {
		return fill(MetaMapper.newEntity(table), nameValues);
	}

	/** 命名语句的参数：列以外的开关、集合与排序名也放这里（实体上的动态属性）。 */
	private static MetaClass params(Object... nameValues) {
		return fill(new MetaClass(), nameValues);
	}

	private static MetaClass fill(MetaClass entity, Object... nameValues) {
		for (int i = 0; i < nameValues.length; i += 2) {
			entity.set((String) nameValues[i], nameValues[i + 1]);
		}
		return entity;
	}

	private static List<String> orderNos(List<MetaClass> rows) {
		return rows.stream().map(row -> row.getString("order_no")).toList();
	}

	/** 生成的文件名跟着元数据里的表名大小写走（H2 是大写、MySQL 习惯小写），这里按忽略大小写找。 */
	private static Path mapperFile(String table) {
		if (!Files.isDirectory(MAPPER_DIRECTORY)) {
			return null;
		}
		try (Stream<Path> files = Files.list(MAPPER_DIRECTORY)) {
			return files.filter(path -> path.getFileName().toString().equalsIgnoreCase(table + ".xml"))
					.findFirst().orElse(null);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void deleteMapperFile(String table) {
		Path file = mapperFile(table);
		if (file != null) {
			try {
				Files.delete(file);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
