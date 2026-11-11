package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 动态表 Mapper XML 的加载 / 生成 / 缓存。
 *
 * <p>规则：</p>
 * <ol>
 *   <li>优先读 {@code <xmlDirectory>/<表名>.xml}；没有再看 classpath {@code meta-mappers/<表名>.xml}；</li>
 *   <li>都没有且 {@code generateXml=true}：用 FreeMarker 模板 {@code meta-mapper.xml.ftl} 按
 *       {@link MetaTable} 生成一份，落盘后使用；目录不可写时只放内存；</li>
 *   <li>文件一旦存在就直接使用，不再生成（即使表结构后续加列，片段也是运行期重算的）；
 *       需要按新结构重写生成文件时，删除该文件后下次访问会重建。</li>
 * </ol>
 *
 * <p>文件只是 SQL 骨架，运行期仍由 FreeMarker 用元数据片段渲染；XML 里不应出现用户值。</p>
 */
public final class MetaMapperXmlStore {

	private static final Logger LOG = Logger.getLogger(MetaMapperXmlStore.class.getName());

	private static final String TEMPLATE_NAME = "meta-mapper.xml.ftl";

	private static final String TEMPLATE_BASE = "xyz/wewin/autumn/data/meta/jdbc/dynamic";

	private static final String GENERATED_MARKER = "generated-by: autumn-data-meta-jdbc";

	private static final Pattern FINGERPRINT = Pattern.compile("fingerprint:\\s*([0-9a-fA-F]+)");

	private final Path directory;

	private final boolean generateXml;

	private final ClassLoader classLoader;

	private final Configuration configuration;

	private final ConcurrentHashMap<String, MetaMapperXml> cache = new ConcurrentHashMap<>();

	public MetaMapperXmlStore(String xmlDirectory, boolean generateXml) {
		this(xmlDirectory, generateXml, defaultClassLoader());
	}

	public MetaMapperXmlStore(String xmlDirectory, boolean generateXml, ClassLoader classLoader) {
		this.directory = xmlDirectory == null || xmlDirectory.isBlank() ? null : Paths.get(xmlDirectory);
		this.generateXml = generateXml;
		this.classLoader = classLoader == null ? defaultClassLoader() : classLoader;
		this.configuration = new Configuration(Configuration.VERSION_2_3_32);
		this.configuration.setClassLoaderForTemplateLoading(this.classLoader, TEMPLATE_BASE);
		this.configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
		this.configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
		this.configuration.setLogTemplateExceptions(false);
		this.configuration.setAutoEscapingPolicy(Configuration.DISABLE_AUTO_ESCAPING_POLICY);
	}

	/** 取某张表的 Mapper XML；进程内按表名缓存。 */
	public MetaMapperXml load(MetaTable table) {
		String key = normalize(table.name());
		MetaMapperXml cached = this.cache.get(key);
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			cached = this.cache.get(key);
			if (cached != null) {
				return cached;
			}
			MetaMapperXml loaded = load0(table);
			this.cache.put(key, loaded);
			return loaded;
		}
	}

	/** 渲染指定语句。 */
	public String render(MetaTable table, String statementId, Map<String, Object> model) {
		return load(table).render(statementId, model);
	}

	/** 元数据刷新时同步丢掉 XML 缓存；生成的 XML 下次会按新 fingerprint 重建。 */
	public void refresh(String tableName) {
		this.cache.remove(normalize(tableName));
	}

	public void refreshAll() {
		this.cache.clear();
	}

	private MetaMapperXml load0(MetaTable table) {
		String fingerprint = fingerprint(table);
		String fileName = fileName(table);
		String content = null;

		Path file = filePath(fileName);
		if (file != null && Files.isRegularFile(file)) {
			try {
				content = Files.readString(file, StandardCharsets.UTF_8);
			}
			catch (IOException ex) {
				throw new MetaSqlException("failed to read mapper XML '" + file + "'", ex);
			}
		}
		if (content == null) {
			content = readClasspath(fileName);
		}
		if (content == null) {
			if (!this.generateXml) {
				throw new MetaSqlException("mapper XML not found for table '" + table.name()
						+ "'; expected " + (file == null ? "classpath meta-mappers/" + fileName : file.toString()));
			}
			content = generate(table, fingerprint);
			if (file != null) {
				write(file, content);
			}
		}
		return parse(content, namespace(table), fingerprintOf(content), isGenerated(content));
	}

	private String generate(MetaTable table, String fingerprint) {
		try {
			Template template = this.configuration.getTemplate(TEMPLATE_NAME);
			Map<String, Object> model = Map.of("namespace", namespace(table), "fingerprint", fingerprint);
			try (StringWriter out = new StringWriter()) {
				template.process(model, out);
				return out.toString();
			}
		}
		catch (IOException | TemplateException ex) {
			throw new MetaSqlException("failed to generate mapper XML for table '" + table.name() + "'", ex);
		}
	}

	private void write(Path file, String content) {
		if (this.directory == null) {
			return;
		}
		try {
			Files.createDirectories(this.directory);
			Files.writeString(file, content, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			LOG.log(Level.WARNING, "failed to write mapper XML '" + file + "'; keeping it in memory", ex);
		}
	}

	private String readClasspath(String fileName) {
		String resource = "meta-mappers/" + fileName;
		try (InputStream input = this.classLoader.getResourceAsStream(resource)) {
			if (input == null) {
				return null;
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to read classpath mapper XML '" + resource + "'", ex);
		}
	}

	private MetaMapperXml parse(String content, String fallbackNamespace, String fallbackFingerprint,
			boolean generated) {
		Document document = parseDocument(content);
		Element root = document.getDocumentElement();
		if (!"mapper".equals(root.getTagName())) {
			throw new MetaSqlException("mapper XML root element must be <mapper> but was <" + root.getTagName() + ">");
		}
		String namespace = root.getAttribute("namespace");
		if (namespace == null || namespace.isBlank()) {
			namespace = fallbackNamespace;
		}
		Map<String, String> statements = new LinkedHashMap<>();
		for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			Element element = (Element) child;
			if (!isStatementTag(element.getTagName())) {
				continue;
			}
			String id = element.getAttribute("id");
			if (id == null || id.isBlank()) {
				throw new MetaSqlException("<" + element.getTagName() + "> requires an 'id' attribute");
			}
			String body = element.getTextContent().trim();
			if (body.isEmpty()) {
				throw new MetaSqlException("mapper statement '" + id + "' is empty");
			}
			if (statements.put(id, body) != null) {
				throw new MetaSqlException("duplicate mapper statement id '" + id + "'");
			}
		}
		return new MetaMapperXml(namespace, fallbackFingerprint, statements, this.configuration, generated);
	}

	private static Document parseDocument(String content) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false);
			factory.setValidating(false);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			return builder.parse(new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (ParserConfigurationException | SAXException | IOException ex) {
			throw new MetaSqlException("failed to parse mapper XML", ex);
		}
	}

	private static boolean isStatementTag(String tagName) {
		return "select".equals(tagName) || "insert".equals(tagName) || "update".equals(tagName)
				|| "delete".equals(tagName);
	}

	private Path filePath(String fileName) {
		return this.directory == null ? null : this.directory.resolve(fileName);
	}

	private static String fileName(MetaTable table) {
		String safe = table.name().replaceAll("[^A-Za-z0-9_.-]", "_");
		safe = safe.replace("..", "_");
		if (safe.startsWith(".")) {
			safe = "_" + safe;
		}
		return safe + ".xml";
	}

	private static String namespace(MetaTable table) {
		return "dynamic." + table.name().replaceAll("[^A-Za-z0-9_.-]", "_");
	}

	private static boolean isGenerated(String content) {
		return content.contains(GENERATED_MARKER);
	}

	private static String fingerprintOf(String content) {
		Matcher matcher = FINGERPRINT.matcher(content);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static String fingerprint(MetaTable table) {
		StringBuilder raw = new StringBuilder(table.name()).append('|').append(table.schema()).append('|')
				.append(table.logicDeleteColumn()).append('|');
		for (MetaProperty column : table.columns()) {
			raw.append(column.getName()).append(':').append(column.getJdbcType()).append(':')
					.append(column.getSqlTypeName()).append(':').append(column.isNullable()).append(':')
					.append(column.isPrimaryKey()).append(':').append(column.isAutoIncrement()).append(':')
					.append(column.getDefaultValue()).append('|');
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length);
			for (byte b : hash) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

	private static String normalize(String tableName) {
		return tableName.toLowerCase(Locale.ROOT);
	}

	private static ClassLoader defaultClassLoader() {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return loader == null ? MetaMapperXmlStore.class.getClassLoader() : loader;
	}
}
