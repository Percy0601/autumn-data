package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code meta/{数据源}/{命名空间}/{表}.xml} 语句库：懒加载、宽松解析、按修改时间重读。
 */
class MetaSqlFileStoreTest {

	@TempDir
	Path root;

	@Test
	void loadsLazilyAndCachesTheParsedFile() throws IOException {
		write("db", "ns", "form_order", "<select id=\"metaFind\">SELECT 1 FROM form_order</select>");
		MetaSqlFileStore store = new MetaSqlFileStore(this.root);

		assertThat(store.size()).isZero(); // 构造时不扫目录

		assertThat(store.statement("db", "ns", "form_order", "metaFind")).isNotNull();
		assertThat(store.size()).isEqualTo(1);
		assertThat(store.statement("db", "ns", "form_order", "metaFind")).isNotNull();
		assertThat(store.size()).isEqualTo(1); // 命中缓存，不重复解析
	}

	@Test
	void missingFileIsNotAnError() {
		MetaSqlFileStore store = new MetaSqlFileStore(this.root);

		assertThat(store.statements("db", "ns", "form_order")).isEmpty();
		assertThat(store.statement("db", "ns", "form_order", "metaFind")).isNull();
		assertThat(store.statementIds("db", "ns", "form_order")).isEmpty();
		assertThat(store.location("db", "ns", "form_order")).contains("form_order.xml");
	}

	@Test
	void statementsWithoutMapperRootAreWrapped() throws IOException {
		// 一个文件里只写几条语句，不写 <?xml?> 与 <mapper> 壳
		write("db", "ns", "form_order", """
				<select id="metaFind">SELECT * FROM form_order</select>
				<update id="metaUpdate">UPDATE form_order SET status = #{status} WHERE id = #{id}</update>
				""");
		MetaSqlFileStore store = new MetaSqlFileStore(this.root);

		assertThat(store.statementIds("db", "ns", "form_order")).containsExactly("metaFind", "metaUpdate");
		assertThat(store.statement("db", "ns", "form_order", "metaUpdate").type())
				.isEqualTo(xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementType.UPDATE);
	}

	@Test
	void mapperRootWithoutNamespaceIsFilledIn() throws IOException {
		write("db", "ns", "form_order", """
				<?xml version="1.0" encoding="UTF-8"?>
				<mapper>
				    <select id="metaFind">SELECT * FROM form_order</select>
				</mapper>
				""");
		MetaSqlFileStore store = new MetaSqlFileStore(this.root);

		assertThat(store.statement("db", "ns", "form_order", "metaFind")).isNotNull();
	}

	@Test
	void sameTableUnderDifferentNamespacesDoesNotCollide() throws IOException {
		write("db", "tenant_a", "form_order", "<select id=\"metaFind\">SELECT 'a' AS tag FROM form_order</select>");
		write("db", "tenant_b", "form_order", "<select id=\"metaFind\">SELECT 'b' AS tag FROM form_order</select>");
		MetaSqlFileStore store = new MetaSqlFileStore(this.root);

		assertThat(store.statement("db", "tenant_a", "form_order", "metaFind").render(java.util.Map.of()).sql())
				.contains("'a'");
		assertThat(store.statement("db", "tenant_b", "form_order", "metaFind").render(java.util.Map.of()).sql())
				.contains("'b'");
		assertThat(store.size()).isEqualTo(2);
	}

	@Test
	void readsTheFileAgainAfterItChanges() throws IOException {
		Path file = write("db", "ns", "form_order", "<select id=\"metaFind\">SELECT 1 AS tag FROM form_order</select>");
		MetaSqlFileStore store = new MetaSqlFileStore(this.root);
		assertThat(store.statement("db", "ns", "form_order", "metaFind").render(java.util.Map.of()).sql())
				.contains("1 AS tag");

		// 改文件：长度变了，下次访问必须重读，不需要重启也不需要显式刷新
		Files.writeString(file, "<select id=\"metaFind\">SELECT 2222 AS tag, 3333 AS extra FROM form_order</select>",
				StandardCharsets.UTF_8);

		assertThat(store.statement("db", "ns", "form_order", "metaFind").render(java.util.Map.of()).sql())
				.contains("2222 AS tag");
	}

	@Test
	void refreshDropsTheParsedCopy() throws IOException {
		write("db", "ns", "form_order", "<select id=\"metaFind\">SELECT 1 AS tag FROM form_order</select>");
		MetaSqlFileStore store = new MetaSqlFileStore(this.root);
		assertThat(store.statement("db", "ns", "form_order", "metaFind")).isNotNull();
		assertThat(store.size()).isEqualTo(1);

		store.refresh("db", "ns", "form_order");
		assertThat(store.size()).isZero();

		store.refreshAll();
		assertThat(store.size()).isZero();
	}

	@Test
	void pathSegmentsCannotEscapeTheRoot() {
		MetaSqlFileStore store = new MetaSqlFileStore(this.root);

		// ".." 被收口成安全文件名：不会读到根目录之外，只是找不到文件
		assertThat(store.statements("..", "..", "form_order")).isEmpty();
		assertThat(store.statements("db", "ns", "../../etc/passwd")).isEmpty();
	}

	private Path write(String datasource, String namespace, String table, String content) throws IOException {
		Path file = this.root.resolve(datasource).resolve(namespace).resolve(table + ".xml");
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

}
