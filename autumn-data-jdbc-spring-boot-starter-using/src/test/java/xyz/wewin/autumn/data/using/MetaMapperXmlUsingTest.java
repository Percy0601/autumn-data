package xyz.wewin.autumn.data.using;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import xyz.wewin.autumn.data.meta.jdbc.SqlBuild;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaQuery.Direction;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaQuery.Op;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaRow;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上层用法示例：一个 Spring Boot 应用里，不写实体、不改代码，怎么用动态层做单据 CRUD 和动态 SQL。
 *
 * <p>引擎由 {@code autumn-data-jdbc-spring-boot-starter} 自动装配并装进 {@link MetaMapper} 门面，
 * 使用方只配了数据源（见 {@code src/test/resources/application.properties}）；
 * Mapper 文件目录配成 {@code autumn.data.meta.mapper.xml-directory=target/meta-mappers}，
 * 生成的文件不污染源码目录。</p>
 *
 * <p>两组入口各走一条路，示例按这个分：</p>
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

	private static final Path XML_DIRECTORY = Path.of("target/meta-mappers");

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
		Object id = MetaMapper.save(DYN, Map.of("order_no", "SO-1", "amount", "12.50", "status", 1));
		assertThat(id).isNotNull();

		MetaRow saved = MetaMapper.findById(DYN, id).orElseThrow();
		assertThat(saved.getString("order_no")).isEqualTo("SO-1");
		assertThat(saved.getBigDecimal("amount")).isEqualByComparingTo("12.50");
		// 动态行就是「运行期实体」，直接能序列化给前端
		assertThat(saved.toJson()).contains("SO-1");
		assertThat(saved.toMap()).containsValue("SO-1");

		// 批量：传的列一致走 JDBC batch，不一致退化成逐条 save，返回值都按入参顺序
		List<Object> ids = MetaMapper.saveAll(DYN, List.of(Map.of("order_no", "SO-2", "status", 1),
				Map.of("order_no", "SO-3", "status", 2, "amount", "88")));
		assertThat(ids).hasSize(2);
		assertThat(MetaMapper.findById(DYN, ids.get(0)).orElseThrow().getString("order_no"))
				.isEqualTo("SO-2");

		// 部分更新：没传的列保持原值；置空必须显式传 MetaValue.NULL
		MetaMapper.updateById(DYN, id, Map.of("remark", "加急"));
		assertThat(MetaMapper.findById(DYN, id).orElseThrow().getBigDecimal("amount"))
				.isEqualByComparingTo("12.50");
		MetaMapper.updateById(DYN, id, Map.of("remark", MetaValue.NULL));
		assertThat(MetaMapper.findById(DYN, id).orElseThrow().get("remark")).isNull();

		// 条件运算符是开集：比较、模糊、IN、排序都不需要预先写死在文件里
		assertThat(MetaMapper.findList(DYN, MetaQuery.by("amount", Op.GE, "50")))
				.extracting(row -> row.getString("order_no")).containsExactly("SO-3");
		assertThat(MetaMapper.findList(DYN, MetaQuery.all().contains("order_no", "SO-").isNotNull("amount")
				.orderBy("amount", Direction.DESC)))
						.extracting(row -> row.getString("order_no")).containsExactly("SO-3", "SO-1");
		assertThat(MetaMapper.findList(DYN, MetaQuery.by("id", Op.IN, id, ids.get(0)))).hasSize(2);

		// 分页：select 与 count 两条语句同一次调用
		Page<MetaRow> page = MetaMapper.findPage(DYN, MetaQuery.by("status", 1), Pageable.of(0, 1));
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

		Object id = MetaMapper.save(DYN, Map.of("order_no", "SO-1", "amount", "12.50", "status", 1));

		// 生成只由「第一次走命名语句」触发
		assertThat(client.selectCount(DYN, "selectCount", Map.of())).isEqualTo(1);
		Path file = mapperFile(DYN);
		assertThat(file).isNotNull();
		String generated = read(file);
		assertThat(generated).contains("generated-by: autumn-data-meta-jdbc");
		assertThat(generated).contains("<select id=\"selectList\">", "<select id=\"selectCount\">",
				"<insert id=\"insert\">", "<update id=\"updateByKey\">");

		// 生成的四条语句照常能跑
		assertThat(client.selectList(DYN, "selectList", Map.of("status", 1)))
				.extracting(row -> row.getString("order_no")).containsExactly("SO-1");
		assertThat(client.execute(DYN, "insert", Map.of("order_no", "SO-2", "status", 2))).isEqualTo(1);
		assertThat(client.selectOne(DYN, "selectList", Map.of("order_no", "SO-2"))).isPresent();
		assertThat(client.execute(DYN, "updateByKey", Map.of("id", id, "remark", "用生成语句改的"))).isEqualTo(1);
		assertThat(MetaMapper.findById(DYN, id).orElseThrow().getString("remark")).isEqualTo("用生成语句改的");

		// 自增主键不进列清单，没传的列交给数据库默认值
		SqlBuild inserted = client.sqlFor(DYN, "insert", Map.of("order_no", "SO-3"));
		assertThat(inserted.sql()).doesNotContainIgnoringCase("\"id\"").containsIgnoringCase("order_no");
		assertThat(inserted.args()).containsExactly("SO-3");

		// 逻辑删除列被写进了文件：作废的行两条路都查不出来
		Object voided = MetaMapper.save(DYN, Map.of("order_no", "SO-9", "status", 1));
		MetaMapper.updateById(DYN, voided, Map.of("deleted_flag", 1));
		assertThat(client.selectList(DYN, "selectList", Map.of("status", 1)))
				.extracting(row -> row.getString("order_no")).doesNotContain("SO-9");
		assertThat(MetaMapper.findList(DYN, MetaQuery.by("status", 1)))
				.extracting(row -> row.getString("order_no")).doesNotContain("SO-9");

		// 改表加列：refresh 之后 CRUD 立刻认得新列，Mapper 文件一个字都不被改写
		this.jdbcOperations.execute("ALTER TABLE " + DYN + " ADD COLUMN courier VARCHAR(32)");
		MetaMapper.refresh(DYN);
		assertThat(MetaMapper.updateById(DYN, id, Map.of("courier", "SF"))).isEqualTo(1);
		assertThat(MetaMapper.findById(DYN, id).orElseThrow().getString("courier")).isEqualTo("SF");
		assertThat(read(mapperFile(DYN))).isEqualTo(generated);
		assertThat(client.sqlFor(DYN, "updateByKey", Map.of("id", id, "status", 3)).sql())
				.doesNotContainIgnoringCase("courier");

		// 要让新列进语句就手工编辑文件；想整个按新表结构重来，删掉文件即可
		client.execute(DYN, "updateByKey", Map.of("id", id, "status", 3));
		deleteMapperFile(DYN);
		MetaMapper.refresh(DYN);
		assertThat(client.sqlFor(DYN, "selectList", Map.of("courier", "SF")).sql())
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
				Map.of("order_no", "SO-EX-1", "user_id", 9001, "amount", "10.00"))).isEqualTo(1);
		assertThat(client.execute(ORDER, "quickAdd",
				Map.of("order_no", "SO-EX-2", "user_id", 9001, "amount", "90.00"))).isEqualTo(1);
		// 手写文件优先，且不会在 xml-directory 里再落一份
		assertThat(mapperFile(ORDER)).isNull();

		// <where> + <if>：给了什么条件拼什么条件；排序字段走 ${} 标识符
		assertThat(client.selectList(ORDER, "search", Map.of("user_id", 9001, "orderBy", "amount")))
				.extracting(row -> row.getString("order_no")).containsExactly("SO-EX-1", "SO-EX-2");
		assertThat(client.selectList(ORDER, "search", Map.of("user_id", 9001, "minAmount", "50")))
				.extracting(row -> row.getString("order_no")).containsExactly("SO-EX-2");
		assertThat(client.selectList(ORDER, "search", Map.of("order_no", "%SO-EX%"))).hasSize(2);

		// 一个条件都不给：<where> 整段连 WHERE 带它的绑定一起退回
		SqlBuild plain = client.sqlFor(ORDER, "search", Map.of());
		assertThat(plain.sql()).doesNotContain("WHERE").doesNotContain("ORDER BY");
		assertThat(plain.args()).isEmpty();

		// <foreach> 展开 IN：占位符个数跟着集合长度走，值仍然只进绑定
		List<Long> ids = client.selectList(ORDER, "search", Map.of("user_id", 9001)).stream()
				.map(row -> row.getLong("id")).sorted().toList();
		SqlBuild in = client.sqlFor(ORDER, "search", Map.of("ids", ids));
		assertThat(in.sql()).contains("(?,?)");
		assertThat(in.args()).containsExactly(ids.get(0), ids.get(1));
		assertThat(client.selectList(ORDER, "search", Map.of("ids", ids))).hasSize(2);

		// <choose>：同一个开关换一种排序方向
		List<String> asc = orderNos(client.selectList(ORDER, "listSorted", Map.of("latest", false)));
		assertThat(orderNos(client.selectList(ORDER, "listSorted", Map.of("latest", true))))
				.containsExactlyElementsOf(asc.reversed());

		// <trim prefix="SET ">：只更新传了的列；置空照样用 MetaValue.NULL
		Long first = ids.get(0);
		assertThat(client.execute(ORDER, "patch", Map.of("id", first, "amount", "88.88"))).isEqualTo(1);
		assertThat(client.selectOne(ORDER, "byOrderNo", Map.of("order_no", "SO-EX-1"))
				.orElseThrow().getBigDecimal("amount")).isEqualByComparingTo("88.88");
		assertThat(client.execute(ORDER, "patch", Map.of("id", first, "amount", MetaValue.NULL))).isEqualTo(1);
		assertThat(client.selectOne(ORDER, "byOrderNo", Map.of("order_no", "SO-EX-1"))
				.orElseThrow().get("amount")).isNull();

		// selectCount 吃返回单个数字的 <select>
		assertThat(client.selectCount(ORDER, "countMatching", Map.of("user_id", 9001))).isEqualTo(2);
	}

	/**
	 * ④ 入口与语句类型要对得上：写错不会静默跑偏，id 拼错时把文件里可用的 id 一起报出来。
	 */
	@Test
	void namedStatementsEnforceTheirTypeAndIds() {
		MetaMapperClient client = MetaMapper.defaultClient();

		assertThatThrownBy(() -> client.execute(ORDER, "search", Map.of()))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("is a <select>");
		assertThatThrownBy(() -> client.selectList(ORDER, "patch", Map.of("id", 1L, "amount", "1.00")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("is not a <select>");

		// 物理删除可以写在文件里，但动态层拒绝执行，行还在
		assertThatThrownBy(() -> client.execute(ORDER, "purgeById", Map.of("id", 1L)))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("does not run physical deletes");
		assertThat(MetaMapper.findById(ORDER, 1L)).isPresent();

		assertThatThrownBy(() -> client.selectList(ORDER, "byOrderNO", Map.of("order_no", "SO-1001")))
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

		SqlBuild bound = client.sqlFor(ORDER, "byOrderNo", Map.of("order_no", payload));
		assertThat(bound.sql()).doesNotContain(payload).doesNotContain("'").contains("?");
		assertThat(bound.args()).containsExactly(payload);
		assertThat(client.selectList(ORDER, "byOrderNo", Map.of("order_no", payload))).isEmpty();

		// ${} 是拼接，所以先过标识符白名单：分号、注释符、比较符都进不来
		assertThatThrownBy(() -> client.sqlFor(ORDER, "search", Map.of("orderBy", "amount; DROP TABLE demo_order")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");
		assertThatThrownBy(() -> client.sqlFor(ORDER, "search", Map.of("orderBy", "amount -- sort")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");
		assertThatThrownBy(() -> client.sqlFor(ORDER, "search", Map.of("orderBy", "1=1")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");

		// CRUD 这条路：列名必须是元数据里真实存在的列，用户字符串根本进不了 SQL 文本
		assertThatThrownBy(() -> MetaMapper.findList(ORDER, MetaQuery.by("order_no = 'x' OR 1=1", "y")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("unknown column");

		// 上面这些尝试都没动到数据，表也还在
		assertThat(client.selectCount(ORDER, "countMatching", Map.of("user_id", 9001))).isEqualTo(0);
		assertThat(MetaMapper.findList(ORDER, MetaQuery.by("order_no", "SO-1001"))).hasSize(1);
	}

	private static List<String> orderNos(List<MetaRow> rows) {
		return rows.stream().map(row -> row.getString("order_no")).toList();
	}

	/** 生成的文件名跟着元数据里的表名大小写走（H2 是大写、MySQL 习惯小写），这里按忽略大小写找。 */
	private static Path mapperFile(String table) {
		if (!Files.isDirectory(XML_DIRECTORY)) {
			return null;
		}
		try (Stream<Path> files = Files.list(XML_DIRECTORY)) {
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
