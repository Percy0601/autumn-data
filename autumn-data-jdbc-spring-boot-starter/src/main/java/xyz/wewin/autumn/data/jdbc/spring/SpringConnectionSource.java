package xyz.wewin.autumn.data.jdbc.spring;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;

/**
 * Spring 环境下的 {@link ConnectionSource}：连接委托给 Spring 的 {@link DataSourceUtils}，
 * 因此生成代码自动加入 {@code @Transactional}，无需任何适配代码。
 *
 * <p>事务语义（对标 MyBatis 的 {@code SpringManagedTransaction}）：
 * <ul>
 *   <li>处于 Spring 事务中：取的是事务绑定的同一个连接；{@link #begin()}/{@link #commit()}/
 *       {@link #rollback()} 全部 no-op，提交/回滚由 spring-tx 负责；</li>
 *   <li>无 Spring 事务时手动 {@link #begin()}：关闭 autocommit 开启本地事务，
 *       {@link #commit()}/{@link #rollback()} 自管，行为与原生 JDBC 一致；</li>
 *   <li>自动提交（无事务）模式：每次取连接都在上一条语句用完后归还连接池
 *       （{@code DataSourceUtils.releaseConnection}），不会长期霸占一条连接。</li>
 * </ul>
 *
 * 实现 {@link DisposableBean}，容器关闭时把持有的连接归还给连接池。
 */
public class SpringConnectionSource implements ConnectionSource, DisposableBean {

	private final DataSource dataSource;

	private Connection current;

	private boolean manualTransaction;

	public SpringConnectionSource(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public Connection getConnection() throws SQLException {
		// 处于 Spring 事务：DataSourceUtils 返回事务绑定的同一连接，每次直接取，绝不沿用缓存，
		// 否则会复用上一条（已回滚）事务遗留的陈旧连接而脱离当前事务
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return DataSourceUtils.getConnection(this.dataSource);
		}
		// 无 Spring 事务、但已手动 begin()：复用已开启 autocommit=false 的那条连接（自己拥有）
		if (this.manualTransaction && this.current != null) {
			return this.current;
		}
		// 自动提交空闲态：上一条语句用完后归还连接池，下次取新的，避免长期占用一条连接
		if (this.current != null) {
			DataSourceUtils.releaseConnection(this.current, this.dataSource);
			this.current = null;
		}
		this.current = DataSourceUtils.getConnection(this.dataSource);
		return this.current;
	}

	@Override
	public void begin() throws SQLException {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return; // Spring 管着事务
		}
		Connection conn = getConnection();
		conn.setAutoCommit(false);
		this.manualTransaction = true;
	}

	@Override
	public void commit() throws SQLException {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return;
		}
		if (this.manualTransaction && this.current != null) {
			this.current.commit();
			this.current.setAutoCommit(true);
			this.manualTransaction = false;
		}
	}

	@Override
	public void rollback() throws SQLException {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			return;
		}
		if (this.manualTransaction && this.current != null) {
			this.current.rollback();
			this.current.setAutoCommit(true);
			this.manualTransaction = false;
		}
	}

	@Override
	public void close() throws SQLException {
		this.manualTransaction = false;
		Connection conn = this.current;
		this.current = null;
		if (conn != null) {
			DataSourceUtils.releaseConnection(conn, this.dataSource);
		}
	}

	@Override
	public boolean isTransactionActive() {
		return this.manualTransaction || TransactionSynchronizationManager.isActualTransactionActive();
	}

	@Override
	public void destroy() throws Exception {
		close();
	}
}
