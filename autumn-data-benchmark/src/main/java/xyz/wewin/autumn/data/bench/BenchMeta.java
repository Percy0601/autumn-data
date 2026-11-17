package xyz.wewin.autumn.data.bench;

import java.util.Set;

import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperEngine;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperOptions;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityStrategy;

/**
 * meta-jdbc 两档实体形态的引擎与客户端：{@code CODE}（运行期生成强类型子类）与 {@code GENERIC}。
 *
 * <p>刻意不走 {@code MetaMapper.install} 的静态门面：那个全局单例会互相覆盖，两档要同时存在
 * 只能各建一个 {@link MetaMapperEngine}。两边共用同一个 DataSource，连接池成本完全对齐。</p>
 *
 * <p>纯内存选项（{@code metaDirectory=""} + {@code generateFiles=false}）：基准不能往项目目录
 * 里写 meta 产物。</p>
 */
public final class BenchMeta {

	private static final Object LOCK = new Object();

	private static volatile MetaMapperEngine codeEngine;

	private static volatile MetaMapperEngine genericEngine;

	private BenchMeta() {
	}

	/** CODE 档引擎：运行期 javac 生成并加载强类型实体类，逐行 READER 直接写字段。 */
	public static MetaMapperEngine codeEngine() {
		MetaMapperEngine local = codeEngine;
		if (local == null) {
			synchronized (LOCK) {
				if (codeEngine == null) {
					codeEngine = engine(MetaEntityStrategy.CODE);
				}
				local = codeEngine;
			}
		}
		return local;
	}

	/** GENERIC 档引擎：不生成子类，逐行写通用 MetaClass 属性列表。 */
	public static MetaMapperEngine genericEngine() {
		MetaMapperEngine local = genericEngine;
		if (local == null) {
			synchronized (LOCK) {
				if (genericEngine == null) {
					genericEngine = engine(MetaEntityStrategy.GENERIC);
				}
				local = genericEngine;
			}
		}
		return local;
	}

	public static MetaMapperClient code() {
		return codeEngine().defaultClient();
	}

	public static MetaMapperClient generic() {
		return genericEngine().defaultClient();
	}

	private static MetaMapperEngine engine(MetaEntityStrategy strategy) {
		MetaMapperOptions options = new MetaMapperOptions(1000, FetchPolicy.defaults(), Set.of(), "", false, 1000,
				strategy);
		return MetaMapperEngine.forDataSource("bench", BenchDatabase.dataSource(), options);
	}

}
