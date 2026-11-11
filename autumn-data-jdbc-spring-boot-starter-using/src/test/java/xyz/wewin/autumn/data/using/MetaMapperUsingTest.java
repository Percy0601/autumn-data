package xyz.wewin.autumn.data.using;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcOperations;

import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaRow;

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
			Object id = MetaMapper.save(TABLE, Map.of("form_no", "D001", "amount", "12.50", "status", 1));

			MetaRow saved = MetaMapper.findById(TABLE, id).orElseThrow();
			assertThat(saved.getString("form_no")).isEqualTo("D001");
			assertThat(saved.getBigDecimal("amount")).isEqualByComparingTo("12.50");

			assertThat(MetaMapper.updateById(TABLE, id, Map.of("status", 2))).isEqualTo(1);
			assertThat(MetaMapper.findPage(TABLE, MetaQuery.by("status", 2), Pageable.of(0, 10)).total())
					.isEqualTo(1);

			// 运行期加列：refresh 之后不需要重启服务
			this.jdbcOperations.execute("ALTER TABLE " + TABLE + " ADD COLUMN remark VARCHAR(64)");
			MetaMapper.refresh(TABLE);

			assertThat(MetaMapper.updateById(TABLE, id, Map.of("remark", "加急"))).isEqualTo(1);
			assertThat(MetaMapper.findById(TABLE, id).orElseThrow().getString("remark")).isEqualTo("加急");
		}
		finally {
			this.jdbcOperations.execute("DROP TABLE IF EXISTS " + TABLE);
		}
	}
}
