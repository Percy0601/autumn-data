package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityModel;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityStrategy;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaPlanCache;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlBuilder;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaStatementStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GENERIC 策略（GraalVM Native Image 用）跑完整链路：不生成类、不编译、不反射，
 * 但对外仍然是 {@link MetaClass}，CRUD / 条件查询 / 命名语句 / 部分更新语义与 CODE 一致。
 */
class MetaEntityGenericModeTest {

	private static final AtomicInteger DB_SEQ = new AtomicInteger();

	@TempDir
	Path mapperDirectory;

	@Test
	void runsCrudWithoutAnyCodeGeneration() throws Exception {
		JdbcDataSource dataSource = new JdbcDataSource();
		dataSource.setURL("jdbc:h2:mem:generic_" + DB_SEQ.incrementAndGet()
				+ ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		dataSource.setUser("sa");
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE generic_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
					+ "order_no VARCHAR(32) NOT NULL, amount DECIMAL(12,2), status INT, "
					+ "deleted_flag INT NOT NULL DEFAULT 0)");
		}

		JdbcConnectionSource connections = new JdbcConnectionSource(dataSource);
		AutumnJdbc jdbc = new AutumnJdbc(connections, new H2Dialect(), FetchPolicy.defaults());
		MetaTableRegistry tables = new MetaTableRegistry(dataSource, new H2Dialect(), Set.of("deleted_flag"));
		MetaEntityRegistry entities = new MetaEntityRegistry(MetaEntityStrategy.GENERIC);
		MetaStatementStore statements = new MetaStatementStore(this.mapperDirectory, true, new H2Dialect());
		MetaMapperClient client = new MetaMapperClient("test", jdbc, connections, tables,
				new MetaSqlBuilder(new H2Dialect()), statements, entities, new MetaPlanCache(), 1000);

		MetaEntityModel model = entities.model(tables.require("generic_order"));
		assertThat(model.entityType()).isEqualTo(MetaClass.class);
		assertThat(model.source()).isNull();
		assertThat(model.qualifiedName()).isEqualTo(MetaClass.class.getName());
		assertThat(entities.strategy()).isEqualTo(MetaEntityStrategy.GENERIC);

		MetaClass row = client.newEntity("generic_order");
		assertThat(row.getClass()).isEqualTo(MetaClass.class);
		assertThat(row.getProperties()).hasSize(5);
		assertThat(row.get("order_no")).isNull();
		assertThat(row.hasChanges()).isFalse();

		row.set("order_no", "G001");
		row.set("amount", "12.50");
		row.set("status", 1);
		MetaClass saved = client.save(row);
		assertThat(saved.get("id")).isInstanceOf(Number.class);

		MetaClass loaded = client.findById("generic_order", saved.get("id")).orElseThrow();
		assertThat(loaded.getString("order_no")).isEqualTo("G001");
		assertThat(loaded.getBigDecimal("amount")).isEqualByComparingTo("12.50");
		assertThat(loaded.getInt("status")).isEqualTo(1);
		// 读出来的行是干净的：后续 update 只写用户真正改过的列
		assertThat(loaded.hasChanges()).isFalse();

		client.save(entity(client, "G002", 2));
		List<MetaClass> rows = client.findList("generic_order", MetaQuery.all());
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0)).isNotSameAs(rows.get(1));
		assertThat(rows).extracting(item -> item.getString("order_no")).containsExactly("G001", "G002");

		loaded.set("status", 3);
		assertThat(client.update(loaded)).isEqualTo(1);
		assertThat(client.findById("generic_order", saved.get("id")).orElseThrow().getInt("status")).isEqualTo(3);

		// 命名语句：首次访问按表结构生成 Mapper XML，GENERIC 下同样可用
		assertThat(client.selectCount("generic_order", "selectCount", params())).isEqualTo(2);
		assertThat(client.selectList("generic_order", "selectList", params("status", 3)))
				.extracting(item -> item.getString("order_no")).containsExactly("G001");
		assertThat(Files.exists(this.mapperDirectory.resolve("generic_order.xml"))).isTrue();
	}

	private static MetaClass entity(MetaMapperClient client, String orderNo, int status) {
		MetaClass entity = client.newEntity("generic_order");
		entity.set("order_no", orderNo);
		entity.set("amount", "20.00");
		entity.set("status", status);
		return entity;
	}

	private static MetaClass params(Object... nameValues) {
		MetaClass entity = new MetaClass();
		for (int i = 0; i < nameValues.length; i += 2) {
			entity.set((String) nameValues[i], nameValues[i + 1]);
		}
		return entity;
	}
}
