package xyz.wewin.autumn.data.jdbc.page;

/**
 * 分页请求：页码从 0 开始。
 *
 * <p>翻页按主键顺序稳定输出；按业务字段排序随派生查询一起提供，
 * 因为排序字段必须在编译期映射到列名才安全（避免把用户输入直接拼进 ORDER BY）。
 * 需要「分页 + 排序」时，声明返回 {@code Page<T>} 且末位形参是本类型的派生查询方法，
 * 排序同样来自方法名的 {@code OrderBy}（见 docs/spring-data-derived-query.md 第 6 节）。</p>
 */
public record Pageable(int page, int size) {

	public Pageable {
		if (page < 0) {
			throw new IllegalArgumentException("page must be >= 0 but was " + page);
		}
		if (size <= 0) {
			throw new IllegalArgumentException("size must be > 0 but was " + size);
		}
	}

	public static Pageable of(int page, int size) {
		return new Pageable(page, size);
	}

	/** 需要跳过的行数。 */
	public long offset() {
		return (long) this.page * this.size;
	}

	/** 本页最多取多少行。 */
	public long limit() {
		return this.size;
	}
}
