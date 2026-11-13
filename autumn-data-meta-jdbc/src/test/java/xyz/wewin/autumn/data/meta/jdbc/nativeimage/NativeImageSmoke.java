package xyz.wewin.autumn.data.meta.jdbc.nativeimage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;

import org.h2.jdbcx.JdbcDataSource;

import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityStrategy;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaPlanCache;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlBuilder;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaStatementStore;

/**
 * Native Image 冒烟入口：不依赖 JUnit / Spring，直接跑一遍动态层 CRUD。
 *
 * <p>native 镜像里 {@code MetaEntityStrategy.auto()} 必须是 GENERIC，实体类型必须是
 * {@link MetaClass} 本身，全程没有 javac / defineClass / 反射。构建命令见
 * {@code docs/meta-dynamic-crud.md} 的「GraalVM Native Image」一节。</p>
 */
public final class NativeImageSmoke {

	private NativeImageSmoke() {
	}

	public static void main(String[] args) throws Exception {
		JdbcDataSource dataSource = new JdbcDataSource();
		dataSource.setURL("jdbc:h2:mem:native_smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
		dataSource.setUser("sa");
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE native_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
					+ "order_no VARCHAR(32) NOT NULL, amount DECIMAL(12,2), status INT, "
					+ "deleted_flag INT NOT NULL DEFAULT 0)");
		}

		MetaEntityStrategy strategy = MetaEntityStrategy.auto();
		if (strategy != MetaEntityStrategy.GENERIC) {
			throw new IllegalStateException("native image must use GENERIC entity strategy but was " + strategy);
		}

		Path mapperDirectory = Files.createTempDirectory("native-mappers");
		JdbcConnectionSource connections = new JdbcConnectionSource(dataSource);
		H2Dialect dialect = new H2Dialect();
		AutumnJdbc jdbc = new AutumnJdbc(connections, dialect, FetchPolicy.defaults());
		MetaTableRegistry tables = new MetaTableRegistry(dataSource, dialect, Set.of("deleted_flag"));
		MetaEntityRegistry entities = new MetaEntityRegistry(MetaEntityStrategy.auto());
		MetaStatementStore statements = new MetaStatementStore(mapperDirectory, true, dialect);
		MetaMapperClient client = new MetaMapperClient("native", jdbc, connections, tables,
				new MetaSqlBuilder(dialect), statements, entities, new MetaPlanCache(), 1000);

		MetaClass row = client.newEntity("native_order");
		row.set("order_no", "N001");
		row.set("amount", "18.80");
		row.set("status", 1);
		client.save(row);
		MetaClass second = client.newEntity("native_order");
		second.set("order_no", "N002");
		second.set("amount", "20.00");
		second.set("status", 1);
		client.save(second);

		MetaClass loaded = client.findById("native_order", row.get("id")).orElseThrow();
		if (loaded.getClass() != MetaClass.class) {
			throw new IllegalStateException("expected plain MetaClass but was " + loaded.getClass().getName());
		}
		if (client.findList("native_order", MetaQuery.all()).size() != 2) {
			throw new IllegalStateException("expected 2 rows");
		}
		System.out.println("NATIVE_SMOKE_OK strategy=" + strategy + " type=" + loaded.getClass().getName()
				+ " row=" + loaded.toJson());
	}
}
