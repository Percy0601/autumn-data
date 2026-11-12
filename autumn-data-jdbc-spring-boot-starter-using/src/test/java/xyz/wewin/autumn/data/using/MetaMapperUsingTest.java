package xyz.wewin.autumn.data.using;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcOperations;

import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;
import xyz.wewin.autumn.data.common.meta.MetaClass;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态单据端到端验证：运行期建表后不重启，直接通过 {@link MetaMapper} 保存、查询、更新；
 * 再加一列并 refresh，新列立即可读写。
 */
@SpringBootTest(classes = UsingApplication.class)
class MetaMapperUsingTest {

	private static final String TABLE = "dynamic_form_demo";

	@Autowired
	JdbcOperations jdbcOperations;

	@Test
	void runtimeTableCrudWithoutRestart() {
		this.jdbcOperations.execute("DROP TABLE IF EXISTS " + TABLE);
		this.jdbcOperations.execute("CREATE TABLE " + TABLE
				+ " (id BIGINT AUTO_INCREMENT PRIMARY KEY, form_no VARCHAR(32) NOT NULL, amount DECIMAL(12,2),"
				+ " status INT, create_time TIMESTAMP, deleted_flag INT DEFAULT 0)");
		try {
			MetaClass form = MetaMapper.newEntity(TABLE);
			form.set("form_no", "D001");
			form.set("amount", "12.50");
			form.set("status", 1);
			MetaMapper.save(form); // 自增主键回填到 form 上

			MetaClass saved = MetaMapper.findById(TABLE, form.get("id")).orElseThrow();
			assertThat(saved.getString("form_no")).isEqualTo("D001");
			assertThat(saved.getBigDecimal("amount")).isEqualByComparingTo("12.50");

			saved.set("status", 2);
			assertThat(MetaMapper.update(saved)).isEqualTo(1); // 只写 status 这一列
			assertThat(MetaMapper.findPage(TABLE, MetaQuery.by("status", 2), Pageable.of(0, 10)).total())
					.isEqualTo(1);

			// 运行期加列：refresh 之后不需要重启服务，新列即读写
			this.jdbcOperations.execute("ALTER TABLE " + TABLE + " ADD COLUMN remark VARCHAR(64)");
			MetaMapper.refresh(TABLE);

			MetaClass reloaded = MetaMapper.findById(TABLE, form.get("id")).orElseThrow();
			reloaded.set("remark", "加急");
			assertThat(MetaMapper.update(reloaded)).isEqualTo(1);
			assertThat(MetaMapper.findById(TABLE, form.get("id")).orElseThrow().getString("remark")).isEqualTo("加急");
		}
		finally {
			this.jdbcOperations.execute("DROP TABLE IF EXISTS " + TABLE);
		}
	}
}
