package xyz.wewin.autumn.data.using;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories;
import xyz.wewin.autumn.data.using.demo.controller.MetaDataController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code MetaDataController} 端到端验证：跑在随机端口的内嵌容器上，数据源是测试内存库
 * （src/test/resources/application.properties），与 demo profile 的 TCP H2 库隔离。
 *
 * <p>结构断言直接调控制器方法拿 {@code Map}（不依赖任何 JSON 库的包名），
 * HTTP 层另做一次真实请求冒烟，确认 @RestController 能正常序列化并返回。</p>
 *
 * <p>顺序固定：一个测试串完「建表 → 生成实体 → CRUD → 加列 → 动态 SQL → 防注入 → 逻辑删除」，
 * 后面两个测试各自带前置动作，单独跑也不依赖别人留下的状态。</p>
 */
@SpringBootTest(classes = UsingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetaDataControllerTest {

	@Autowired
	MetaDataController controller;

	@Autowired
	Environment environment;

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	@Order(1)
	void dynamicTableEndToEnd() throws Exception {
		// ① 运行期建表：不重启服务，表结构与强类型实体类都在这一次调用里现生成
		Map<String, Object> init = this.controller.init();
		assertThat(init).containsEntry("seedRows", 3);
		assertThat((int) init.get("columnCount")).isGreaterThanOrEqualTo(8);
		assertThat(init.get("generatedEntity")).isEqualTo(Boolean.TRUE);
		assertThat((String) init.get("entityClass")).isNotEqualTo(MetaClass.class.getName());
		assertThat(init.get("columns").toString()).containsIgnoringCase("deleted_flag");

		// ①-b 生成 SQL 落 XML：meta_demo_form.xml 里能直接看到 findOne / findList 等默认语句，
		// 想改语义就改文件里的 SQL，不用回到 Java。
		Path statementXml = MetaDirectories.of(
				this.environment.getProperty("autumn.data.meta.mapper.meta-directory")).sqlDirectory()
				.resolve(MetaMapper.engine().defaultPoolName()).resolve(MetaMapper.DEFAULT_NAMESPACE)
				.resolve("meta_demo_form.xml");
		assertThat(statementXml).isRegularFile();
		String statementSql = Files.readString(statementXml);
		assertThat(statementSql).contains("AUTUMN-DATA GENERATED START", "id=\"selectList\"");
		for (String id : MetaMapper.DEFAULT_STATEMENT_IDS) {
			assertThat(statementSql).contains("id=\"" + id + "\"");
		}
		assertThat(statementSql).contains("id=\"countVoided\""); // 手写自定义语句与默认语句共存

		// ② 元数据：DECIMAL 列被识别成 BigDecimal，自增主键被标记
		Map<String, Object> schema = this.controller.schema();
		assertThat(schema.get("columns").toString()).contains("java.math.BigDecimal", "BIGINT");
		assertThat(schema.get("logicDeleteColumn")).isEqualTo("deleted_flag");

		// ③ 批量写：自增主键逐个回填
		Map<String, Object> added = this.controller.addRows(2);
		assertThat(added).containsEntry("saved", 2);
		List<?> keys = (List<?>) added.get("generatedKeys");
		assertThat(keys).hasSize(2);
		assertThat(keys.get(0)).isNotNull();

		// ④ 条件 + 排序 + 分页：参数实体 + 手写 SQL 的 #{} 绑定
		Map<String, Object> page = this.controller.findRows(1, new BigDecimal("50"), null, 0, 5, "amount", true);
		assertThat(((Number) page.get("total")).longValue()).isGreaterThanOrEqualTo(1L);
		List<?> content = (List<?>) page.get("content");
		assertThat(content).hasSizeLessThanOrEqualTo(5);
		assertThat(((Map<?, ?>) content.get(0)).get("form_no")).isNotNull();

		// ⑤ 类型化 getter：读回来的还是同一个运行期实体类，且是干净的
		Map<String, Object> one = this.controller.findById(1L);
		Map<?, ?> typed = (Map<?, ?>) one.get("typed");
		assertThat(typed.get("formNo")).isEqualTo("F-001");
		assertThat(typed.get("id")).isEqualTo(1L);
		assertThat(new BigDecimal(typed.get("amount").toString())).isEqualByComparingTo("12.50");
		assertThat((String) one.get("json")).contains("F-001");
		assertThat(one.get("hasChanges")).isEqualTo(Boolean.FALSE);

		// ⑥ 部分更新：只写改过的两列，成功后实体清脏；显式写 null 才是置空
		Map<String, Object> updated = this.controller.updateRow(1L, 9, null, "urgent", false);
		assertThat(updated.get("changedColumns").toString()).contains("remark", "status");
		assertThat(updated).containsEntry("updatedRows", 1);
		assertThat(((Map<?, ?>) updated.get("row")).get("remark")).isEqualTo("urgent");
		assertThat(updated.get("cleanAfterUpdate")).isEqualTo(Boolean.TRUE);
		Map<String, Object> cleared = this.controller.updateRow(1L, null, null, null, true);
		assertThat(((Map<?, ?>) cleared.get("row")).get("remark")).isNull();

		// ⑦ 运算符开集：比较 / 模糊 / IN / 空值 / 排序 / 限行 / count / exists
		Map<String, Object> operators = this.controller.queryOperators();
		assertThat(operators.get("LIKE").toString()).contains("F-001");
		assertThat((List<?>) operators.get("IN")).hasSizeGreaterThanOrEqualTo(3);
		assertThat((List<?>) operators.get("LIMIT_2")).hasSize(2);
		assertThat(operators.get("EXISTS_F-001")).isEqualTo(Boolean.TRUE);
		assertThat(((Number) operators.get("COUNT_status_1")).longValue()).isGreaterThanOrEqualTo(1L);

		// ⑧ 运行期加列：refresh 后新列立刻可读写，结构指纹变化生成新的实体类
		String entityBefore = (String) this.controller.engine().get("entityClass");
		Map<String, Object> column = this.controller.addColumn("courier", "VARCHAR(64)");
		assertThat(column).containsEntry("added", true).containsEntry("entityClassChanged", true);
		assertThat(column).containsEntry("entityClassBefore", entityBefore);
		assertThat(column.get("readBack")).isEqualTo("加列后立即写入");
		assertThat(column.get("readBackJavaType")).isEqualTo("java.lang.String");
		// 重复加列是幂等的
		assertThat(this.controller.addColumn("courier", "VARCHAR(64)")).containsEntry("added", false);

		// ⑨ 命名语句：手写语句 XML 与七条默认语句共存，sqlFor / selectList / selectCount / execute 都能跑
		Map<String, Object> statements = this.controller.namedStatements();		assertThat((String) statements.get("listSql")).contains("?");
		assertThat((List<?>) statements.get("listValues")).hasSize(1);
		assertThat(((Number) statements.get("selectCount")).longValue()).isGreaterThanOrEqualTo(1L);
		assertThat((String) statements.get("insertSql")).containsIgnoringCase("form_no");
		assertThat(statements).containsEntry("insertedRows", 1).containsEntry("updatedRows", 1);
		assertThat(statements.get("afterUpdateRemark")).isEqualTo("生成语句改的");
		assertThat((String) statements.get("statementFile")).endsWith(".xml");

		// ⑨-b 跨表自定义形状：resultType="map" 的语句 JOIN 第二张表 meta_demo_customer，
		// 客户名 / 区域这些锚定表没有的列能带出来，分页计数仍是对整条 SELECT 包子查询
		Map<String, Object> join = this.controller.joinQuery(1, 0, 50);
		assertThat((String) join.get("sql")).containsIgnoringCase("join meta_demo_customer");
		assertThat(((Number) join.get("total")).longValue()).isGreaterThanOrEqualTo(1L);
		List<?> joinedRows = (List<?>) join.get("content");
		Map<?, ?> seedJoin = joinedRows.stream().map(row -> (Map<?, ?>) row)
				.filter(row -> "F-003".equals(row.get("form_no"))).findFirst().orElseThrow();
		assertThat(seedJoin.get("customer_no")).isEqualTo("C-003");
		assertThat(seedJoin.get("customer_name")).isEqualTo("南岭实业");
		assertThat(seedJoin.get("region")).isEqualTo("south");
		// 未挂客户的单据也能查出来（LEFT JOIN），客户列就是 null
		assertThat((List<?>) join.get("aggregation")).isNotEmpty();

		// ⑩ 防注入：值不进 SQL 文本，非法列名/排序标识符都被元数据挡住
		Map<String, Object> safety = this.controller.safety();
		assertThat(safety.get("valueInSqlText")).isEqualTo(Boolean.FALSE);
		assertThat(safety.get("boundValues").toString()).contains("OR '1'='1");
		assertThat(safety).containsEntry("matchedRows", 0);
		assertThat((String) safety.get("illegalConditionColumn")).contains("unknown column");
		assertThat((String) safety.get("illegalOrderByIdentifier")).contains("unknown column");

		// ⑪ 引擎诊断：按池绑定、当前实体类已换新版
		Map<String, Object> engine = this.controller.engine();
		assertThat(engine).containsEntry("defaultPoolName", "default").containsEntry("boundPool", "default");
		assertThat(engine.get("activePools").toString()).contains("default");
		assertThat(engine.get("entityClass")).isNotEqualTo(entityBefore);

		// ⑫ 逻辑删除：查询自动过滤，显式按标志查行还在；删除语义由 XML 的 delete 语句决定
		Map<String, Object> voided = this.controller.voidRow(1L);
		assertThat(voided.get("visibleAfterVoid")).isEqualTo(Boolean.FALSE);
		assertThat(voided).containsEntry("voidedRowsFoundByFlag", 1L);

		Map<String, Object> summary = this.controller.summary();
		assertThat(summary.get("initialized")).isEqualTo(Boolean.TRUE);
		assertThat((List<?>) summary.get("endpoints")).hasSizeGreaterThan(10);
	}

	@Test
	@Order(2)
	void uninitializedTableReturnsReadableHint() {
		assertThat(this.controller.drop()).containsEntry("dropped", "meta_demo_form");

		Map<String, Object> summary = this.controller.summary();
		assertThat(summary.get("initialized")).isEqualTo(Boolean.FALSE);
		assertThat((String) summary.get("hint")).contains("/metadata/init");
		assertThatThrownBy(() -> this.controller.schema())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("/metadata/init");
	}

	/** 真实 HTTP 冒烟：确认端点可达、Map 能被容器序列化成 JSON、异常处理器返回 400。 */
	@Test
	@Order(3)
	void httpEndpointsAreReachable() throws Exception {
		this.controller.init();
		assertThat(get("/metadata/summary", 200)).contains("initialized");
		assertThat(get("/metadata/schema", 200)).contains("entityClass");
		assertThat(get("/metadata/rows?status=1", 200)).contains("content");
		assertThat(get("/metadata/query/operators", 200)).contains("EXISTS_F-001");
		assertThat(get("/metadata/engine", 200)).contains("defaultPoolName");
		assertThat(get("/metadata/safety", 200)).contains("valueInSqlText");
		assertThat(get("/metadata/statements", 200)).contains("statementFile");
		assertThat(get("/metadata/query/join", 200)).contains("aggregation");
		assertThat(request("POST", "/metadata/rows?count=1", 200)).contains("generatedKeys");
		assertThat(request("DELETE", "/metadata/drop", 200)).contains("dropped");
		assertThat(get("/metadata/schema", 400)).contains("/metadata/init");
	}

	private String get(String path, int expectedStatus) throws Exception {
		return request("GET", path, expectedStatus);
	}

	private String request(String method, String path, int expectedStatus) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
				.header("Accept", "application/json")
				.method(method, BodyPublishers.noBody())
				.build();
		HttpResponse<String> response = this.http.send(request, BodyHandlers.ofString());
		assertThat(response.statusCode()).as(method + " " + path + " -> " + response.body())
				.isEqualTo(expectedStatus);
		return response.body();
	}

	private String baseUrl() {
		return "http://localhost:" + this.environment.getProperty("local.server.port");
	}

}
