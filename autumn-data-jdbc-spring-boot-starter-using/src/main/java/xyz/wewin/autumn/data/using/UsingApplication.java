package xyz.wewin.autumn.data.using;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 使用方示例应用：模拟真实业务工程「只引 starter + 配 spring.datasource」的形态。
 *
 * <p>仓储实现由 autumn-data-jdbc-compiler 在编译期生成，类上带 {@code @Service}，
 * 随组件扫描注册为 Bean（构造注入 {@code JdbcClient}），使用方不需要任何装配代码。</p>
 */
@SpringBootApplication
public class UsingApplication {

	static void main(String[] args) {
		SpringApplication.run(UsingApplication.class, args);
	}
}
