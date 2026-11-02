package xyz.wewin.autumn.data.using;

import java.util.List;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.wewin.autumn.data.common.datasource.MetaDataSource;
import xyz.wewin.autumn.data.jdbc.spring.SpringMetaDataSource;
import xyz.wewin.autumn.data.using.demo.User;
import xyz.wewin.autumn.data.using.demo.UserRepository;
import xyz.wewin.autumn.data.using.demo.multi.MultiDataSourceDemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用方视角的多数据源示例验证：打开 {@code autumn.data.meta.enabled} 后，
 * {@link SpringMetaDataSource}（Spring 的 {@code AbstractDataSource} 子类）接管应用的数据源，
 * 底下由 {@link MetaDataSource} 负责注册、懒建与空闲回收。
 *
 * <p>值得看的三件事：编译期生成的 {@link UserRepository} 一行代码没改就跑在接管后的默认池上；
 * 配置声明的 {@code tenant-a} 与运行期追加的 {@code tenant-live} 各自独立、互看不见对方的表；
 * Spring 事务管理器绑的就是这个 {@code AbstractDataSource} 实例，因此 {@code @Transactional}
 * 语义（含回滚）原样保留。</p>
 */
@SpringBootTest(classes = UsingApplication.class, properties = { "autumn.data.meta.enabled=true",
		"autumn.data.meta.pools.tenant-a.url=jdbc:h2:mem:demo-tenant-a;DB_CLOSE_DELAY=-1",
		"autumn.data.meta.pools.tenant-a.username=sa" })
class MultiDataSourceTakeoverTest {

	@Autowired
	SpringMetaDataSource metaDataSource;

	@Autowired
	DataSource dataSource;

	@Autowired
	MultiDataSourceDemo multiDataSourceDemo;

	@Autowired
	UserRepository userRepository;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void abstractDataSourceTakesOverAndGeneratedRepositoryFollows() {
		// 接管后应用里唯一的 DataSource 就是 SpringMetaDataSource，Boot 的建池退让
		assertThat(this.dataSource).isSameAs(this.metaDataSource);
		assertThat(MetaDataSource.contains("default")).isTrue();
		// 默认池的参数仍来自 spring.datasource.*（测试配置里的 autumn-using-test 库）
		assertThat(MetaDataSource.get("default")).isInstanceOf(HikariDataSource.class);
		assertThat(((HikariDataSource) MetaDataSource.get("default")).getJdbcUrl()).contains("autumn-using-test");
		// 生成的仓储没有任何改动，读到的就是默认池里的种子数据
		assertThat(this.userRepository.count()).isEqualTo(3L);
		assertThat(this.userRepository.findByUsername("alice")).hasSize(1);
	}

	@Test
	void configuredAndRuntimePoolsStayIndependent() {
		DataSource primaryPool = MetaDataSource.get("default");
		// 配置声明的额外池：到这里才真正建池
		DataSource tenantA = this.metaDataSource.pool("tenant-a");
		assertThat(tenantA).isNotSameAs(primaryPool);
		JdbcTemplate tenantTemplate = new JdbcTemplate(tenantA);
		tenantTemplate.execute("CREATE TABLE IF NOT EXISTS tenant_note (id INT)");
		tenantTemplate.update("INSERT INTO tenant_note VALUES (42)");
		assertThat(tenantTemplate.queryForObject("SELECT COUNT(*) FROM tenant_note", Integer.class)).isEqualTo(1);

		// 业务默认池看不见租户池的表：不是同一个库
		assertThatThrownBy(() -> new JdbcTemplate(primaryPool).queryForObject("SELECT COUNT(*) FROM tenant_note",
				Integer.class)).isInstanceOf(DataAccessException.class);

		// 运行期追加的租户池只登记了工厂，没访问过就不建池、不占连接
		assertThat(MetaDataSource.contains("tenant-live")).isTrue();
		assertThat(MetaDataSource.names()).doesNotContain("tenant-live");
		this.multiDataSourceDemo.templateFor("tenant-live").queryForList("SELECT 1");
		assertThat(MetaDataSource.names()).contains("default", "tenant-a", "tenant-live");
		assertThat(this.metaDataSource.pool("tenant-live")).isNotSameAs(tenantA);
		// 示例里三个池的 URL 互不相同，肉眼可辨
		assertThat(this.multiDataSourceDemo.poolUrls().values()).doesNotHaveDuplicates();
	}

	@Test
	void transactionManagerBindsTheWrapperSoRollbackStillWorks() {
		assertThat(((DataSourceTransactionManager) this.transactionManager).getDataSource())
			.isSameAs(this.dataSource);

		long before = this.userRepository.count();
		assertThatThrownBy(() -> new TransactionTemplate(this.transactionManager).execute(status -> {
			this.userRepository.saveAll(List.of(user("meta-tx-a"), user("meta-tx-b")));
			throw new IllegalStateException("boom");
		})).isInstanceOf(IllegalStateException.class).hasMessage("boom");
		// 接管之后回滚依旧：两条都没留下
		assertThat(this.userRepository.count()).isEqualTo(before);
	}

	private static User user(String username) {
		User user = new User();
		user.setUsername(username);
		user.setEmail(username + "@example.com");
		return user;
	}
}
