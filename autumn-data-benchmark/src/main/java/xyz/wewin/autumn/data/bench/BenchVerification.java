package xyz.wewin.autumn.data.bench;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.session.SqlSession;

import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;

/**
 * 基准前的正确性交叉验证：四条路径读同一条数据，逐字段比对。
 *
 * <p>性能数字只有在四条路返回完全相同的结果时才有意义。这里不做测量，只做等值断言，
 * 任何一个字段不一致都会以非零退出码结束。</p>
 */
public final class BenchVerification {

	private static int failures;

	private BenchVerification() {
	}

	public static void main(String[] args) throws Exception {
		BenchDatabase.ensureSchema();
		String expected = jdbcRow(1L);

		check("jdbc", expected, expected);

		try (SqlSession session = MyBatisBench.factory().openSession(true)) {
			check("mybatis", expected, signature(session.getMapper(BenchMapper.class).selectOne(1L)));
		}

		MetaMapperClient code = BenchMeta.code();
		check("meta-code", expected, signature(code.findById(BenchDatabase.READ_TABLE, 1L).orElseThrow()));

		MetaMapperClient generic = BenchMeta.generic();
		check("meta-generic", expected, signature(generic.findById(BenchDatabase.READ_TABLE, 1L).orElseThrow()));

		MetaClass params = code.newEntity(BenchDatabase.READ_TABLE);
		params.set("id", 1L);
		check("meta-statement", expected,
				signature(code.selectOne(BenchDatabase.READ_TABLE, "findById", params).orElseThrow()));

		// 100 行列表：条数 + 首末行签名
		try (SqlSession session = MyBatisBench.factory().openSession(true)) {
			List<BenchOrder> rows = session.getMapper(BenchMapper.class).selectList(BenchDatabase.LIST_ROWS);
			check("mybatis-list-size", String.valueOf(BenchDatabase.LIST_ROWS), String.valueOf(rows.size()));
			check("mybatis-list-first", jdbcRow(1L), signature(rows.get(0)));
			check("mybatis-list-last", jdbcRow(BenchDatabase.LIST_ROWS), signature(rows.get(rows.size() - 1)));
		}
		List<MetaClass> codeRows = code.findPage(BenchDatabase.READ_TABLE, new MetaClass(),
				Pageable.of(0, BenchDatabase.LIST_ROWS)).content();
		check("meta-list-size", String.valueOf(BenchDatabase.LIST_ROWS), String.valueOf(codeRows.size()));
		check("meta-list-first", jdbcRow(1L), signature(codeRows.get(0)));
		check("meta-list-last", jdbcRow(BenchDatabase.LIST_ROWS),
				signature(codeRows.get(codeRows.size() - 1)));

		// 插入：两边的自增主键都要回填，且写入值一致
		BenchDatabase.resetWriteTable();
		BenchOrder order = new BenchOrder();
		order.setUserId(2001L);
		order.setOrderNo("VERIFY-1");
		order.setAmount(new BigDecimal("12.34"));
		order.setStatus(1);
		order.setCreateTime(LocalDateTime.of(2026, 1, 1, 8, 0));
		try (SqlSession session = MyBatisBench.factory().openSession(true)) {
			session.getMapper(BenchMapper.class).insert(order);
		}
		String jdbcInserted = jdbcWriteTableRow(1L);
		check("mybatis-insert", "1|2001|VERIFY-1|12.34|1|2026-01-01T08:00", jdbcInserted);

		MetaClass entity = code.newEntity(BenchDatabase.WRITE_TABLE);
		entity.set("user_id", 2002L);
		entity.set("order_no", "VERIFY-2");
		entity.set("amount", new BigDecimal("12.34"));
		entity.set("status", 1);
		entity.set("create_time", LocalDateTime.of(2026, 1, 1, 8, 0));
		code.save(entity);
		check("meta-insert-key", "2", String.valueOf(entity.getLong("id")));
		check("meta-insert", "2|2002|VERIFY-2|12.34|1|2026-01-01T08:00", jdbcWriteTableRow(2L));

		if (failures == 0) {
			System.out.println("VERIFICATION OK: all paths agree");
		}
		else {
			System.out.println("VERIFICATION FAILED: " + failures + " mismatches");
			System.exit(1);
		}
	}

	private static String signature(BenchOrder order) {
		return order.getId() + "|" + order.getUserId() + "|" + order.getOrderNo() + "|" + order.getAmount() + "|"
				+ order.getStatus() + "|" + order.getCreateTime();
	}

	private static String signature(MetaClass entity) {
		return entity.getLong("id") + "|" + entity.getLong("user_id") + "|" + entity.getString("order_no") + "|"
				+ entity.getBigDecimal("amount") + "|" + entity.getInt("status") + "|"
				+ entity.getLocalDateTime("create_time");
	}

	private static String jdbcRow(long id) throws SQLException {
		return jdbcSignature(BenchDatabase.selectOneSql(), id);
	}

	private static String jdbcWriteTableRow(long id) throws SQLException {
		return jdbcSignature("SELECT " + BenchDatabase.COLUMNS + " FROM " + BenchDatabase.WRITE_TABLE + " WHERE id = ?",
				id);
	}

	private static String jdbcSignature(String sql, long id) throws SQLException {
		try (Connection connection = BenchDatabase.dataSource().getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, id);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					return "missing";
				}
				return resultSet.getLong(1) + "|" + resultSet.getLong(2) + "|" + resultSet.getString(3) + "|"
						+ resultSet.getBigDecimal(4) + "|" + resultSet.getInt(5) + "|"
						+ resultSet.getObject(6, LocalDateTime.class);
			}
		}
	}

	private static void check(String what, String expected, String actual) {
		if (expected.equals(actual)) {
			System.out.println("ok   " + what + " -> " + actual);
			return;
		}
		failures++;
		System.out.println("FAIL " + what + " expected=" + expected + " actual=" + actual);
	}

}
