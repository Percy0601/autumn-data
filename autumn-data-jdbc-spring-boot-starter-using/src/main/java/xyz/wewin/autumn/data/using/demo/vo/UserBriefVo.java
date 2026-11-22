package xyz.wewin.autumn.data.using.demo.vo;

/**
 * 自定义返回类型（VO）示例之三：<b>普通类形态</b>（无参构造 + 公共 setter）。
 *
 * <p>成员由公共 {@code setXxx} 决定，{@code setUsername} → 成员 {@code username}；生成代码先
 * {@code new UserBriefVo()} 再逐个 setter 写入，同样零反射。成员名按实体字段名映射到列。</p>
 */
public class UserBriefVo {

	private Long id;

	private String username;

	public Long getId() {
		return this.id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getUsername() {
		return this.username;
	}

	public void setUsername(String username) {
		this.username = username;
	}
}
