package xyz.wewin.autumn.data.jdbc.page;

import java.util.List;

/**
 * 一页结果：内容 + 页码 + 页大小 + 满足条件的总行数。
 *
 * @param <T> 元素类型
 */
public record Page<T>(List<T> content, int page, int size, long total) {

	public Page {
		content = List.copyOf(content);
	}

	public static <T> Page<T> of(List<T> content, Pageable pageable, long total) {
		return new Page<>(content, pageable.page(), pageable.size(), total);
	}

	/** 总页数（向上取整）。 */
	public int totalPages() {
		return (int) ((this.total + this.size - 1) / this.size);
	}

	public boolean hasNext() {
		return (long) (this.page + 1) * this.size < this.total;
	}

	public boolean isEmpty() {
		return this.content.isEmpty();
	}
}
