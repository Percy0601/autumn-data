package xyz.wewin.autumn.data.jdbc.spring.fixtures;

import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;

/**
 * 编译期生成类的替身：清单（{@code META-INF/autumn-data/repositories}）里对应
 * {@code xyz.wewin.autumn.data.jdbc.spring.fixtures.WidgetRepository}，注册器按单参构造器自动装配。
 */
public class WidgetRepositoryImpl {

	private final AutumnJdbc autumn;

	public WidgetRepositoryImpl(AutumnJdbc autumn) {
		this.autumn = autumn;
	}

	public AutumnJdbc autumn() {
		return this.autumn;
	}

}
