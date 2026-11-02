package xyz.wewin.autumn.data.jdbc.runtime;

import javax.sql.DataSource;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.dialect.DialectResolver;

/**
 * 无 Spring 引导：给定一个 {@link DataSource} 即可拿到仓储实例
 * （对标 MyBatis 的 {@code SqlSessionFactory.openSession}）。
 *
 * <p>自带 {@link JdbcConnectionSource}，不依赖任何 Spring 运行时；事务用 {@link #begin()}/{@link #commit()}
 * /{@link #rollback()} 手工控制。生成的 {@code <Repo>Impl} 只认 {@link AutumnJdbc}，因此完全可脱离 Spring 运行。</p>
 */
public final class Autumn {

	private final ConnectionSource connectionSource;

	private final Dialect dialect;

	private final FetchPolicy fetchPolicy;

	private final AutumnJdbc autumnJdbc;

	private Autumn(DataSource dataSource, Dialect dialect, FetchPolicy fetchPolicy) {
		this.connectionSource = new JdbcConnectionSource(dataSource);
		this.dialect = dialect;
		this.fetchPolicy = fetchPolicy;
		this.autumnJdbc = new AutumnJdbc(this.connectionSource, dialect, fetchPolicy);
	}

	/** 仅凭 DataSource 开箱即用：方言按 DataSource 元数据识别，取数策略用默认。 */
	public static Autumn using(DataSource dataSource) {
		return using(dataSource, DialectResolver.resolve(dataSource), FetchPolicy.defaults());
	}

	public static Autumn using(DataSource dataSource, Dialect dialect, FetchPolicy fetchPolicy) {
		return new Autumn(dataSource, dialect, fetchPolicy);
	}

	/** 便捷入口：{@code Autumn.create(UserRepository.class, dataSource)}。 */
	@SuppressWarnings("unchecked")
	public static <T> T create(Class<T> repositoryInterface, DataSource dataSource) {
		return using(dataSource).repository(repositoryInterface);
	}

	/**
	 * 取得仓储实例。实现类约定为接口同包下的 {@code <接口名>Impl}，且只有一个
	 * 接收 {@link AutumnJdbc} 的构造器（由 autumn-data-jdbc-compiler 生成）。
	 */
	@SuppressWarnings("unchecked")
	public <T> T repository(Class<T> repositoryInterface) {
		if (!repositoryInterface.isInterface()) {
			throw new IllegalArgumentException(repositoryInterface + " is not an interface");
		}
		String implName = repositoryInterface.getName() + "Impl";
		try {
			Class<?> implClass = Class.forName(implName, true, repositoryInterface.getClassLoader());
			java.lang.reflect.Constructor<?> ctor = implClass.getConstructor(AutumnJdbc.class);
			return (T) ctor.newInstance(this.autumnJdbc);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Failed to instantiate repository implementation " + implName, ex);
		}
	}

	public void begin() throws java.sql.SQLException {
		this.connectionSource.begin();
	}

	public void commit() throws java.sql.SQLException {
		this.connectionSource.commit();
	}

	public void rollback() throws java.sql.SQLException {
		this.connectionSource.rollback();
	}

	public void close() throws java.sql.SQLException {
		this.connectionSource.close();
	}
}
