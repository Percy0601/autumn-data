package xyz.wewin.autumn.data.jdbc.runtime;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 连接与事务的抽象（对标 MyBatis 的 {@code Transaction}）。
 *
 * <p>核心零 Spring：默认实现 {@link JdbcConnectionSource} 用原生 JDBC 管理连接与提交/回滚；
 * 在 Spring 环境下由 starter 提供 {@code SpringConnectionSource} 适配器，把连接交给 Spring 的
 * {@code DataSourceUtils}，从而自动加入 {@code @Transactional}。生成代码只依赖本接口，不感知 Spring。</p>
 */
public interface ConnectionSource {

	/** 取一条连接（同一事务范围内应返回同一连接）。 */
	Connection getConnection() throws SQLException;

	/** 开启事务（autocommit=false）。无 Spring 时由自带实现直接管理。 */
	void begin() throws SQLException;

	/** 提交。Spring 环境下若已在 Spring 事务中则为 no-op。 */
	void commit() throws SQLException;

	/** 回滚。Spring 环境下若已在 Spring 事务中则为 no-op。 */
	void rollback() throws SQLException;

	/** 归还/关闭连接。Spring 环境下由 {@code DataSourceUtils} 释放（不真正关闭池连接）。 */
	void close() throws SQLException;

	/** 是否处于显式事务中（autocommit 已关闭且未提交/回滚）。 */
	boolean isTransactionActive();
}
