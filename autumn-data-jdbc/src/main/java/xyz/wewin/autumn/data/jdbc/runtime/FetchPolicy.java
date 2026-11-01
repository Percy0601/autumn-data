package xyz.wewin.autumn.data.jdbc.runtime;

/**
 * 取数上限策略（编译期生成的实现类在运行期读取它，两个数字都不写死在生成代码里）。
 *
 * @param associationLimit 一次加载一对多集合时，每个子表查询最多取回多少行（默认 1000）
 * @param batchSize        JDBC 批处理分块大小，决定一条批量语句打包多少行
 */
public record FetchPolicy(int associationLimit, int batchSize) {

	public static final int DEFAULT_ASSOCIATION_LIMIT = 1000;

	public static final int DEFAULT_BATCH_SIZE = 100;

	public FetchPolicy {
		if (associationLimit <= 0) {
			throw new IllegalArgumentException("associationLimit must be > 0 but was " + associationLimit);
		}
		if (batchSize <= 0) {
			throw new IllegalArgumentException("batchSize must be > 0 but was " + batchSize);
		}
	}

	public static FetchPolicy defaults() {
		return new FetchPolicy(DEFAULT_ASSOCIATION_LIMIT, DEFAULT_BATCH_SIZE);
	}
}
