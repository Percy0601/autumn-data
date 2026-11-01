package xyz.wewin.autumn.data.using.demo;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import xyz.wewin.autumn.data.jdbc.sql.SqlStatements;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编译期生成的 {@code UserRepositoryImpl} 直连 H2 验证（不起 Spring 容器）：
 * SQL 资源已落盘为 properties → 实现类真实读写 → 自增主键回填。
 */
class GeneratedUserRepositoryImplTest {

	private static final String SQL_RESOURCE = "META-INF/autumn-data/sql/xyz/wewin/autumn/data/using/demo/UserRepository.properties";

	private final DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:autumn-generated-impl;DB_CLOSE_DELAY=-1",
			"sa", "");

	private UserRepository repository;

	@BeforeEach
	void setUp() {
		JdbcClient jdbcClient = JdbcClient.create(this.dataSource);
		jdbcClient.sql("DROP TABLE IF EXISTS demo_user").update();
		jdbcClient.sql("""
				CREATE TABLE demo_user (
					id BIGINT AUTO_INCREMENT PRIMARY KEY,
					name VARCHAR(64) NOT NULL,
					age INT NOT NULL,
					email VARCHAR(128),
					created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
				""").update();
		this.repository = new UserRepositoryImpl(jdbcClient);
	}

	@Test
	void sqlStatementsLandOnClasspathAsProperties() {
		SqlStatements sql = SqlStatements.load(SQL_RESOURCE);
		assertThat(sql.get("insert"))
			.isEqualTo("INSERT INTO demo_user (name, age, email, created_at) VALUES (?, ?, ?, ?)");
		assertThat(sql.get("update"))
			.isEqualTo("UPDATE demo_user SET name = ?, age = ?, email = ?, created_at = ? WHERE id = ?");
		assertThat(sql.get("selectAll")).isEqualTo("SELECT id, name, age, email, created_at FROM demo_user");
		assertThat(sql.get("deleteByIds")).isEqualTo("DELETE FROM demo_user WHERE id IN (:ids)");
		assertThat(sql.inClause("selectByIds", 3)).endsWith("WHERE id IN (?, ?, ?)");
	}

	@Test
	void implementationIsServiceWithInjectedJdbcClient() throws Exception {
		assertThat(UserRepositoryImpl.class.getAnnotation(Service.class)).isNotNull();
		assertThat(UserRepositoryImpl.class.getConstructor(JdbcClient.class)).isNotNull();
		assertThat(UserRepositoryImpl.class.getDeclaredConstructors()).hasSize(1);
	}

	@Test
	void saveInsertsAndBackfillsGeneratedId() {
		User saved = this.repository.save(newUser("alice", 30));
		assertThat(saved.getId()).isNotNull();
		assertThat(this.repository.findById(saved.getId())).get().hasFieldOrPropertyWithValue("name", "alice");
	}

	@Test
	void saveWithExistingIdUpdatesInPlace() {
		User saved = this.repository.save(newUser("bob", 25));
		saved.setName("bobby");
		saved.setAge(26);
		this.repository.save(saved);
		assertThat(this.repository.count()).isEqualTo(1L);
		assertThat(this.repository.findById(saved.getId())).get().hasFieldOrPropertyWithValue("name", "bobby");
	}

	@Test
	void batchReadsAndDeletesUseExpandedInClause() {
		this.repository.saveAll(List.of(newUser("carol", 35), newUser("dave", 28)));
		List<Long> ids = this.repository.findAll().stream().map(User::getId).toList();
		assertThat(this.repository.findAllById(ids)).hasSize(2);
		this.repository.deleteById(ids.get(0));
		assertThat(this.repository.existsById(ids.get(0))).isFalse();
		this.repository.deleteAll(this.repository.findAll());
		assertThat(this.repository.count()).isZero();
	}

	private static User newUser(String name, int age) {
		User user = new User();
		user.setName(name);
		user.setAge(age);
		return user;
	}
}
