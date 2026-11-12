package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;

/**
 * 每个线程一份 {@link ConnectionSource} 的包装。
 *
 * <p>静态门面会被并发请求共用，而底层 {@code ConnectionSource} 实现大多持有“当前连接”字段
 * （{@code JdbcConnectionSource} / {@code SpringConnectionSource} 都是如此），直接共享会串连接。
 * 这里按线程懒建一份，动态查询每次操作结束后由 {@link MetaMapperClient} 调 {@link #close()} 归还。</p>
 */
public final class PerThreadConnectionSource implements ConnectionSource {

	private final Supplier<ConnectionSource> supplier;

	private final ThreadLocal<ConnectionSource> local;

	public PerThreadConnectionSource(Supplier<ConnectionSource> supplier) {
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

	/** 供测试或容器关闭时清理当前线程的条目。 */
	public void remove() {
		this.local.remove();
	}
}
