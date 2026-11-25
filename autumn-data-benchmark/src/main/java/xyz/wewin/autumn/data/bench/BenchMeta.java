package xyz.wewin.autumn.data.bench;

import java.util.Set;

import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperEngine;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperOptions;

/**
 * 动态层基准客户端：实体统一为通用 {@link xyz.wewin.autumn.data.common.meta.MetaClass}，
 * 不再有 CODE / GENERIC 两档。
 *
 * <p>刻意不走 {@code MetaMapper.install} 的静态门面：那个全局单例会互相覆盖，基准要和其它使用方
 * 分开。纯内存选项（{@code metaDirectory=""} + {@code generateFiles=false}）：基准不往项目目录写产物。</p>
 *
 * <p>旧基准标签 {@code code} / {@code generic} 仍保留为别名，只为复跑历史脚本；两者现在指向同一个
 * 引擎，不能再用来比较两档实体形态。</p>
 */
public final class BenchMeta {

	private static final Object LOCK = new Object();

	private static volatile MetaMapperEngine engine;

	private BenchMeta() {
	}

	public static MetaMapperEngine engine() {
		MetaMapperEngine local = engine;
		if (local == null) {
			synchronized (LOCK) {
				if (engine == null) {
					engine = createEngine();
				}
				local = engine;
			}
		}
		return local;
	}

	public static MetaMapperClient client() {
		return engine().defaultClient();
	}

	/** @deprecated CODE 档已删除，仅为兼容旧基准脚本。 */
	@Deprecated
	public static MetaMapperEngine codeEngine() {
		return engine();
	}

	/** @deprecated GENERIC 档已删除，仅为兼容旧基准脚本。 */
	@Deprecated
	public static MetaMapperEngine genericEngine() {
		return engine();
	}

	/** @deprecated CODE 档已删除，仅为兼容旧基准脚本。 */
	@Deprecated
	public static MetaMapperClient code() {
		return client();
	}

	/** @deprecated GENERIC 档已删除，仅为兼容旧基准脚本。 */
	@Deprecated
	public static MetaMapperClient generic() {
		return client();
	}

	private static MetaMapperEngine createEngine() {
		MetaMapperOptions options = new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", false,
				1000, 1000);
		return MetaMapperEngine.forDataSource("bench", BenchDatabase.dataSource(), options);
	}

}
