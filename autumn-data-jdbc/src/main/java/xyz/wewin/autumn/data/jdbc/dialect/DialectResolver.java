package xyz.wewin.autumn.data.jdbc.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * 根据 {@link DataSource} 的数据库产品名自动识别并选择对应 {@link Dialect}。
 *
 * <p>识别规则（大小写不敏感，包含匹配）：</p>
 * <ul>
 *   <li>{@code mysql}  → {@link MySqlDialect}</li>
 *   <li>{@code postgre} → {@link PostgreSqlDialect}</li>
 *   <li>{@code h2}      → {@link H2Dialect}</li>
 *   <li>其它 / 获取失败 → {@link DefaultDialect}</li>
 * </ul>
 */
public final class DialectResolver {

	private DialectResolver() {
	}

	public static Dialect resolve(DataSource dataSource) {
		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			String productName = metaData.getDatabaseProductName();
			if (productName == null) {
				return new DefaultDialect();
			}
			String p = productName.toLowerCase();
			if (p.contains("mysql")) {
				return new MySqlDialect();
			}
			if (p.contains("postgre")) {
				return new PostgreSqlDialect();
			}
			if (p.contains("h2")) {
				return new H2Dialect();
			}
			return new DefaultDialect();
		}
		catch (SQLException ex) {
			return new DefaultDialect();
		}
	}
}
