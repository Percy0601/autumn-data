package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.math.BigDecimal;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaType;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaModelStore;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;
import xyz.wewin.autumn.data.meta.jdbc.sql.MarkedSql;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaPlanCache;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlFileStore;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档示例的端到端校验：{@code docs/examples/meta-data} 下的 DEMO_ORDER.json 与 DEMO_ORDER.xml
 * 被复制到本模块 test resources，这里用真实 H2 表跑一遍，确保示例可直接复制使用。
 */
class MetaExampleDefinitionTest {

	private static final AtomicInteger DB_SEQ = new AtomicInteger();

	private MetaMapperClient client;

	private MetaTableRegistry registry;

	@BeforeEach
	void setUp() throws Exception {
		JdbcDataSource dataSource = new JdbcDataSource();
		dataSource.setURL("jdbc:h2:mem:example_" + DB_SEQ.incrementAndGet()
				+ ";MODE=MySQL;DATABASE_TO_LOWER=FALSE;DB_CLOSE_DELAY=-1");
		dataSource.setUser("sa");
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE DEMO_ORDER ("
					+ "ID BIGINT AUTO_INCREMENT PRIMARY KEY, USER_ID BIGINT, "
					+ "ORDER_NO VARCHAR(32) NOT NULL, AMOUNT DECIMAL(12,2))");
		}

		// JSON 定义目录与 XML 示例是同一份文件：classpath 里的 spec/xml，框架只读。
		URL definition = getClass().getClassLoader()
				.getResource("meta-data/example-default/example-default/spec/DEMO_ORDER.json");
		Path specDirectory = Paths.get(definition.toURI()).getParent();
		MetaModelStore models = new MetaModelStore(specDirectory, false, 16);
		this.registry = new MetaTableRegistry(dataSource, new H2Dialect(), Set.of(), models, 100);
		JdbcConnectionSource connections = new JdbcConnectionSource(dataSource);
		AutumnJdbc jdbc = new AutumnJdbc(connections, new H2Dialect(), FetchPolicy.defaults());
		MetaSqlFileStore statements = new MetaSqlFileStore((xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories) null,
				getClass().getClassLoader(), 1000, new MetaSqlGenerator(new H2Dialect()), false);
		this.client = new MetaMapperClient("example", "example", jdbc, connections, this.registry,
				new MetaEntityRegistry(), new MetaPlanCache(), 1000, statements);
	}

	@Test
	void jsonDefinitionDrivesClassShapeAndTypedValues() {
		MetaTable table = this.registry.require("DEMO_ORDER");
		assertThat(table.name()).isEqualTo("DEMO_ORDER");
		assertThat(table.schema()).isEqualTo("PUBLIC");
		assertThat(table.metaClass().getMetaType()).isEqualTo(MetaType.META_TYPE_ENTITY);
		assertThat(table.findColumn("ID").isPrimaryKey()).isTrue();
		assertThat(table.findColumn("ID").isAutoIncrement()).isTrue();
		assertThat(table.findColumn("AMOUNT").getType()).isEqualTo("java.math.BigDecimal");
		assertThat(table.findColumn("ORDER_NO").getJdbcType()).isEqualTo(java.sql.Types.VARCHAR);

		MetaClass row = this.client.newEntity("DEMO_ORDER");
		row.set("user_id", 9L);
		row.set("order_no", "A-001");
		row.set("amount", new BigDecimal("12.50"));
		this.client.save(row);
		assertThat(row.get("id")).isNotNull();

		MetaClass loaded = this.client.findById("DEMO_ORDER", row.get("id")).orElseThrow();
		assertThat(loaded.property("AMOUNT").getValue()).isInstanceOf(BigDecimal.class);
		assertThat(loaded.getBigDecimal("amount")).isEqualByComparingTo("12.50");
		assertThat(loaded.getString("order_no")).isEqualTo("A-001");
	}

	@Test
	void fullXmlExampleRunsAndOverridesTheDefaultStatement() {
		MetaClass row = this.client.newEntity("DEMO_ORDER");
		row.set("user_id", 9L);
		row.set("order_no", "B-001");
		row.set("amount", new BigDecimal("88.00"));
		this.client.save(row);
		Object id = row.get("id");

		assertThat(this.client.statementIds("DEMO_ORDER")).contains("search", "byOrderNo", "countMatching",
				"quickAdd", "patchEntity", "setAmount", "purgeById");

		// where + if + foreach + include + choose + resultMap
		List<MetaClass> found = this.client.selectList("DEMO_ORDER", "search",
				params("ids", List.of(id), "latest", true));
		assertThat(found).hasSize(1);
		assertThat(found.get(0).getString("order_no")).isEqualTo("B-001");
		assertThat(this.client.selectOne("DEMO_ORDER", "byOrderNo", params("order_no", "B-001")).orElseThrow()
				.getBigDecimal("amount")).isEqualByComparingTo("88.00");
		assertThat(this.client.selectCount("DEMO_ORDER", "countMatching", params("user_id", 9L))).isEqualTo(1);

		// spec 同 id 覆盖 auto：findOne 按金额倒序
		MarkedSql overridden = this.client.sqlFor("DEMO_ORDER", "findOne", params("user_id", 9L));
		assertThat(overridden.sql()).contains("ORDER BY amount DESC");

		// 写语句：不判空的 SET 会把 null 写进去；显式 delete 走物理删除
		assertThat(this.client.execute("DEMO_ORDER", "setAmount",
				params("id", id, "amount", new BigDecimal("99.90")))).isEqualTo(1);
		assertThat(this.client.findById("DEMO_ORDER", id).orElseThrow().getBigDecimal("amount"))
				.isEqualByComparingTo("99.90");
		assertThat(this.client.execute("DEMO_ORDER", "purgeById", params("id", id))).isEqualTo(1);
		assertThat(this.client.findById("DEMO_ORDER", id)).isEmpty();
	}

	private static MetaClass params(Object... nameValues) {
		MetaClass params = new MetaClass();
		for (int i = 0; i < nameValues.length; i += 2) {
			params.set((String) nameValues[i], nameValues[i + 1]);
		}
		return params;
	}
}
