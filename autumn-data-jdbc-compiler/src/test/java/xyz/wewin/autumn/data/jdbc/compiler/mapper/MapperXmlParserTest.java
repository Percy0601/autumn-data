package xyz.wewin.autumn.data.jdbc.compiler.mapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.data.common.mapper.MapperDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperXmlParseException;
import xyz.wewin.autumn.data.common.mapper.MapperXmlParser;
import xyz.wewin.autumn.data.common.mapper.TemplateNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mapper XML 解析 + 模板 AST + 代码生成的集成测试。
 */
class MapperXmlParserTest {

	@Test
	void parseSimpleSelect() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="com.example.UserMapper">
				    <select id="findAll" resultType="com.example.User">
				        SELECT * FROM users
				    </select>
				</mapper>
				""";

		MapperDefinition mapper = parse(xml);

		assertThat(mapper.namespace()).isEqualTo("com.example.UserMapper");
		assertThat(mapper.statements()).hasSize(1);

		MapperDefinition.StatementDefinition stmt = mapper.statements().get(0);
		assertThat(stmt.id()).isEqualTo("findAll");
		assertThat(stmt.type()).isEqualTo(MapperDefinition.StatementType.SELECT);
		assertThat(stmt.resultType()).isEqualTo("com.example.User");
		assertThat(stmt.template()).hasSize(1);
		assertThat(stmt.template().get(0)).isInstanceOf(TemplateNode.Text.class);
	}

	@Test
	void parseSelectWithIfAndParameter() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="com.example.UserMapper">
				    <select id="findByName" resultType="User">
				        SELECT * FROM users WHERE 1=1
				        <if test="name != null">
				            AND name LIKE #{name}
				        </if>
				    </select>
				</mapper>
				""";

		MapperDefinition mapper = parse(xml);
		MapperDefinition.StatementDefinition stmt = mapper.statements().get(0);

		assertThat(stmt.template()).hasSizeGreaterThanOrEqualTo(2);
		// 第一个是 Text（SELECT * FROM users WHERE 1=1）
		assertThat(stmt.template().get(0)).isInstanceOf(TemplateNode.Text.class);
		// 第二个是 If
		assertThat(stmt.template().get(1)).isInstanceOf(TemplateNode.If.class);

		TemplateNode.If ifNode = (TemplateNode.If) stmt.template().get(1);
		assertThat(ifNode.expression()).isEqualTo("name != null");
		// If 内部应该有 Text + Parameter
		assertThat(ifNode.body()).anyMatch(n -> n instanceof TemplateNode.Parameter p && p.name().equals("name"));
	}

	@Test
	void parseForeach() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="com.example.UserMapper">
				    <select id="findByIds">
				        SELECT * FROM users WHERE id IN
				        <foreach collection="ids" item="id" separator=", " open="(" close=")">
				            #{id}
				        </foreach>
				    </select>
				</mapper>
				""";

		MapperDefinition mapper = parse(xml);
		MapperDefinition.StatementDefinition stmt = mapper.statements().get(0);

		assertThat(stmt.template()).anyMatch(n -> n instanceof TemplateNode.ForEach);

		TemplateNode.ForEach forEach = stmt.template().stream()
				.filter(TemplateNode.ForEach.class::isInstance)
				.map(TemplateNode.ForEach.class::cast)
				.findFirst()
				.orElseThrow();

		assertThat(forEach.collectionExpression()).isEqualTo("ids");
		assertThat(forEach.itemName()).isEqualTo("id");
		assertThat(forEach.separator()).isEqualTo(", ");
	}

	@Test
	void parseChoose() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="com.example.UserMapper">
				    <select id="findByCondition">
				        SELECT * FROM users WHERE
				        <choose>
				            <when test="status == 'active'">
				                status = 1
				            </when>
				            <when test="status == 'inactive'">
				                status = 0
				            </when>
				            <otherwise>
				                1=1
				            </otherwise>
				        </choose>
				    </select>
				</mapper>
				""";

		MapperDefinition mapper = parse(xml);
		MapperDefinition.StatementDefinition stmt = mapper.statements().get(0);

		assertThat(stmt.template()).anyMatch(n -> n instanceof TemplateNode.Choose);

		TemplateNode.Choose choose = stmt.template().stream()
				.filter(TemplateNode.Choose.class::isInstance)
				.map(TemplateNode.Choose.class::cast)
				.findFirst()
				.orElseThrow();

		assertThat(choose.branches()).hasSize(2);
		assertThat(choose.otherwise()).isNotEmpty();
	}

	@Test
	void parseVariableInterpolation() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="com.example.UserMapper">
				    <select id="findFromTable">
				        SELECT * FROM ${tableName} WHERE id = #{id}
				    </select>
				</mapper>
				""";

		MapperDefinition mapper = parse(xml);
		MapperDefinition.StatementDefinition stmt = mapper.statements().get(0);

		assertThat(stmt.template()).anyMatch(n -> n instanceof TemplateNode.Variable v
				&& v.expression().equals("tableName"));
		assertThat(stmt.template()).anyMatch(n -> n instanceof TemplateNode.Parameter p
				&& p.name().equals("id"));
	}

	@Test
	void parseInsertStatement() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="com.example.UserMapper">
				    <insert id="insertUser" parameterType="User">
				        INSERT INTO users (name, email) VALUES (#{name}, #{email})
				    </insert>
				</mapper>
				""";

		MapperDefinition mapper = parse(xml);
		assertThat(mapper.statements()).hasSize(1);
		assertThat(mapper.statements().get(0).type()).isEqualTo(MapperDefinition.StatementType.INSERT);
	}

	@Test
	void multipleStatements() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="com.example.UserMapper">
				    <select id="findAll">SELECT * FROM users</select>
				    <insert id="insert">INSERT INTO users VALUES (#{name})</insert>
				    <update id="update">UPDATE users SET name = #{name} WHERE id = #{id}</update>
				    <delete id="delete">DELETE FROM users WHERE id = #{id}</delete>
				</mapper>
				""";

		MapperDefinition mapper = parse(xml);
		assertThat(mapper.statements()).hasSize(4);
		assertThat(mapper.statements().stream().map(MapperDefinition.StatementDefinition::type).toList())
				.containsExactly(
						MapperDefinition.StatementType.SELECT,
						MapperDefinition.StatementType.INSERT,
						MapperDefinition.StatementType.UPDATE,
						MapperDefinition.StatementType.DELETE);
	}

	@Test
	void missingNamespaceThrows() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper>
				    <select id="findAll">SELECT 1</select>
				</mapper>
				""";

		assertThatThrownBy(() -> parse(xml))
				.isInstanceOf(MapperXmlParseException.class)
				.hasMessageContaining("namespace");
	}

	@Test
	void missingStatementIdThrows() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="test">
				    <select>SELECT 1</select>
				</mapper>
				""";

		assertThatThrownBy(() -> parse(xml))
				.isInstanceOf(MapperXmlParseException.class)
				.hasMessageContaining("id");
	}

	@Test
	void generateCode() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="com.example.UserMapper">
				    <select id="findByName" resultType="User">
				        SELECT * FROM users WHERE 1=1
				        <if test="name != null">
				            AND name LIKE #{name}
				        </if>
				    </select>
				</mapper>
				""";

		MapperDefinition mapper = parse(xml);
		String code = TemplateCodeGenerator.generate(mapper, "com.example.generated");

		assertThat(code).contains("package com.example.generated;");
		assertThat(code).contains("public final class UserMapperSql");
		assertThat(code).contains("public static");
		assertThat(code).contains("findByName");
		assertThat(code).contains("SqlBuild");
		assertThat(code).contains("params.get(\"name\")");
		assertThat(code).contains("args.add(");
	}

	@Test
	void generateForeachCode() {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper namespace="com.example.UserMapper">
				    <select id="findByIds">
				        SELECT * FROM users WHERE id IN
				        <foreach collection="ids" item="id" separator=", " open="(" close=")">
				            #{id}
				        </foreach>
				    </select>
				</mapper>
				""";

		MapperDefinition mapper = parse(xml);
		String code = TemplateCodeGenerator.generate(mapper, "com.example");

		assertThat(code).contains("Collection<?>");
		assertThat(code).contains("for (Object id :");
		assertThat(code).contains(", ");
	}

	@Test
	void conditionTranslation() {
		assertThat(TemplateCodeGenerator.translateCondition("name != null"))
				.isEqualTo("params.get(\"name\") != null");
		assertThat(TemplateCodeGenerator.translateCondition("name == null"))
				.isEqualTo("params.get(\"name\") == null");
		assertThat(TemplateCodeGenerator.translateCondition("name??"))
				.isEqualTo("params.get(\"name\") != null");
		assertThat(TemplateCodeGenerator.translateCondition("status == 'active'"))
				.isEqualTo("\"active\".equals(params.get(\"status\"))");
		assertThat(TemplateCodeGenerator.translateCondition("name != ''"))
				.contains("isEmpty()");
	}

	private static MapperDefinition parse(String xml) {
		return MapperXmlParser.parse(
				new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
				"test-mapper.xml");
	}

}
