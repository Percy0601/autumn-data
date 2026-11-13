package xyz.wewin.autumn.data.meta.jdbc.entity;

import org.junit.jupiter.api.Test;

import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 策略选择：普通 JVM 走 CODE，Native Image 里自动走 GENERIC；显式配置按字面解析。
 *
 * <p>用设置 {@code org.graalvm.nativeimage.imagecode} 的方式模拟 native 运行时，
 * 测试结束恢复原值，避免影响同 JVM 里的其它用例。</p>
 */
class MetaEntityStrategyTest {

	private static final String NATIVE_PROPERTY = "org.graalvm.nativeimage.imagecode";

	@Test
	void autoPrefersCodegenOnRegularJvm() {
		withNativeProperty(null, () -> {
			assertThat(MetaEntityStrategy.inNativeImage()).isFalse();
			assertThat(MetaEntityStrategy.auto()).isEqualTo(MetaEntityStrategy.CODE);
		});
	}

	@Test
	void autoFallsBackToGenericInsideNativeImage() {
		withNativeProperty("runtime", () -> {
			assertThat(MetaEntityStrategy.inNativeImage()).isTrue();
			assertThat(MetaEntityStrategy.auto()).isEqualTo(MetaEntityStrategy.GENERIC);
		});
	}

	@Test
	void resolvesExplicitModes() {
		assertThat(MetaEntityStrategy.resolve(null)).isEqualTo(MetaEntityStrategy.auto());
		assertThat(MetaEntityStrategy.resolve(" auto ")).isEqualTo(MetaEntityStrategy.auto());
		assertThat(MetaEntityStrategy.resolve("true")).isEqualTo(MetaEntityStrategy.CODE);
		assertThat(MetaEntityStrategy.resolve("generic")).isEqualTo(MetaEntityStrategy.GENERIC);
		assertThat(MetaEntityStrategy.resolve("native")).isEqualTo(MetaEntityStrategy.GENERIC);
		assertThatThrownBy(() -> MetaEntityStrategy.resolve("maybe")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void explicitCodegenIsRejectedInsideNativeImage() {
		withNativeProperty("runtime", () -> assertThatThrownBy(() -> MetaEntityStrategy.resolve("code"))
				.isInstanceOf(MetaSqlException.class)
				.hasMessageContaining("Native Image"));
	}

	/** 设置/清除 native 模拟属性并保证恢复；native 构建期由 GraalVM 自己设置同名属性。 */
	static void withNativeProperty(String value, Runnable action) {
		String previous = System.getProperty(NATIVE_PROPERTY);
		try {
			if (value == null) {
				System.clearProperty(NATIVE_PROPERTY);
			}
			else {
				System.setProperty(NATIVE_PROPERTY, value);
			}
			action.run();
		}
		finally {
			if (previous == null) {
				System.clearProperty(NATIVE_PROPERTY);
			}
			else {
				System.setProperty(NATIVE_PROPERTY, previous);
			}
		}
	}
}
