package xyz.wewin.autumn.data.bench;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

/**
 * MyBatis 侧的一次性装配：读 {@code mybatis-config.xml}。
 *
 * <p>连接池通过 {@link HikariBenchDataSourceFactory} 指向 {@link BenchDatabase} 的那个共享
 * HikariCP 实例，所以两边取连接的开销是同一份。</p>
 */
public final class MyBatisBench {

	private static final AtomicReference<SqlSessionFactory> FACTORY = new AtomicReference<>();

	private MyBatisBench() {
	}

	public static SqlSessionFactory factory() {
		SqlSessionFactory existing = FACTORY.get();
		if (existing != null) {
			return existing;
		}
		SqlSessionFactory created = build();
		return FACTORY.compareAndSet(null, created) ? created : FACTORY.get();
	}

	private static SqlSessionFactory build() {
		try (InputStream input = Resources.getResourceAsStream("mybatis-config.xml")) {
			return new SqlSessionFactoryBuilder().build(input);
		}
		catch (IOException ex) {
			throw new IllegalStateException("failed to load mybatis-config.xml", ex);
		}
	}

	/** 单条语句的完全限定 id，供纯映射基准直接取 MappedStatement。 */
	public static String statementId(String method) {
		return BenchMapper.class.getName() + "." + method;
	}

}
