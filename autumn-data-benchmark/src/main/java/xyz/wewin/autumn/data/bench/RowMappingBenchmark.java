package xyz.wewin.autumn.data.bench;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.executor.resultset.ResultSetWrapper;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import xyz.wewin.autumn.data.common.dialect.DialectResolver;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityColumns;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityModel;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityReader;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;

/**
 * 纯映射基准：只测「把同一个已取回的结果集逐行变对象」，不含 SQL 执行、不含连接池借还。
 *
 * <p>结果集是 H2 的可滚动（{@code TYPE_SCROLL_INSENSITIVE}）结果集，数据全部在客户端物化，
 * 每次调用先用 {@code beforeFirst()} 复位，因此测到的只是逐行映射成本。两边跑的是<b>同一个</b>
 * 结果集实例、同一份 100 行数据。</p>
 *
 * <ul>
 *   <li>{@code meta-code-reader}：CODE 生成类的 {@code READER}（逐列 {@code rs.getObject(index)} + 直写字段，
 *       但每行都重建一次「列标签 → 序号」索引）；</li>
 *   <li>{@code meta-column-index}：只跑 {@code MetaEntityColumns.index(rs)}，把「逐行元数据扫描 + Map 分配」
 *       这一项单独量出来；</li>
 *   <li>{@code meta-generic-reader}：GENERIC 档的逐行读取；</li>
 *   <li>{@code mybatis-metaobject}：MyBatis 的逐行核心（{@code ObjectFactory} 建对象 +
 *       {@code MetaObject.setValue} 反射写属性 + {@code TypeHandler} 取值），列索引由
 *       {@link ResultSetWrapper} 每结果集只建一次；</li>
 *   <li>{@code mybatis-handler}：MyBatis 真实的 {@link DefaultResultSetHandler} 逐行路径，
 *       含 {@code ResultSetWrapper} 的每结果集建索引；</li>
 *   <li>{@code pojo-setters}：手写 POJO setter 按列序号取值的性能地板。</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class RowMappingBenchmark {

	@Param({ "meta-code-reader", "meta-column-index", "meta-generic-reader", "mybatis-metaobject",
			"mybatis-handler", "pojo-setters" })
	public String impl;

	public Connection connection;

	public PreparedStatement statement;

	public ResultSet rowSet;

	public Configuration configuration;

	public MappedStatement mappedStatement;

	public ResultMap resultMap;

	public List<ResultMapping> propertyMappings;

	public MetaEntityReader codeReader;

	public MetaEntityReader genericReader;

	public MapOp op;

	@FunctionalInterface
	public interface MapOp {

		int call() throws SQLException;

	}

	@Setup
	public void setUp() throws Exception {
		BenchDatabase.ensureSchema();
		synchronized (BenchDatabase.class) {
			// 一次性把 100 行物化到可滚动结果集，之后所有迭代都复用这份数据
			this.connection = BenchDatabase.dataSource().getConnection();
			this.statement = this.connection.prepareStatement(BenchDatabase.selectListSql(),
					ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
			this.rowSet = this.statement.executeQuery();
		}

		MetaTableRegistry tables = new MetaTableRegistry(BenchDatabase.dataSource(),
				DialectResolver.resolve(BenchDatabase.dataSource()));
		MetaTable table = tables.require(BenchDatabase.READ_TABLE);
		MetaEntityModel codeModel = BenchMeta.codeEngine().entityRegistry().model(table);
		MetaEntityModel genericModel = BenchMeta.genericEngine().entityRegistry().model(table);
		this.codeReader = codeModel.reader();
		this.genericReader = genericModel.reader();

		this.configuration = MyBatisBench.factory().getConfiguration();
		this.mappedStatement = this.configuration.getMappedStatement(MyBatisBench.statementId("selectList"));
		this.resultMap = this.mappedStatement.getResultMaps().get(0);
		this.propertyMappings = this.resultMap.getPropertyResultMappings();

		this.op = switch (this.impl) {
			case "meta-code-reader" -> this::metaCodeReader;
			case "meta-column-index" -> this::metaColumnIndex;
			case "meta-generic-reader" -> this::metaGenericReader;
			case "mybatis-metaobject" -> this::mybatisMetaObject;
			case "mybatis-handler" -> this::mybatisHandler;
			case "pojo-setters" -> this::pojoSetters;
			default -> throw new IllegalArgumentException("unknown impl: " + this.impl);
		};
	}

	@TearDown
	public void tearDown() throws SQLException {
		try (ResultSet rs = this.rowSet; PreparedStatement ps = this.statement; Connection c = this.connection) {
			// 按 try-with-resources 的逆序关闭；只为不留悬挂连接
		}
	}

	@Benchmark
	public int mapRows() throws SQLException {
		return this.op.call();
	}

	private int metaCodeReader() throws SQLException {
		this.rowSet.beforeFirst();
		int rows = 0;
		while (this.rowSet.next()) {
			this.codeReader.read(this.rowSet);
			rows++;
		}
		return rows;
	}

	private int metaColumnIndex() throws SQLException {
		this.rowSet.beforeFirst();
		int rows = 0;
		while (this.rowSet.next()) {
			MetaEntityColumns.index(this.rowSet);
			rows++;
		}
		return rows;
	}

	private int metaGenericReader() throws SQLException {
		this.rowSet.beforeFirst();
		int rows = 0;
		while (this.rowSet.next()) {
			this.genericReader.read(this.rowSet);
			rows++;
		}
		return rows;
	}

	/** MyBatis 逐行核心：建对象 → 逐属性反射 setValue。列索引由 ResultSetWrapper 每结果集建一次。 */
	private int mybatisMetaObject() throws SQLException {
		this.rowSet.beforeFirst();
		ObjectFactory objectFactory = this.configuration.getObjectFactory();
		List<ResultMapping> mappings = this.propertyMappings;
		int rows = 0;
		while (this.rowSet.next()) {
			BenchOrder order = (BenchOrder) objectFactory.create(BenchOrder.class);
			MetaObject metaObject = this.configuration.newMetaObject(order);
			for (ResultMapping mapping : mappings) {
				metaObject.setValue(mapping.getProperty(),
						mapping.getTypeHandler().getResult(this.rowSet, mapping.getColumn()));
			}
			rows++;
		}
		return rows;
	}

	/** MyBatis 真实的 DefaultResultSetHandler 逐行路径（含每结果集的 ResultSetWrapper 构造）。 */
	private int mybatisHandler() throws SQLException {
		this.rowSet.beforeFirst();
		ResultSetWrapper wrapper = new ResultSetWrapper(this.rowSet, this.configuration);
		DefaultResultHandler resultHandler = new DefaultResultHandler(this.configuration.getObjectFactory());
		DefaultResultSetHandler handler = new DefaultResultSetHandler(null, this.mappedStatement, null, null, null,
				RowBounds.DEFAULT);
		handler.handleRowValues(wrapper, this.resultMap, resultHandler, RowBounds.DEFAULT, null);
		return resultHandler.getResultList().size();
	}

	private int pojoSetters() throws SQLException {
		this.rowSet.beforeFirst();
		int rows = 0;
		while (this.rowSet.next()) {
			BenchOrder order = new BenchOrder();
			order.setId(this.rowSet.getLong(1));
			order.setUserId(this.rowSet.getLong(2));
			order.setOrderNo(this.rowSet.getString(3));
			order.setAmount(this.rowSet.getBigDecimal(4));
			order.setStatus(this.rowSet.getInt(5));
			order.setCreateTime(this.rowSet.getObject(6, java.time.LocalDateTime.class));
			rows++;
		}
		return rows;
	}

}
