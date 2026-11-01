package xyz.wewin.autumn.data.using;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import xyz.wewin.autumn.data.using.demo.UserRepository;
import xyz.wewin.autumn.data.using.demo.UserRepositoryImpl;

/**
 * 使用方示例应用：模拟真实业务工程「只引 starter + 配 spring.datasource」的形态。
 *
 * <p>注意：{@code userRepository} 手工注册是**临时桥**——生成的 Impl 尚未被 Spring 感知。
 * 正式方案是 starter 提供仓储注册（扫描 Repository 接口 → 绑定编译期生成的 Impl，
 * 对应 Spring Data 的 @EnableJdbcRepositories / Micronaut 的自动发现，
 * autumn 将做成约定版），随 M2 一起实现后删除本临时配置。</p>
 */
@SpringBootApplication
public class UsingApplication {

	@Bean
	UserRepository userRepository() {
		return new UserRepositoryImpl();
	}

	public static void main(String[] args) {
		SpringApplication.run(UsingApplication.class, args);
	}
}
