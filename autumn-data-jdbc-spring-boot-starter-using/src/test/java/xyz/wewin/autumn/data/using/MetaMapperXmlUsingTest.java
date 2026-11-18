package xyz.wewin.autumn.data.using;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcOperations;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.sql.MarkedSql;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上层用法示例：一个 Spring Boot 应用里，不写实体、不改代码，怎么用动态层做单据 CRUD 和动态 SQL。
 *
 * <p>引擎由 {@code autumn-data-jdbc-spring-boot-starter} 自动装配并装进 {@link MetaMapper} 门面，
 * 使用方只配了数据源（见 {@code src/test/resources/application.properties}）；
 * 工作目录配成 {@code autumn.data.meta.mapper.meta-directory=target/autumn-meta}，
 * 产物落在它的固定子目录里（{@code meta-class} 元类定义 / {@code entity} 生成的实体源码 /
 * {@code sql} 语句 XML），生成的文件不污染源码目录。</p>
 *
 * <p><b>SQL 只有一个来源</b>：一张表一个 XML 文件，路径
 * {@code {语句根}/{数据源}/{命名空间}/{表名}.xml}。框架在文件里维护一个
 * {@code AUTUMN-DATA GENERATED START/END} 自动生成区，按表结构生成七条默认语句
 * （{@code findList/findPage/findOne/findById/save/update/delete}）；标记之外的使用方自定义语句
 * 逐字保留。查询条件用「参数实体 + XML 里的 {@code <if>}」，不再有第二套语句库或 Java 侧现拼 SQL。</p>
 *
 * <p>操作对象始终是 {@link MetaClass}：写用的实体由 {@link MetaMapper#newEntity(String)} 按当前表结构
 * 生成，读回来的行也是同一个类的实例，「读出来 — 改几列 — 写回去」中间不需要任何转换。</p>
 *
 * <p>示例按语句文件的三种状态分：</p>
 * <ul>
 *   <li>{@link #sevenDefaultStatementsDriveCrudWithoutHandWrittenXml()}：文件不存在，框架生成默认语句区；</li>
 *   <li>{@link #handWrittenStatementFileIsNeverRewritten()}：手写文件没有生成块，框架一个字都不动，
 *       只在内存补齐默认语句；</li>
 *   <li>{@link #generatedBlockIsRefreshedButCustomStatementsArePreserved()}：生成块与自定义语句共存，
 *       表结构变化只重写生成块；</li>
 *   <li>{@link #handWrittenMapperFileDrivesDynamicSql()}：classpath 里的手写文件
 *       {@code meta/default/default/demo_order.xml} 直接生效。</li>
 * </ul>
 */
@SpringBootTest(classes = UsingApplication.class)
class MetaMapperXmlUsingTest {

	/** 运行期建出来的表：演示「文件不存在才生成一次」。 */
	private static final String DYN = "dyn_form_demo";

	/** 建表脚本里已有的表：它的语句文件是手写的。 */
	private static final String ORDER = "demo_order";

	private static final String META_DIRECTORY = "target/autumn-meta";

	/**
	 * 使用方手写的自定义语句区：没有 GENERATED 标记，框架不会改写它，只在内存补上七条默认语句，
	 * 于是 {@code selectList/selectCount/insert/updateByKey} 与默认 CRUD 在同一份文件里共存。
	 */
	private static final String HAND_WRITTEN = """
			<?xml version="1.0" encoding="UTF-8"?>
			<mapper namespace="default/default/dyn_form_demo">
			    <select id="selectList">
			        SELECT id, order_no, amount, status, remark FROM ${table}
			        <where>
			            AND deleted_flag = 0
			            <if test="status != null">AND status = #{status}</if>
			            <if test="order_no != null">AND order_no = #{order_no}</if>
			        </where>
			        ORDER BY id
			    </select>
			    <select id="selectCount">SELECT COUNT(*) FROM ${table} WHERE deleted_flag = 0</select>
			    <insert id="insert">
			        INSERT INTO ${table}
			        <trim prefix="(" suffix=")" suffixOverrides=",">
			            <if test="order_no != null">order_no,</if>
			            <if test="amount != null">amount,</if>
			            <if test="status != null">status,</if>
			        </trim>
			        <trim prefix="VALUES (" suffix=")" suffixOverrides=",">
			            <if test="order_no != null">#{order_no},</if>
			            <if test="amount != null">#{amount},</if>
			            <if test="status != null">#{status},</if>
			        </trim>
			    </insert>
			    <update id="updateByKey">UPDATE ${table} SET remark = #{remark} WHERE id = #{id}</update>
			</mapper>
			""";

	@Autowired
	JdbcOperations jdbcOperations;

	@BeforeEach
	void prepareTables() {
		this.jdbcOperations.execute("DROP TABLE IF EXISTS " + DYN);
		this.jdbcOperations.execute("CREATE TABLE " + DYN
				+ " (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no VARCHAR(32) NOT NULL, amount DECIMAL(12,2),"
				+ " status INT, remark VARCHAR(64), channel VARCHAR(32), deleted_flag INT DEFAULT 0)");
		this.jdbcOperations.execute("DELETE FROM " + ORDER + " WHERE order_no LIKE 'SO-EX-%'");
		deleteStatementFile(DYN);
		deleteStatementFile(ORDER);
		MetaMapper.refresh(DYN);
		MetaMapper.defaultClient().refresh(ORDER);
	}

	/**
	 * ① 文件不存在：框架按表结构生成七条默认语句，CRUD 立刻可用；查询条件 = 参数实体 + {@code <if>}。
	 */
	@Test
	void sevenDefaultStatementsDriveCrudWithoutHandWrittenXml() {
		assertThat(MetaMapper.DEFAULT_STATEMENT_IDS).containsExactly(
				"findList", "findPage", "findOne", "findById", "save", "update", "delete");

		MetaClass order = MetaMapper.save(form(DYN, "order_no", "SO-1", "amount", "12.50", "status", 1));
		assertThat(order.get("id")).isNotNull(); // 自增主键回填在实体上

		MetaClass saved = MetaMapper.findById(DYN, order.get("id")).orElseThrow();
		assertThat(saved.getString("order_no")).isEqualTo("SO-1");
		assertThat(saved.getBigDecimal("amount")).isEqualByComparingTo("12.50");
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

		// 赋了值的列才进 WHERE：这就是参数实体 + <if>
		MetaClass query = params("status", 1);
		assertThat(MetaMapper.findList(DYN, query)).hasSize(2);
		Page<MetaClass> page = MetaMapper.findPage(DYN, query, Pageable.of(0, 1));
		assertThat(page.total()).isEqualTo(2);
		assertThat(page.content()).hasSize(1);
		assertThat(MetaMapper.count(DYN, query)).isEqualTo(2);
		assertThat(MetaMapper.exists(DYN, params("order_no", "SO-2"))).isTrue();

		// 比较 / 模糊 / 排序这类复杂条件写在 XML 自定义语句里：代码注册是同一套语法的内存覆盖层
		MetaMapperClient client = MetaMapper.defaultClient();
		try {
			client.registerStatement(DYN, "highValue",
					"SELECT order_no FROM ${table} WHERE amount >= #{min} ORDER BY amount DESC");
			assertThat(client.selectList(DYN, "highValue", params("min", new BigDecimal("50"))))
					.extracting(row -> row.getString("order_no")).containsExactly("SO-3");
			client.registerStatement(DYN, "byNo",
					"SELECT id FROM ${table} WHERE order_no LIKE #{kw} ORDER BY id");
			assertThat(client.selectList(DYN, "byNo", params("kw", "SO-%"))).hasSize(3);
		}
		finally {
			client.unregisterStatement(DYN, "highValue");
			client.unregisterStatement(DYN, "byNo");
		}

		// 文件按 {语句根}/{数据源}/{命名空间}/{表名}.xml 生成，自动生成区里就是那七条语句
		Path file = statementFile(DYN);
		assertThat(file).isNotNull();
		String generated = read(file);
		assertThat(generated).contains("AUTUMN-DATA GENERATED START", "AUTUMN-DATA GENERATED END");
		for (String id : MetaMapper.DEFAULT_STATEMENT_IDS) {
			assertThat(generated).contains("id=\"" + id + "\"");
		}
		assertThat(generated).contains("changed('"); // save/update 靠变更集区分「没动」与「置 NULL」
		assertThat(client.statementIds(DYN)).containsAll(MetaMapper.DEFAULT_STATEMENT_IDS);

		// 多数据源 / 业务划分：of(池, 命名空间) 把边界固定在客户端句柄上，各自一份语句文件
		MetaMapperClient erp = MetaMapper.of(MetaMapper.engine().defaultPoolName(), "erp");
		assertThat(erp.poolName()).isEqualTo(MetaMapper.engine().defaultPoolName());
		assertThat(erp.namespace()).isEqualTo("erp");
		assertThat(erp.statementIds(DYN)).containsAll(MetaMapper.DEFAULT_STATEMENT_IDS);
		assertThat(statementFile("erp", DYN)).isNotNull();
	}

	/**
	 * ② 手写文件（无 GENERATED 标记）是使用方资产：框架一个字都不改写，只在内存补齐七条默认语句。
	 */
	@Test
	void handWrittenStatementFileIsNeverRewritten() throws Exception {
		Files.createDirectories(statementDirectory());
		Path target = statementDirectory().resolve(DYN + ".xml");
		Files.writeString(target, HAND_WRITTEN);
		MetaMapper.clearStatementCache(DYN);

		MetaMapperClient client = MetaMapper.defaultClient();
		assertThat(client.statementIds(DYN))
				.contains("selectList", "selectCount", "insert", "updateByKey")
				.containsAll(MetaMapper.DEFAULT_STATEMENT_IDS);
		assertThat(Files.readString(target)).isEqualTo(HAND_WRITTEN);

		MetaClass order = MetaMapper.save(form(DYN, "order_no", "SO-1", "amount", "12.50", "status", 1));
		assertThat(client.selectCount(DYN, "selectCount", params())).isEqualTo(1);
		assertThat(client.selectList(DYN, "selectList", params("status", 1)))
				.extracting(row -> row.getString("order_no")).containsExactly("SO-1");
		assertThat(client.execute(DYN, "updateByKey",
				params("id", order.get("id"), "remark", "手写语句改的"))).isEqualTo(1);
		assertThat(MetaMapper.findById(DYN, order.get("id")).orElseThrow().getString("remark"))
				.isEqualTo("手写语句改的");
		assertThat(Files.readString(target)).isEqualTo(HAND_WRITTEN);

		// 表结构变化只刷新缓存，手写文件依然一个字不动
		this.jdbcOperations.execute("ALTER TABLE " + DYN + " ADD COLUMN courier VARCHAR(32)");
		MetaMapper.refresh(DYN);
		assertThat(Files.readString(target)).isEqualTo(HAND_WRITTEN);
		assertThat(client.statementIds(DYN)).contains("selectList", "findList");
	}

	/**
	 * ③ 生成块与自定义语句共存：改表加列后框架<b>只重写 GENERATED 块</b>，标记外内容逐字保留。
	 */
	@Test
	void generatedBlockIsRefreshedButCustomStatementsArePreserved() throws Exception {
		MetaClass order = MetaMapper.save(form(DYN, "order_no", "SO-1", "status", 1));
		Path file = statementFile(DYN);
		assertThat(file).isNotNull();
		String generated = read(file);

		String custom = "    <select id=\"myCustom\">SELECT COUNT(*) FROM ${table}</select>\n";
		Files.writeString(file, generated.replace("</mapper>", custom + "</mapper>"));
		MetaMapper.clearStatementCache(DYN);

		MetaMapperClient client = MetaMapper.defaultClient();
		assertThat(client.statementIds(DYN)).contains("myCustom", "findList", "delete");
		assertThat(client.selectCount(DYN, "myCustom", params())).isEqualTo(1);

		// 加列 + refresh：新列立刻可读写，生成块被重写，自定义语句还在
		this.jdbcOperations.execute("ALTER TABLE " + DYN + " ADD COLUMN courier VARCHAR(32)");
		MetaMapper.refresh(DYN);
		MetaClass row = MetaMapper.findById(DYN, order.get("id")).orElseThrow();
		row.set("courier", "SF");
		assertThat(MetaMapper.update(row)).isEqualTo(1);
		assertThat(MetaMapper.findById(DYN, order.get("id")).orElseThrow().getString("courier"))
				.isEqualTo("SF");

		String after = read(statementFile(DYN));
		assertThat(after).contains("AUTUMN-DATA GENERATED START", "AUTUMN-DATA GENERATED END");
		assertThat(after).contains("myCustom").containsIgnoringCase("courier");
		assertThat(client.statementIds(DYN)).contains("myCustom", "findList");
	}

	/**
	 * ④ 语句来自 classpath 的 {@code meta/default/default/demo_order.xml}：手写文件命中后不再落盘生成，
	 * 动态 SQL 的写法全在 XML 里，七条默认语句在内存补齐。
	 */
	@Test
	void handWrittenMapperFileDrivesDynamicSql() {
		MetaMapperClient client = MetaMapper.defaultClient();
		assertThat(statementFile(ORDER)).isNull(); // classpath 资源不会被复制到工作目录再生成一份
		assertThat(client.statementIds(ORDER))
				.contains("quickAdd", "search", "byOrderNo", "countMatching", "listSorted", "patch", "setAmount")
				.containsAll(MetaMapper.DEFAULT_STATEMENT_IDS);

		assertThat(client.execute(ORDER, "quickAdd",
				params("order_no", "SO-EX-1", "user_id", 9001, "amount", "10.00"))).isEqualTo(1);
		assertThat(client.execute(ORDER, "quickAdd",
				params("order_no", "SO-EX-2", "user_id", 9001, "amount", "90.00"))).isEqualTo(1);

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

		assertThat(client.selectCount(ORDER, "countMatching", params("user_id", 9001))).isEqualTo(2);
	}

	/**
	 * ⑤ 入口与语句类型要对得上；删除语义由 XML 里的 {@code delete} 语句决定，框架不再自行禁止物理删除。
	 */
	@Test
	void statementTypesIdsAndDeletionAreControlledByXml() {
		MetaMapperClient client = MetaMapper.defaultClient();

		assertThatThrownBy(() -> client.execute(ORDER, "search", params()))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("is a <select>");
		assertThatThrownBy(() -> client.selectList(ORDER, "patch", params("id", 1L, "amount", "1.00")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("is not a <select>");

		// 文件显式写了 <delete id="purgeById"> 就能执行；用不存在的 id 验证「允许执行」而不动种子数据
		assertThat(client.sqlFor(ORDER, "purgeById", params("id", -1L)).sql()).contains("DELETE FROM");
		assertThat(client.execute(ORDER, "purgeById", params("id", -1L))).isZero();
		assertThat(MetaMapper.findById(ORDER, 1L)).isPresent();

		// 默认 delete：没有逻辑删除列 → 物理 DELETE；有 deleted_flag → 逻辑删除 UPDATE
		assertThat(client.sqlFor(ORDER, "delete", params("id", -1L)).sql()).contains("DELETE FROM");
		assertThat(client.sqlFor(DYN, "delete", params("id", -1L)).sql()).contains("UPDATE");

		assertThatThrownBy(() -> client.selectList(ORDER, "byOrderNO", params("order_no", "SO-1001")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("no statement 'byOrderNO'")
				.hasMessageContaining("byOrderNo");
	}

	/**
	 * ⑥ 防注入的两条边界：{@code #{}} 的值永远不进 SQL 文本，{@code ${}} 只让标识符过白名单。
	 */
	@Test
	void valuesBindAndIdentifierFragmentsAreWhitelisted() {
		MetaMapperClient client = MetaMapper.defaultClient();
		String payload = "SO-1001' OR '1'='1";

		MarkedSql bound = client.sqlFor(ORDER, "byOrderNo", params("order_no", payload));
		assertThat(bound.sql()).doesNotContain(payload).doesNotContain("'").contains("?");
		assertThat(bound.values()).containsExactly(payload);
		assertThat(client.selectList(ORDER, "byOrderNo", params("order_no", payload))).isEmpty();

		assertThatThrownBy(() -> client.sqlFor(ORDER, "search", params("orderBy", "amount; DROP TABLE demo_order")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");
		assertThatThrownBy(() -> client.sqlFor(ORDER, "search", params("orderBy", "amount -- sort")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");
		assertThatThrownBy(() -> client.sqlFor(ORDER, "search", params("orderBy", "1=1")))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("not an identifier fragment");

		// 参数实体里不是元数据的列名根本进不了默认语句（逐列 <if> 只认真实列）
		MetaClass injected = params("order_no = 'x' OR 1=1", "y");
		assertThat(client.sqlFor(ORDER, "findList", injected).sql())
				.doesNotContain("OR 1=1").doesNotContain("'x'");
		assertThat(MetaMapper.findList(ORDER, injected)).hasSize(3);

		// 写方向：未知列在拼 SQL 之前就被拦下
		MetaClass bad = new MetaClass();
		bad.setName(ORDER);
		bad.set("not_a_column", 1);
		assertThatThrownBy(() -> MetaMapper.save(bad))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("unknown column");

		assertThat(client.selectCount(ORDER, "countMatching", params("user_id", 9001))).isZero();
		assertThat(MetaMapper.findList(ORDER, params("order_no", "SO-1001"))).hasSize(1);
	}

	/** 待写入的实体：形参是「列名, 值」成对，写过的列才是这次 INSERT/UPDATE 的范围。 */
	private static MetaClass form(String table, Object... nameValues) {
		return fill(MetaMapper.newEntity(table), nameValues);
	}

	/** 语句参数：列以外的开关、集合与排序名也放这里（动态属性）。 */
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

	/** 语句目录 = {语句根}/{数据源}/{命名空间}，路径与引擎里的唯一语句库保持一致。 */
	private Path statementDirectory() {
		return statementDirectory(MetaMapper.DEFAULT_NAMESPACE);
	}

	private Path statementDirectory(String namespace) {
		Path sql = MetaDirectories.of(META_DIRECTORY).sqlDirectory();
		if (sql == null) {
			return null;
		}
		return sql.resolve(MetaMapper.engine().defaultPoolName()).resolve(namespace);
	}

	/** 生成的文件名跟着元数据里的表名大小写走（H2 是大写、MySQL 习惯小写），这里按忽略大小写找。 */
	private Path statementFile(String table) {
		return statementFile(MetaMapper.DEFAULT_NAMESPACE, table);
	}

	private Path statementFile(String namespace, String table) {
		Path directory = statementDirectory(namespace);
		if (directory == null || !Files.isDirectory(directory)) {
			return null;
		}
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(path -> path.getFileName().toString().equalsIgnoreCase(table + ".xml"))
					.findFirst().orElse(null);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private void deleteStatementFile(String table) {
		Path file = statementFile(table);
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
