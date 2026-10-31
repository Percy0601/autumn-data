package xyz.wewin.autumn.data.using.demo;

import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.data.jdbc.repository.CrudRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编译期生成的 {@code UserRepositoryImpl} 验证（管道版）：
 * 生成 → 编译 → 实现接口 → 实例化。方法体目前是占位实现，M2 下一步接真实 SQL。
 */
class GeneratedUserRepositoryImplTest {

	@Test
	void implementationGeneratedAtCompileTime() throws Exception {
		Class<?> impl = Class.forName("xyz.wewin.autumn.data.using.demo.UserRepositoryImpl");
		assertThat(CrudRepository.class).isAssignableFrom(impl);
		Object instance = impl.getDeclaredConstructor().newInstance();
		assertThat(instance).isInstanceOf(UserRepository.class);
		// 骨架阶段：方法存在但尚未实现
		assertThatThrownBy(() -> ((UserRepository) instance).count())
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
