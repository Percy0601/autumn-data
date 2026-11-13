package xyz.wewin.autumn.data.jdbc.runtime;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * 每个线程一份 {@link ConnectionSource} 的包装。
 *
 * <p>{@link ConnectionSource} 的实现大多持有“当前连接”字段（{@link JdbcConnectionSource}、
 * Spring 集成层的 {@code SpringConnectionSource} 都是如此），一个实例被并发请求共用时必然会串连接：
 * 甲线程的 {@code getConnection()} 把乙线程正在用的连接归还/替换掉。所以在共享场景（Spring 单例 Bean、
 * 动态层静态门面的 client）统一套一层本类——按线程懒建一份底层连接源，互不可见。</p>
 *
 * <p>线程内仍是“取-用-还”的常规语义：调用方在一次操作结束时调 {@link #close()}，把连接还给池；
 * 事务中则由 Spring 的事务管理器决定提交/回滚，{@link #begin()}/{@link #commit()}/{@link #rollback()}
 * 是否生效取决于底层实现。用完线程后可用 {@link #remove()} 清掉当前线程的条目。</p>
 */
public final class PerThreadConnectionSource implements ConnectionSource {

	private final Supplier<ConnectionSource> supplier;

	private final ThreadLocal<ConnectionSource> local;

	public PerThreadConnectionSource(Supplier<ConnectionSource> supplier) {
		if (supplier == null) {
			throw new IllegalArgumentException("supplier must not be null");
		}
		this.supplier = supplier;
		this.local = ThreadLocal.withInitial(supplier);
	}

	@Override
	public Connection getConnection() throws SQLException {
		return this.local.get().getConnection();
	}

	@Override
	public void begin() throws SQLException {
		this.local.get().begin();
	}

	@Override
	public void commit() throws SQLException {
		this.local.get().commit();
	}

	@Override
	public void rollback() throws SQLException {
		this.local.get().rollback();
	}

	@Override
	public void close() throws SQLException {
		this.local.get().close();
	}

	@Override
	public boolean isTransactionActive() {
		return this.local.get().isTransactionActive();
	}

	/** 当前线程的底层连接源（诊断/进阶用法）。 */
	public ConnectionSource current() {
		return this.local.get();
	}

	/** 底层连接源的创建工厂（诊断用）。 */
	public Supplier<ConnectionSource> supplier() {
		return this.supplier;
	}

	/** 供测试或容器关闭时清理当前线程的条目。 */
	public void remove() {
		this.local.remove();
	}
}
