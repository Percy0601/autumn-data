package xyz.wewin.autumn.data.meta.jdbc;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;

/**
 * FreeMarker 配置中心（meta 模块核心组件之一）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>从类路径 {@code META-INF/mappers} 下加载 {@code .ftl} 模板（替代 MyBatis 的 XML SQL）；</li>
 *   <li>提供 {@link #refresh()} 方法清空模板缓存，实现 <b>运行时动态加载 / 热更新</b> SQL；</li>
 *   <li>对模板解析异常直接抛出（开发期更直观）。</li>
 * </ul>
 *
 * <p>后续步骤将在此基础上实现 {@link MetaSqlSession}：把 Mapper 接口方法映射到
 * {@code namespace + id} 的模板，渲染出 SQL 后通过 JdbcClient 执行。</p>
 */
public class MetaConfiguration {

	private final Configuration freemarkerConfig;

	public MetaConfiguration() {
		this.freemarkerConfig = new Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS);
		this.freemarkerConfig.setClassLoaderForTemplateLoading(
				Thread.currentThread().getContextClassLoader(), "META-INF/mappers");
		this.freemarkerConfig.setDefaultEncoding("UTF-8");
		this.freemarkerConfig.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
		this.freemarkerConfig.setLogTemplateExceptions(false);
		this.freemarkerConfig.setWrapUncheckedExceptions(true);
	}

	/** 按名称获取模板（名称相对于 {@code META-INF/mappers}，例如 {@code person/selectById.ftl}）。 */
	public Template getTemplate(String name) throws IOException {
		return freemarkerConfig.getTemplate(name);
	}

	/** 动态加载：清空模板缓存，下次获取时从类路径重新读取最新 {@code .ftl}。 */
	public void refresh() {
		freemarkerConfig.clearTemplateCache();
	}

	public Configuration getFreemarkerConfig() {
		return freemarkerConfig;
	}
}
