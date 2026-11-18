package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.PerThreadConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaPlanCache;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlFileStore;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一 XML 语句库：{@code {根}/{数据源}/{命名空间}/{表名}.xml}。
 *
 * <p>七条默认语句（findList/findPage/findOne/findById/save/update/delete）始终可用；标记外的
 * 自定义语句逐字保留并优先于同名默认语句；{@code of(数据源, 命名空间)} 把寻址固定在调用边界上。</p>
 */
class MetaMapperMetaApiTest {

	private static final AtomicInteger DB_SEQ = new AtomicInteger();

	private static final MetaEntityRegistry ENTITIES = new MetaEntityRegistry();

	private static final String DEFAULT_NAMESPACE = "default";

	private JdbcDataSource dataSource;

	private MetaMapperClient client;

	private ConnectionSource connectionSource;

	private MetaTableRegistry registry;

	/** 语句库根目录：{@code <root>/{数据源}/{命名空间}/{表}.xml}。 */
	@TempDir
	Path sqlRoot;

	@BeforeEach
	void setUp() throws Exception {
		this.dataSource = new JdbcDataSource();
		this.dataSource.setURL("jdbc:h2:mem:meta_api_" + DB_SEQ.incrementAndGet()
				+ ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		this.dataSource.setUser("sa");
		try (Connection connection = this.dataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE form_order ("
					+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
					+ "order_no VARCHAR(32) NOT NULL, "
					+ "amount DECIMAL(12,2), "
					+ "status INT, "
					+ "create_time TIMESTAMP, "
					+ "deleted_flag INT NOT NULL DEFAULT 0)");
		}
		this.connectionSource = new PerThreadConnectionSource(() -> new JdbcConnectionSource(this.dataSource));
		this.registry = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of("deleted_flag"));
		this.client = client("test", this.registry);
	}

	@Test
	void sevenDefaultStatementsAreAlwaysAvailableEvenWithoutAFile() {
		assertThat(this.client.statementIds("form_order")).containsExactlyInAnyOrder(
				"findList", "findPage", "findOne", "findById", "save", "update", "delete");

		MetaClass saved = this.client.save(row("A001", "12.50", 1));
		assertThat(saved.get("id")).isInstanceOf(Number.class);
		assertThat(this.client.findList("form_order", params("status", 1))).hasSize(1);
		assertThat(this.client.cacheStats()).containsEntry("statementTables", 1L);
	}

	@Test
	void customStatementsArePreservedAndDefaultsAreFilledIn() throws IOException {
		String handwritten = """
				<mapper namespace="test/default/form_order">
				    <select id="findByNo">
				        SELECT id, order_no, status FROM form_order WHERE order_no = #{order_no}
				    </select>
				</mapper>
				""";
		Path file = write("test", DEFAULT_NAMESPACE, handwritten);
		this.client.save(row("D001", "1.00", 1));
		this.client.save(row("D002", "2.00", 1));

		assertThat(this.client.statementIds("form_order")).contains("findByNo", "findList", "delete");
		assertThat(this.client.selectList("form_order", "findByNo", params("order_no", "D002")))
				.extracting(row -> row.getString("order_no")).containsExactly("D002");
		// 手写文件没有 GENERATED 标记：框架一个字都不动，默认语句只在内存里补齐。
		assertThat(Files.readString(file)).isEqualTo(handwritten);
	}

	@Test
	void namespaceIsBoundOnTheClientHandle() throws IOException {
		write("test", "a", "<mapper namespace=\"a\"><select id=\"tag\">SELECT 'a' AS tag FROM form_order</select></mapper>");
		write("test", "b", "<mapper namespace=\"b\"><select id=\"tag\">SELECT 'b' AS tag FROM form_order</select></mapper>");

		MetaMapperClient a = this.client.forNamespace("a");
		MetaMapperClient b = this.client.forNamespace("b");
		assertThat(a.namespace()).isEqualTo("a");
		assertThat(b.namespace()).isEqualTo("b");
		assertThat(a.poolName()).isEqualTo("test");
		assertThat(a).isNotSameAs(b);
		assertThat(a.sqlFor("form_order", "tag", params()).sql()).contains("'a'");
		assertThat(b.sqlFor("form_order", "tag", params()).sql()).contains("'b'");
	}

	@Test
	void deleteUsesLogicDeleteWhenTheTableHasALogicDeleteColumn() {
		MetaClass saved = this.client.save(row("F001", "1.00", 1));

		assertThat(this.client.delete(saved)).isEqualTo(1);
		assertThat(this.client.findById("form_order", saved.get("id"))).isEmpty();
		// 逻辑删除只是把标记列置 1，行还在
		assertThat(this.client.countBySql("form_order", "SELECT COUNT(*) FROM form_order", null)).isEqualTo(1);
	}

	@Test
	void deleteUsesPhysicalDeleteWithoutALogicDeleteColumn() {
		MetaTableRegistry plain = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of());
		MetaMapperClient noLogicDelete = client("test", plain);
		MetaClass saved = noLogicDelete.save(row("G001", "1.00", 1));

		assertThat(noLogicDelete.delete(saved)).isEqualTo(1);
		assertThat(noLogicDelete.countBySql("form_order", "SELECT COUNT(*) FROM form_order", null)).isZero();
	}

	@Test
	void customDeleteStatementOverridesTheGeneratedDefault() throws IOException {
		write("test", DEFAULT_NAMESPACE, """
				<mapper namespace="test/default/form_order">
				    <delete id="delete">DELETE FROM form_order WHERE id = #{id}</delete>
				</mapper>
				""");
		MetaClass saved = this.client.save(row("E001", "5.00", 1));

		// 即使表配了逻辑删除列，文件里显式写 <delete> 就按物理删除执行。
		assertThat(this.client.delete(saved)).isEqualTo(1);
		assertThat(this.client.countBySql("form_order", "SELECT COUNT(*) FROM form_order", null)).isZero();
	}

	@Test
	void missingStatementIdReportsWhatIsAvailable() throws IOException {
		write("test", DEFAULT_NAMESPACE, "<mapper namespace=\"x\"><select id=\"myQuery\">SELECT 1 FROM form_order</select></mapper>");

		assertThatThrownBy(() -> this.client.sqlFor("form_order", "nope", params()))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("nope")
				.hasMessageContaining("available:")
				.hasMessageContaining("myQuery")
				.hasMessageContaining("findList");
	}

	@Test
	void statementTypeMismatchIsRejected() throws IOException {
		write("test", DEFAULT_NAMESPACE,
				"<mapper namespace=\"x\"><update id=\"findList\">UPDATE form_order SET status = 1</update></mapper>");

		assertThatThrownBy(() -> this.client.findList("form_order", params()))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("is not a <select>");
	}

	@Test
	void staticFacadeUsesDefaultPoolAndBoundNamespace() {
		MetaMapper.install(MetaMapperEngine.forDataSource("default", this.dataSource,
				MetaMapperOptions.inMemory()));
		MetaClass saved = this.client.save(row("H001", "8.00", 1));

		assertThat(MetaMapper.DEFAULT_STATEMENT_IDS).containsExactly("findList", "findPage", "findOne",
				"findById", "save", "update", "delete");
		assertThat(MetaMapper.findList("form_order", params("order_no", "H001"))).hasSize(1);
		assertThat(MetaMapper.of("default", "erp").findById("form_order", saved.get("id"))).isPresent();
	}

	// ------------------------------------------------------------------
	// 辅助
	// ------------------------------------------------------------------

	private MetaMapperClient client(String poolName, MetaTableRegistry tableRegistry) {
		return client(poolName, tableRegistry, false);
	}

	private MetaMapperClient client(String poolName, MetaTableRegistry tableRegistry, boolean generateFiles) {
		AutumnJdbc jdbc = new AutumnJdbc(this.connectionSource, new H2Dialect(), FetchPolicy.defaults());
		MetaSqlFileStore statements = new MetaSqlFileStore(this.sqlRoot, getClass().getClassLoader(),
				10_000, new MetaSqlGenerator(new H2Dialect()), generateFiles);
		return new MetaMapperClient(poolName, jdbc, this.connectionSource, tableRegistry,
				ENTITIES, new MetaPlanCache(), 1000, statements);
	}

	private Path write(String datasource, String namespace, String content) throws IOException {
		Path file = this.sqlRoot.resolve(datasource).resolve(namespace).resolve("form_order.xml");
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	/** 待写入的实体：用 newEntity 拿到带表名的实例。 */
	private MetaClass row(String orderNo, String amount, int status) {
		MetaClass entity = this.client.newEntity("form_order");
		entity.set("order_no", orderNo);
		entity.set("amount", amount);
		entity.set("status", status);
		return entity;
	}

	/** 语句参数实体：只放赋过值的属性，列以外的名字也能进模板作用域。 */
	private static MetaClass params(Object... nameValues) {
		MetaClass entity = new MetaClass();
		for (int i = 0; i < nameValues.length; i += 2) {
			entity.set((String) nameValues[i], nameValues[i + 1]);
		}
		return entity;
	}

}
