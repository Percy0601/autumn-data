package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
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
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaPlanCache;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlBuilder;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlFileStore;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaStatementStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code metaFind / metaSave / metaUpdate / metaDelete} 的两套 API：
 * 一套走默认数据源 + 默认命名空间（{@code meta/{池名}/default/{表}.xml}），
 * 一套把数据源 / 命名空间 / 表 / 语句 id 全部显式给出。
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
		// 池名用 test：classpath 里只有 default/default 的样例文件，不会串到这些用例
		this.client = client("test", this.registry);
	}

	@Test
	void metaFindRunsTheStatementFromTheFile() throws IOException {
		write("test", DEFAULT_NAMESPACE, """
				<mapper namespace="test/default/form_order">
				    <select id="metaFind">
				        SELECT id, order_no, status FROM form_order ORDER BY id
				    </select>
				</mapper>
				""");
		this.client.save(row("A001", "12.50", 1));
		this.client.save(row("A002", "3.40", 1));

		List<MetaClass> rows = this.client.metaFind("form_order", MetaQuery.all());

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).getString("order_no")).isEqualTo("A001");
		// 文件里的投影只有三列：没投影的列不会被补出来，说明 SQL 确实来自文件
		assertThat(rows.get(0).get("amount")).isNull();
		assertThat(this.client.cacheStats()).containsEntry("metaSqlFiles", 1L);
	}

	@Test
	void metaFindFallsBackToGeneratedSqlWhenNoStatementExists() {
		this.client.save(row("B001", "1.00", 1));
		this.client.save(row("B002", "2.00", 2));

		List<MetaClass> matched = this.client.metaFind("form_order", MetaQuery.by("status", 2));

		assertThat(matched).hasSize(1);
		assertThat(matched.get(0).getString("order_no")).isEqualTo("B002");
		assertThat(matched.get(0).getBigDecimal("amount")).isEqualByComparingTo("2.00");
	}

	@Test
	void metaQueryConditionsAndOrdersAreLaidOverTheFileStatement() throws IOException {
		write("test", DEFAULT_NAMESPACE, """
				<select id="metaFind">SELECT id, order_no, status FROM form_order</select>
				""");
		this.client.save(row("C001", "1.00", 1));
		this.client.save(row("C002", "2.00", 2));
		this.client.save(row("C003", "3.00", 1));

		List<MetaClass> rows = this.client.metaFind("form_order",
				MetaQuery.by("status", 1).orderBy("id", MetaQuery.Direction.DESC));

		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).getString("order_no")).isEqualTo("C003");
		assertThat(rows.get(1).getString("order_no")).isEqualTo("C001");
	}

	@Test
	void fullApiRunsAnyStatementIdWithItsParameters() throws IOException {
		write("test", DEFAULT_NAMESPACE, """
				<select id="metaFind">SELECT id, order_no, status FROM form_order</select>
				<select id="findByNo">
				    SELECT id, order_no, status FROM form_order WHERE order_no = #{order_no}
				</select>
				""");
		this.client.save(row("D001", "1.00", 1));
		this.client.save(row("D002", "2.00", 1));

		List<MetaClass> rows = this.client.metaFind("test", DEFAULT_NAMESPACE, "form_order", "findByNo",
				params("order_no", "D002"));

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getString("order_no")).isEqualTo("D002");
		assertThat(this.client.metaStatementIds("test", DEFAULT_NAMESPACE, "form_order"))
				.containsExactly("metaFind", "findByNo");
	}

	@Test
	void metaSaveUpdateDeleteUseTheFileStatements() throws IOException {
		write("test", DEFAULT_NAMESPACE, """
				<mapper namespace="test/default/form_order">
				    <insert id="metaSave">
				        INSERT INTO form_order (order_no, amount, status) VALUES (#{order_no}, #{amount}, #{status})
				    </insert>
				    <update id="metaUpdate">
				        UPDATE form_order SET status = #{status}, amount = #{amount} WHERE id = #{id}
				    </update>
				    <delete id="metaDelete">DELETE FROM form_order WHERE id = #{id}</delete>
				</mapper>
				""");

		MetaClass saved = this.client.metaSave(row("E001", "5.00", 1));
		assertThat(saved.get("id")).isInstanceOf(Number.class); // 自增主键回填
		assertThat(saved.hasChanges()).isFalse();

		MetaClass row = this.client.findById("form_order", saved.get("id")).orElseThrow();
		row.set("status", 9);
		assertThat(this.client.metaUpdate(row)).isEqualTo(1);
		assertThat(this.client.findById("form_order", saved.get("id")).orElseThrow().getInt("status")).isEqualTo(9);

		assertThat(this.client.metaDelete(row)).isEqualTo(1);
		assertThat(this.client.findById("form_order", saved.get("id"))).isEmpty();
	}

	@Test
	void metaDeleteDegradesToLogicDeleteWithoutAStatement() {
		MetaClass saved = this.client.save(row("F001", "1.00", 1));

		assertThat(this.client.metaDelete(saved)).isEqualTo(1);

		assertThat(this.client.findById("form_order", saved.get("id"))).isEmpty(); // 逻辑删除后查不到
		assertThat(this.client.countBySql("form_order", "SELECT COUNT(*) FROM form_order", null)).isEqualTo(1);
	}

	@Test
	void metaDeleteFailsWhenNeitherStatementNorLogicColumnExists() {
		MetaTableRegistry plain = new MetaTableRegistry(this.dataSource, new H2Dialect(), Set.of());
		MetaMapperClient noLogicDelete = client("test", plain);
		MetaClass saved = noLogicDelete.save(row("G001", "1.00", 1));

		assertThatThrownBy(() -> noLogicDelete.metaDelete(saved)).isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("does not generate physical deletes");
	}

	@Test
	void missingStatementIdReportsWhatTheFileHas() throws IOException {
		write("test", DEFAULT_NAMESPACE, """
				<select id="metaFind">SELECT id FROM form_order</select>
				""");

		assertThatThrownBy(() -> this.client.metaFind("test", DEFAULT_NAMESPACE, "form_order", "nope", null))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("available: [metaFind]");
	}

	@Test
	void statementTypeMismatchIsRejected() throws IOException {
		write("test", DEFAULT_NAMESPACE, """
				<update id="metaFind">UPDATE form_order SET status = 1</update>
				""");

		assertThatThrownBy(() -> this.client.metaFind("form_order", MetaQuery.all()))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("must be a <select>");
	}

	@Test
	void staticFacadeReadsTheDefaultNamespaceFromClasspath() {
		// classpath:meta/default/default/form_order.xml（见 src/test/resources）
		MetaMapper.install(MetaMapperEngine.forDataSource("default", this.dataSource,
				xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperOptions.inMemory()));
		this.client.save(row("H001", "8.00", 1));

		List<MetaClass> rows = MetaMapper.metaFind("form_order", MetaQuery.all());

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getString("order_no")).isEqualTo("H001");
		assertThat(rows.get(0).get("amount")).isNull(); // 文件里只投影了三列
	}

	// ------------------------------------------------------------------
	// 辅助
	// ------------------------------------------------------------------

	private MetaMapperClient client(String poolName, MetaTableRegistry tableRegistry) {
		AutumnJdbc jdbc = new AutumnJdbc(this.connectionSource, new H2Dialect(), FetchPolicy.defaults());
		MetaStatementStore statements = new MetaStatementStore(this.sqlRoot.resolve("mapper"), false,
				new H2Dialect());
		return new MetaMapperClient(poolName, jdbc, this.connectionSource, tableRegistry,
				new MetaSqlBuilder(new H2Dialect()), statements, ENTITIES, new MetaPlanCache(), 1000,
				new MetaSqlFileStore(this.sqlRoot));
	}

	private Path write(String datasource, String namespace, String content) throws IOException {
		Path file = this.sqlRoot.resolve(datasource).resolve(namespace).resolve("form_order.xml");
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private MetaClass row(String orderNo, String amount, int status) {
		return params("order_no", orderNo, "amount", amount, "status", status);
	}

	private MetaClass params(Object... nameValues) {
		MetaClass entity = this.client.newEntity("form_order");
		for (int i = 0; i < nameValues.length; i += 2) {
			entity.set((String) nameValues[i], nameValues[i + 1]);
		}
		return entity;
	}

}
