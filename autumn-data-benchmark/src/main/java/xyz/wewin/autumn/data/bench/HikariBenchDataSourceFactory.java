package xyz.wewin.autumn.data.bench;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.DataSourceFactory;

/**
 * 把 {@link BenchDatabase} 里那个共享的 HikariCP 连接池交给 MyBatis。
 *
 * <p>MyBatis 默认的 {@code UNPOOLED/POOLED} 数据源会自己再建一套连接，那样连接建立成本就
 * 只压在 MyBatis 一边，比较没有意义。这里让 XML 里的 {@code <dataSource>} 直接返回同一个实例。</p>
 */
public class HikariBenchDataSourceFactory implements DataSourceFactory {

	@Override
	public DataSource getDataSource() {
		return BenchDatabase.dataSource();
	}

	@Override
	public void setProperties(java.util.Properties properties) {
		// 连接串、池参数都在 BenchDatabase 里统一设定，这里不接受覆盖
	}

}
