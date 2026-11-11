package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 一张动态表的 Mapper XML：语句 id → FreeMarker 模板体。
 *
 * <p>XML 由 {@link MetaMapperXmlStore} 负责“已有则读、缺失则生成”；本类只负责把某条语句
 * 用运行期片段（表名、列清单、WHERE、SET、主键条件）渲染成最终 SQL。</p>
 */
public final class MetaMapperXml {

	private final String namespace;

	private final String fingerprint;

	private final Map<String, String> statements;

	private final Configuration configuration;

	private final Map<String, Template> templates = new ConcurrentHashMap<>();

	private final boolean generated;

	MetaMapperXml(String namespace, String fingerprint, Map<String, String> statements,
			Configuration configuration, boolean generated) {
		this.namespace = namespace;
		this.fingerprint = fingerprint;
		this.statements = Map.copyOf(statements);
		this.configuration = configuration;
		this.generated = generated;
	}

	public String namespace() {
		return this.namespace;
	}

	public String fingerprint() {
		return this.fingerprint;
	}

	public Map<String, String> statements() {
		return this.statements;
	}

	/** 是否是本层按元数据自动生成的文件（刷新时可安全重建）。 */
	public boolean generated() {
		return this.generated;
	}

	public String statement(String id) {
		String body = this.statements.get(id);
		if (body == null) {
			throw new MetaSqlException("mapper XML '" + this.namespace + "' has no statement '" + id
					+ "'; available: " + this.statements.keySet());
		}
		return body;
	}

	/**
	 * 渲染指定语句。{@code model} 里的 {@code table / columnsSql / whereSql / orderSql /
	 * insertColumnsSql / insertValuesSql / setSql / keyWhereSql} 由调用方按元数据算好，
	 * 不含任何用户值。
	 */
	public String render(String id, Map<String, Object> model) {
		Template template = this.templates.computeIfAbsent(id, ignored -> {
			try {
				return new Template(this.namespace + "." + id, statement(id), this.configuration);
			}
			catch (IOException ex) {
				throw new MetaSqlException("failed to compile mapper statement '" + this.namespace + "." + id + "'", ex);
			}
		});
		try (StringWriter out = new StringWriter()) {
			template.process(model, out);
			return out.toString().trim();
		}
		catch (TemplateException | IOException ex) {
			throw new MetaSqlException("failed to render mapper statement '" + this.namespace + "." + id + "'", ex);
		}
	}
}
