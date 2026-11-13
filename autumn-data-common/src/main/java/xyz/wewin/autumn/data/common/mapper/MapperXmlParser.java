package xyz.wewin.autumn.data.common.mapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import xyz.wewin.autumn.data.common.mapper.MapperDefinition.ResultMapping;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementType;

/**
 * MyBatis 风格 XML Mapper 解析器：用 JDK 内置 DOM 把 {@code <mapper>} 文件翻译成 {@link MapperDefinition}。
 *
 * <p>编译期注解处理器和动态表运行期共用这一份语法：静态路径把 AST 编译成 Java 源码，
 * 动态路径直接解释执行 AST。解析阶段就把 {@code #{...}} 摘成独立的
 * {@link TemplateNode.Parameter} 节点，任何插值都不会在文本层被二次解释。</p>
 *
 * <p>零第三方依赖——只用 {@code javax.xml.parsers}。</p>
 *
 * <p>XML 格式示例：</p>
 * <pre>{@code
 * <mapper namespace="com.example.UserMapper">
 *     <select id="findUsers" resultType="com.example.User">
 *         SELECT * FROM users
 *         WHERE 1=1
 *         <if test="name != null">
 *             AND name LIKE #{name}
 *         </if>
 *         <foreach collection="ids" item="id" separator=", " open="(" close=")">
 *             #{id}
 *         </foreach>
 *     </select>
 * </mapper>
 * }</pre>
 */
public final class MapperXmlParser {

	private static final String MAPPER_TAG = "mapper";

	private static final String NAMESPACE_ATTR = "namespace";

	private MapperXmlParser() {
	}

	/**
	 * 从类路径资源解析 Mapper XML。
	 *
	 * @param resourcePath 类路径下的资源路径（如 {@code mappers/UserMapper.xml}）
	 * @param classLoader 用于加载资源的 ClassLoader
	 * @return 解析后的 Mapper 定义
	 */
	public static MapperDefinition parse(String resourcePath, ClassLoader classLoader) {
		try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
			if (input == null) {
				throw new MapperXmlParseException("mapper XML not found on classpath: " + resourcePath);
			}
			return parse(input, resourcePath);
		}
		catch (IOException ex) {
			throw new MapperXmlParseException("failed to read mapper XML: " + resourcePath, ex);
		}
	}

	/**
	 * 从 XML 文本解析 Mapper XML（动态层的文件已在内存里）。
	 */
	public static MapperDefinition parse(String xml, String sourceDescription) {
		return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), sourceDescription);
	}

	/**
	 * 从输入流解析 Mapper XML。
	 */
	public static MapperDefinition parse(InputStream input, String sourceDescription) {
		Document document = parseDocument(input, sourceDescription);
		Element root = document.getDocumentElement();
		if (!MAPPER_TAG.equals(root.getTagName())) {
			throw new MapperXmlParseException(
					"root element of mapper XML must be <mapper>, but was <" + root.getTagName() + ">");
		}
		String namespace = root.getAttribute(NAMESPACE_ATTR);
		if (namespace.isEmpty()) {
			throw new MapperXmlParseException("mapper XML <mapper> requires a 'namespace' attribute");
		}
		List<StatementDefinition> statements = new ArrayList<>();
		// 第一遍：片段与结果映射可以先声明、后使用，和语句出现顺序无关。
		Map<String, Element> fragments = new LinkedHashMap<>();
		Map<String, List<ResultMapping>> resultMaps = new LinkedHashMap<>();
		for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			Element element = (Element) child;
			switch (element.getTagName()) {
				case "sql" -> {
					String id = element.getAttribute("id");
					if (id.isEmpty()) {
						throw new MapperXmlParseException("<sql> requires an 'id' attribute");
					}
					if (fragments.put(id, element) != null) {
						throw new MapperXmlParseException("duplicate <sql id=\"" + id + "\"> in mapper XML");
					}
				}
				case "resultMap" -> {
					String id = element.getAttribute("id");
					if (id.isEmpty()) {
						throw new MapperXmlParseException("<resultMap> requires an 'id' attribute");
					}
					if (resultMaps.put(id, parseResultMap(element)) != null) {
						throw new MapperXmlParseException("duplicate <resultMap id=\"" + id + "\"> in mapper XML");
					}
				}
				default -> {
				}
			}
		}
		for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			Element element = (Element) child;
			StatementType type = statementTypeOf(element.getTagName());
			if (type == null) {
				continue;
			}
			statements.add(parseStatement(element, type, fragments, resultMaps));
		}
		return new MapperDefinition(namespace, statements);
	}

	private static StatementDefinition parseStatement(Element element, StatementType type,
			Map<String, Element> fragments, Map<String, List<ResultMapping>> resultMaps) {
		String id = element.getAttribute("id");
		if (id.isEmpty()) {
			throw new MapperXmlParseException(
					"<" + element.getTagName() + "> requires an 'id' attribute");
		}
		String resultType = element.getAttribute("resultType");
		String parameterType = element.getAttribute("parameterType");
		List<ResultMapping> resultMappings = resultMappings(element, resultMaps);
		List<TemplateNode> template = TemplateParser.parse(element, fragments);
		return new StatementDefinition(id, type, resultType, parameterType, template, resultMappings);
	}

	/**
	 * {@code resultMap="id"} 在解析阶段换成映射清单：运行期与编译期都只看到最终列映射，
	 * 不需要回头再拿整份 Mapper 上下文。
	 */
	private static List<ResultMapping> resultMappings(Element element, Map<String, List<ResultMapping>> resultMaps) {
		String resultMap = element.getAttribute("resultMap").trim();
		if (resultMap.isEmpty()) {
			return List.of();
		}
		List<ResultMapping> mappings = resultMaps.get(resultMap);
		if (mappings == null) {
			throw new MapperXmlParseException("<" + element.getTagName() + " id=\"" + element.getAttribute("id")
					+ "\"> references unknown <resultMap id=\"" + resultMap + "\">; available: "
					+ resultMaps.keySet());
		}
		return mappings;
	}

	/**
	 * {@code <resultMap id type><id column property/><result column property/></resultMap>}。
	 *
	 * <p>{@code type} 在动态层没有意义（实体由表结构在运行期生成），保留属性只是为了兼容
	 * MyBatis 写法。{@code column} 是结果集里的标签/别名，{@code property} 是 MetaClass 属性名。</p>
	 */
	private static List<ResultMapping> parseResultMap(Element element) {
		List<ResultMapping> mappings = new ArrayList<>();
		for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			Element column = (Element) child;
			switch (column.getTagName()) {
				case "id", "result" -> {
					String from = column.getAttribute("column").trim();
					String to = column.getAttribute("property").trim();
					if (from.isEmpty() || to.isEmpty()) {
						throw new MapperXmlParseException("<" + column.getTagName() + "> inside <resultMap id=\""
								+ element.getAttribute("id") + "\"> requires both 'column' and 'property'");
					}
					mappings.add(new ResultMapping(from, to));
				}
				default -> throw new MapperXmlParseException("unsupported <" + column.getTagName()
						+ "> inside <resultMap>; supported: id, result");
			}
		}
		if (mappings.isEmpty()) {
			throw new MapperXmlParseException("<resultMap id=\"" + element.getAttribute("id")
					+ "\"> declares no <id>/<result> mapping");
		}
		return List.copyOf(mappings);
	}

	private static StatementType statementTypeOf(String tagName) {
		return switch (tagName) {
			case "select" -> StatementType.SELECT;
			case "insert" -> StatementType.INSERT;
			case "update" -> StatementType.UPDATE;
			case "delete" -> StatementType.DELETE;
			default -> null;
		};
	}

	private static Document parseDocument(InputStream input, String sourceDescription) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false);
			factory.setValidating(false);
			// mapper XML 不需要 DTD，而运行期这份是从磁盘读的：直接禁掉 DOCTYPE，
			// XXE 就没有入口，比只关外部实体少一类漏配。
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(input);
		}
		catch (ParserConfigurationException | SAXException | IOException ex) {
			throw new MapperXmlParseException("failed to parse mapper XML: " + sourceDescription, ex);
		}
	}

}
