package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.List;

import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Binding;
import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Kind;
import xyz.wewin.autumn.data.jdbc.compiler.DerivedQuery.Parameter;
import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Association;
import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Column;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 派生查询解析器的纯函数自测：方法名 → SQL / 绑定 / 结果形态，以及每个「首期不生成」的构造
 * 是否给出定位得过目不忘的原因（这些消息就是处理器报错的正文）。
 */
class DerivedQueryParserTests {

	private static final Column ID = new Column("id", "id", "java.lang.Long", "getId", "setId", true, true);

	private static final Column NAME = new Column("name", "name", "java.lang.String", "getName", "setName", false,
			false);

	private static final Column AGE = new Column("age", "age", "int", "getAge", "setAge", false, false);

	private static final Column CREATED_AT = new Column("createdAt", "created_at", "java.time.LocalDateTime",
			"getCreatedAt", "setCreatedAt", false, false);

	private static final String SELECT = "SELECT id, name, age, created_at FROM demo_user";

	private static final EntityMetadata USER = new EntityMetadata("demo.User", "demo_user", ID,
			List.of(ID, NAME, AGE, CREATED_AT));

	private static final Column ORDER_ID = new Column("id", "id", "java.lang.Long", "getId", "setId", true, true);

	private static final Column ORDER_USER_ID = new Column("userId", "user_id", "java.lang.Long", "getUserId",
			"setUserId", false, false);

	private static final Column ORDER_NO = new Column("orderNo", "order_no", "java.lang.String", "getOrderNo",
			"setOrderNo", false, false);

	private static final Column AMOUNT = new Column("amount", "amount", "java.math.BigDecimal", "getAmount",
			"setAmount", false, false);

	private static final EntityMetadata ORDER = new EntityMetadata("demo.Order", "demo_order", ORDER_ID,
			List.of(ORDER_ID, ORDER_USER_ID, ORDER_NO, AMOUNT));

	/** 父侧 {@code @JoinColumn(name = "user_id")} 的一对多；子实体字段就是派生查询里可用的第二段路径。 */
	private static final Association ORDERS = new Association("orders", "getOrders", "setOrders", "java.util.List",
			"demo.Order", "user_id", ORDER, true);

	/** {@code mappedBy = "owner"} 的关联：外键列来自子侧，叫 {@code owner_id}。 */
	private static final Association BY_OWNER = new Association("byOwner", "getByOwner", "setByOwner", "java.util.Set",
			"demo.Order", "owner_id", ORDER, true);

	private static final Association LAZY_ORDERS = new Association("lazyOrders", "getLazyOrders", "setLazyOrders",
			"java.util.List", "demo.Order", "audit_id", ORDER, false);

	private static final EntityMetadata CUSTOMER = new EntityMetadata("demo.User", "demo_user", ID,
			List.of(ID, NAME, AGE, CREATED_AT), List.of(ORDERS, BY_OWNER, LAZY_ORDERS));

	private static final String EXISTS_ORDERS = "EXISTS (SELECT 1 FROM demo_order WHERE demo_order.user_id = demo_user.id AND ";

	@Test
	void singlePropertyBecomesEquality() {
		DerivedQuery query = parse("findByName", list(), string("name"));
		assertThat(query.sql()).isEqualTo(SELECT + " WHERE name = ?");
		assertThat(query.kind()).isEqualTo(Kind.LIST);
		assertThat(query.bindings()).containsExactly(new Binding("name", false));
	}

	@Test
	void everyQueryVerbAndTheAllModifierParseTheSame() {
		for (String verb : List.of("find", "read", "get", "query", "search")) {
			assertThat(parse(verb + "ByName", list(), string("name")).sql()).isEqualTo(SELECT + " WHERE name = ?");
			assertThat(parse(verb + "AllByName", list(), string("name")).sql()).isEqualTo(SELECT + " WHERE name = ?");
		}
	}

	@Test
	void keywordsCarryTheirOwnArityAndSqlShape() {
		assertThat(parse("findByAgeGreaterThan", list(), integer("age")).sql()).isEqualTo(SELECT + " WHERE age > ?");
		assertThat(parse("findByAgeGreaterThanEqual", list(), integer("age")).sql())
			.isEqualTo(SELECT + " WHERE age >= ?");
		assertThat(parse("findByAgeLessThanEqual", list(), integer("age")).sql())
			.isEqualTo(SELECT + " WHERE age <= ?");
		assertThat(parse("findByAgeNotEqual", list(), integer("age")).sql()).isEqualTo(SELECT + " WHERE age <> ?");
		assertThat(parse("findByAgeBetween", list(), integer("low"), integer("high")).sql())
			.isEqualTo(SELECT + " WHERE age BETWEEN ? AND ?");
		// 0 参数的关键词不消耗形参
		assertThat(parse("findByCreatedAtIsNull", list()).sql()).isEqualTo(SELECT + " WHERE created_at IS NULL");
		assertThat(parse("findByCreatedAtIsNotNull", list()).sql())
			.isEqualTo(SELECT + " WHERE created_at IS NOT NULL");
		assertThat(parse("findByNameLike", list(), string("pattern")).sql()).isEqualTo(SELECT + " WHERE name LIKE ?");
		// 日期比较的 After/Before 是 GreaterThan/LessThan 的同义词
		assertThat(parse("findByCreatedAtAfter", list(), dateTime("since")).sql())
			.isEqualTo(SELECT + " WHERE created_at > ?");
	}

	@Test
	void andGroupsBeforeOrTheWayPartTreeDoes() {
		DerivedQuery and = parse("findByNameAndAgeGreaterThan", list(), string("name"), integer("age"));
		assertThat(and.sql()).isEqualTo(SELECT + " WHERE name = ? AND age > ?");
		assertThat(and.bindings()).containsExactly(new Binding("name", false), new Binding("age", false));

		assertThat(parse("findByNameOrAge", list(), string("name"), integer("age")).sql())
			.isEqualTo(SELECT + " WHERE name = ? OR age = ?");
		// findByNameAndAgeOrCreatedAtIsNull 的语义是 (name AND age) OR createdAt IS NULL
		assertThat(parse("findByNameAndAgeOrCreatedAtIsNull", list(), string("name"), integer("age")).sql())
			.isEqualTo(SELECT + " WHERE (name = ? AND age = ?) OR created_at IS NULL");
	}

	@Test
	void orderByRendersMappedColumnsInDeclaredOrder() {
		assertThat(parse("findByNameOrderByNameAscAgeDesc", list(), string("name")).sql())
			.isEqualTo(SELECT + " WHERE name = ? ORDER BY name ASC, age DESC");
		assertThat(parse("findAllOrderByCreatedAtDesc", list()).sql())
			.isEqualTo(SELECT + " ORDER BY created_at DESC");
	}

	@Test
	void collectionArgumentFeedsTheInMarker() {
		DerivedQuery query = parse("findByAgeIn", list(), longs("ages"));
		assertThat(query.sql()).isEqualTo(SELECT + " WHERE age IN (:ids)");
		assertThat(query.bindings()).containsExactly(new Binding("ages", true));
	}

	@Test
	void countAndExistsSelectCountStar() {
		DerivedQuery count = parse("countByName", longType(), string("name"));
		assertThat(count.kind()).isEqualTo(Kind.COUNT);
		assertThat(count.sql()).isEqualTo("SELECT COUNT(*) FROM demo_user WHERE name = ?");
		assertThat(parse("existsByName", booleanType(), string("name")).kind()).isEqualTo(Kind.EXISTS);
		// 计数没有行序概念，即便方法名写了 OrderBy 也不会带上（PostgreSQL 会因非聚合列拒绝）
		assertThat(parse("countByNameOrderByNameAsc", longType(), string("name")).sql())
			.isEqualTo("SELECT COUNT(*) FROM demo_user WHERE name = ?");
	}

	@Test
	void optionalIsTheSingleRowShape() {
		assertThat(parse("findByName", optional(), string("name")).kind()).isEqualTo(Kind.OPTIONAL);
	}

	@Test
	void unknownPropertyListsWhatTheEntityActuallyHas() {
		assertThatThrownBy(() -> parse("findByCity", list(), string("city")))
			.isInstanceOf(DerivedQueryException.class)
			.hasMessageContaining("unknown property 'City' on demo.User")
			.hasMessageContaining("fields: id, name, age, createdAt");
	}

	@Test
	void constructsOutsideTheFirstSliceFailLoudly() {
		assertThatThrownBy(() -> parse("findFirstByName", list(), string("name")))
			.hasMessageContaining("row limit");
		assertThatThrownBy(() -> parse("findDistinctByName", list(), string("name")))
			.hasMessageContaining("Distinct is not supported");
		assertThatThrownBy(() -> parse("deleteByName", list(), string("name")))
			.hasMessageContaining("deleteBy' is not generated yet");
		assertThatThrownBy(() -> parse("findByNameStartingWith", list(), string("prefix")))
			.hasMessageContaining("StartingWith' is not implemented yet");
		// 裸实体返回没法表达 0 行，规范要求用 Optional
		assertThatThrownBy(() -> parse("findByName", "demo.User", string("name")))
			.hasMessageContaining("prefer Optional over the bare entity");
	}

	@Test
	void argumentMismatchAndBadInArgumentAreRejected() {
		assertThatThrownBy(() -> parse("findByNameAndAge", list(), string("name")))
			.hasMessageContaining("needs more arguments than the declaration provides");
		assertThatThrownBy(() -> parse("findByName", list(), string("name"), integer("extra")))
			.hasMessageContaining("only binds 1 of the 2 declared parameters");
		assertThatThrownBy(() -> parse("findByAgeIn", list(), string("notACollection")))
			.hasMessageContaining("IN condition needs a Collection argument");
		assertThatThrownBy(() -> parse("findByAgeInAndNameIn", list(), longs("ages"), strings("names")))
			.hasMessageContaining("only one IN condition per query");
	}

	@Test
	void malformedMethodNamesAreRejected() {
		assertThatThrownBy(() -> parse("findUserByName", list(), string("name")))
			.hasMessageContaining("expected the 'By' separator");
		assertThatThrownBy(() -> parse("fetchByName", list(), string("name")))
			.hasMessageContaining("unrecognised subject");
		assertThatThrownBy(() -> parse("findBy", list()))
			.hasMessageContaining("no predicate after 'By'");
		assertThatThrownBy(() -> parse("findByNameOrderByName", list(), string("name")))
			.hasMessageContaining("needs a known property followed by Asc or Desc");
	}

	@Test
	void oneLevelAssociationPathBecomesCorrelatedExists() {
		DerivedQuery query = parseOn(CUSTOMER, "findByOrdersOrderNo", list(), string("orderNo"));
		assertThat(query.sql()).isEqualTo(SELECT + " WHERE " + EXISTS_ORDERS + "demo_order.order_no = ?)");
		assertThat(query.bindings()).containsExactly(new Binding("orderNo", false));
		// EXISTS 是半连接：一个用户多笔单命中也只回一行，父侧列清单与 ROW_MAPPER 不变
		assertThat(query.sql()).doesNotContain("JOIN");
	}

	@Test
	void associationPathCombinesWithOwnColumnsAndKeywords() {
		assertThat(parseOn(CUSTOMER, "findByNameAndOrdersAmountGreaterThan", list(), string("name"),
				decimal("amount")).sql())
			.isEqualTo(SELECT + " WHERE name = ? AND " + EXISTS_ORDERS + "demo_order.amount > ?)");
		assertThat(parseOn(CUSTOMER, "countByOrdersOrderNo", longType(), string("orderNo")).sql())
			.isEqualTo("SELECT COUNT(*) FROM demo_user WHERE " + EXISTS_ORDERS + "demo_order.order_no = ?)");
		// mappedBy 的关联用子侧解析出的外键列
		assertThat(parseOn(CUSTOMER, "findByByOwnerOrderNo", list(), string("orderNo")).sql())
			.isEqualTo(SELECT + " WHERE EXISTS (SELECT 1 FROM demo_order WHERE demo_order.owner_id = demo_user.id"
					+ " AND demo_order.order_no = ?)");
		// 谓词只判断存在性，和 fetch = LAZY 不冲突（LAZY 只是不自动装配）
		assertThat(parseOn(CUSTOMER, "findByLazyOrdersOrderNo", list(), string("orderNo")).sql())
			.isEqualTo(SELECT + " WHERE EXISTS (SELECT 1 FROM demo_order WHERE demo_order.audit_id = demo_user.id"
					+ " AND demo_order.order_no = ?)");
	}

	@Test
	void associationPathSupportsInAndUnknownPathsFailLoudly() {
		DerivedQuery in = parseOn(CUSTOMER, "findByOrdersOrderNoIn", list(), strings("orderNos"));
		assertThat(in.sql()).isEqualTo(SELECT + " WHERE " + EXISTS_ORDERS + "demo_order.order_no IN (:ids))");
		assertThat(in.bindings()).containsExactly(new Binding("orderNos", true));

		assertThatThrownBy(() -> parseOn(CUSTOMER, "findByOrdersNote", list(), string("note")))
			.isInstanceOf(DerivedQueryException.class)
			.hasMessageContaining("association 'orders' has no property 'Note' on demo.Order")
			.hasMessageContaining("fields: id, userId, orderNo, amount");
		assertThatThrownBy(() -> parseOn(CUSTOMER, "findByOrders", list()))
			.hasMessageContaining("association 'orders' needs a property of its own after it");
		// 两层以上关联不展开：报错点名子实体没有这个属性，并说明只解析一层
		assertThatThrownBy(() -> parseOn(CUSTOMER, "findByOrdersOrdersOrderNo", list(), string("x")))
			.hasMessageContaining("association 'orders' has no property 'OrdersOrderNo' on demo.Order")
			.hasMessageContaining("only one association level is resolved");
		// 完全不认识的属性会列出可用的一层关联
		assertThatThrownBy(() -> parseOn(CUSTOMER, "findByCity", list(), string("city")))
			.hasMessageContaining("one-level association paths supported: orders, byOwner, lazyOrders");
	}

	private static DerivedQuery parse(String methodName, String returnTypeSource, Parameter... parameters) {
		return DerivedQueryParser.parse(methodName, returnTypeSource, List.of(parameters), USER);
	}

	private static DerivedQuery parseOn(EntityMetadata meta, String methodName, String returnTypeSource,
			Parameter... parameters) {
		return DerivedQueryParser.parse(methodName, returnTypeSource, List.of(parameters), meta);
	}

	private static Parameter decimal(String name) {
		return new Parameter("java.math.BigDecimal", name, false);
	}

	private static Parameter string(String name) {
		return new Parameter("java.lang.String", name, false);
	}

	private static Parameter integer(String name) {
		return new Parameter("int", name, false);
	}

	private static Parameter dateTime(String name) {
		return new Parameter("java.time.LocalDateTime", name, false);
	}

	private static Parameter longs(String name) {
		return new Parameter("java.util.List<java.lang.Long>", name, true);
	}

	private static Parameter strings(String name) {
		return new Parameter("java.util.List<java.lang.String>", name, true);
	}

	private static String list() {
		return "java.util.List<demo.User>";
	}

	private static String optional() {
		return "java.util.Optional<demo.User>";
	}

	private static String longType() {
		return "long";
	}

	private static String booleanType() {
		return "boolean";
	}
}
