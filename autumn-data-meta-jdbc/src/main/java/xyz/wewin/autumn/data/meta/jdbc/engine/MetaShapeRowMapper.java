package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.ResultSetMetaData;
import java.util.List;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaShapeColumn;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaShapeModel;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaJdbcTypes;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;

/**
 * 把自定义结果形状的一行映射成 {@link MetaClass}，供 {@code resultType="map"} /
 * {@code resultType="metaclass"} 的命名语句使用。
 *
 * <p>首次拿到结果集时，按 {@link ResultSetMetaData} 读出列形状（列名、JDBC 类型、SQL 类型、可空），
 * 交给 {@link MetaEntityRegistry#shapeModel}：CODE 模式会为这条语句生成一个具体类
 * {@code ...shape.generated.<schema>.<table>.v<指纹>.<语句 id 派生的类名>}，之后的每一行走生成类里的
 * 静态 {@code read}——按列序号直写字段、零反射；GENERIC 模式回退到通用 {@link MetaClass}，
 * 属性列表仍带每列类型。形状只解析一次，行对象的具体类型、包名、类名都可以从
 * {@code row.getClass()} 直接读到。</p>
 */
final class MetaShapeRowMapper implements RowMapper<MetaClass> {

	private final MetaEntityRegistry models;

	private final MetaTable table;

	private final String statementId;

	private MetaShapeModel model;

	MetaShapeRowMapper(MetaEntityRegistry models, MetaTable table, String statementId) {
		this.models = models;
		this.table = table;
		this.statementId = statementId;
	}

	@Override
	public MetaClass mapRow(ResultSet rs, int rowNum) throws SQLException {
		MetaShapeModel resolved = this.model;
		if (resolved == null) {
			List<MetaShapeColumn> columns = MetaShapeColumn.read(rs.getMetaData(), MetaJdbcTypes::javaTypeName);
			resolved = this.models.shapeModel(this.table, this.statementId, columns);
			this.model = resolved;
		}
		return resolved.reader().read(rs);
	}

	@Override
	public String toString() {
		return "MetaShapeRowMapper{" + this.table.name() + "#" + this.statementId + "}";
	}
}
