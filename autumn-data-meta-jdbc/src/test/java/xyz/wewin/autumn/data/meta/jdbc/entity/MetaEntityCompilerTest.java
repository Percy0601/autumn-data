package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.net.URL;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** 运行期编译的 classpath 解析：只认 {@code file:} 协议，其它一律跳过。 */
class MetaEntityCompilerTest {

	@TempDir
	Path directory;

	/** Spring Boot 可执行 jar 的 CodeSource 是 jar: URI，绝不能让 classpath 静态初始化炸掉。 */
	@Test
	void nonFileCodeSourcesAreSkippedWhenBuildingCompilerClasspath() throws Exception {
		assertThat(MetaEntityCompiler.pathFromUri(null)).isNull();
		assertThat(MetaEntityCompiler.pathFromUri(new URL("jar:file:/app.jar!/BOOT-INF/classes!/"))).isNull();
		assertThat(MetaEntityCompiler.pathFromUri(this.directory.toAbsolutePath().toUri().toURL())).isNotNull();
	}
}
