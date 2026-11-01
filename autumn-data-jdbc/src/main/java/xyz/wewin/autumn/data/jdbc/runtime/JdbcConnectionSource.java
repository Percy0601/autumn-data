package xyz.wewin.autumn.data.jdbc.runtime;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * 原生 JDBC 实现的 {@link ConnectionSource}：自己持有连接、管理 autocommit 与提交/回滚。
 * 无 Spring 时直接使用（如 {@link Autumn} 引导）。
 *
 * <p>未调用 {@link #begin()} 时连接保持 autocommit=true，每条语句自动提交，等价于当前单条操作的行为；
 * 调用 {@link #begin()} 后关闭 autocommit，直到 {@link #commit()}/{@link #rollback()}。</p>
 */
public class JdbcConnectionSource implements ConnectionSource {

	private final DataSource dataSource;

	private Connection connection;

	private boolean transactionActive;

	public JdbcConnectionSource(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public Connection getConnection() throws SQLException {
		if (this.connection == null) {
			this.connection = this.dataSource.getConnection();
			this.connection.setAutoCommit(!this.transactionActive);
		}
		return this.connection;
	}

	@Override
	public void begin() throws SQLException {
		Connection conn = getConnection();
		conn.setAutoCommit(false);
		this.transactionActive = true;
	}

	@Override
	public void commit() throws SQLException {
		if (this.transactionActive && this.connection != null) {
			this.connection.commit();
			this.connection.setAutoCommit(true);
			this.transactionActive = false;
		}
	}

	@Override
	public void rollback() throws SQLException {
		if (this.transactionActive && this.connection != null) {
			this.connection.rollback();
			this.connection.setAutoCommit(true);
			this.transactionActive = false;
		}
	}

	@Override
	public void close() throws SQLException {
		this.transactionActive = false;
		if (this.connection != null) {
			this.connection.close();
			this.connection = null;
		}
	}

	@Override
	public boolean isTransactionActive() {
		return this.transactionActive;
	}
}
