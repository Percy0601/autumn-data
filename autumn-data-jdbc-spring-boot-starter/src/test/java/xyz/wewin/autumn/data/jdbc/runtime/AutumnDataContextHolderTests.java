package xyz.wewin.autumn.data.jdbc.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一次性上下文的取值规则：级联模式与字段投影各占一维，互相不覆盖，被最近一次取实体的查询一起消费；
 * 线程池场景靠 {@code wrap} 在提交时捕获。
 */
class AutumnDataContextHolderTests {

	@AfterEach
	void clearContext() {
		AutumnDataContextHolder.clear();
	}

	@Test
	void noProjectionMeansCompileTimeFullColumnSelect() {
		assertThat(AutumnDataContextHolder.projection()).isNull();
		assertThat(AutumnDataContextHolder.get().mode()).isEqualTo(AssociationMode.DEFAULT);
	}

	@Test
	void modeAndProjectionAreIndependentAxesOfTheSameOneShotContext() {
		AutumnDataContextHolder.selectFields("username");
		assertThat(AutumnDataContextHolder.projection()).isEqualTo(FieldProjection.include("username"));
		assertThat(AutumnDataContextHolder.get().mode()).isEqualTo(AssociationMode.DEFAULT);

		// 切模式不丢投影
		AutumnDataContextHolder.disableCascade();
		assertThat(AutumnDataContextHolder.get().mode()).isEqualTo(AssociationMode.DISABLED);
		assertThat(AutumnDataContextHolder.projection()).isEqualTo(FieldProjection.include("username"));

		// 换投影不丢模式
		AutumnDataContextHolder.excludeFields("email");
		assertThat(AutumnDataContextHolder.get().mode()).isEqualTo(AssociationMode.DISABLED);
		assertThat(AutumnDataContextHolder.projection()).isEqualTo(FieldProjection.exclude("email"));

		// 生成代码消费一次，两项设置一起失效
		AutumnDataContextHolder.consumePending();
		assertThat(AutumnDataContextHolder.projection()).isNull();
		assertThat(AutumnDataContextHolder.get().mode()).isEqualTo(AssociationMode.DEFAULT);
	}

	@Test
	void requiredColumnsAreNeverProjectedAway() {
		FieldProjection onlyName = FieldProjection.include("name");
		assertThat(onlyName.projects("name", false)).isTrue();
		assertThat(onlyName.projects("age", false)).isFalse();
		// 主键与多对一外键：白名单没写也恒取，否则级联装配与回填就断了
		assertThat(onlyName.projects("id", true)).isTrue();

		FieldProjection withoutEmail = FieldProjection.exclude("email");
		assertThat(withoutEmail.projects("name", false)).isTrue();
		assertThat(withoutEmail.projects("email", false)).isFalse();
		assertThat(withoutEmail.projects("authorId", true)).isTrue();
	}

	@Test
	void emptyOrBlankFieldListIsRejected() {
		assertThatThrownBy(() -> FieldProjection.include()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at least one");
		assertThatThrownBy(() -> FieldProjection.exclude(" "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("null/blank");
	}

	@Test
	void persistentContextIsNotConsumedByAQuery() {
		AutumnDataContextHolder.set(AutumnDataContext.defaults().withProjection(FieldProjection.include("id")));
		AutumnDataContextHolder.consumePending();
		// 持久（请求级）上下文要 clear 才失效，不会被「最近一次查询」消费
		assertThat(AutumnDataContextHolder.projection()).isEqualTo(FieldProjection.include("id"));
	}

	@Test
	void wrapCarriesTheOneShotProjectionIntoAPooledThread() throws Exception {
		FieldProjection[] seen = new FieldProjection[2];
		AssociationMode[] modes = new AssociationMode[2];

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			// 先让 worker 建好：它继承的是「创建那一刻」的上下文，此时还没有任何设置
			executor.submit(() -> {
			}).get(5, TimeUnit.SECONDS);

			AutumnDataContextHolder.selectFields("username");
			AutumnDataContextHolder.disableCascade();
			executor.submit(AutumnDataContextHolder.wrap(() -> capture(seen, modes, 0))).get(5, TimeUnit.SECONDS);
			// wrap 在任务结束后把 worker 还原成它原本的状态（没有上下文）
			executor.submit(() -> capture(seen, modes, 1)).get(5, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(seen[0]).isEqualTo(FieldProjection.include("username"));
		assertThat(modes[0]).isEqualTo(AssociationMode.DISABLED);
		assertThat(seen[1]).isNull();
	}

	private static void capture(FieldProjection[] seen, AssociationMode[] modes, int slot) {
		seen[slot] = AutumnDataContextHolder.projection();
		modes[slot] = AutumnDataContextHolder.get().mode();
	}

}
