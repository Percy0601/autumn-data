package xyz.wewin.autumn.data.meta.jdbc.entity;

import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 动态实体的生成策略。
 *
 * <p>默认 {@code auto}：普通 JVM 上走 {@link #CODE}，运行期给每张表生成强类型
 * {@code MetaClass} 子类；GraalVM Native Image 里自动退到 {@link #GENERIC}，行数据直接写进
 * 通用 {@link xyz.wewin.autumn.data.common.meta.MetaClass}（属性列表即带类型的 Map）。</p>
 *
 * <p>为什么 native 下不能走代码生成：Native Image 是 closed-world，镜像里没有
 * {@code javax.tools}（{@code ToolProvider.getSystemJavaCompiler()} 返回 {@code null}），
 * 进程内也不允许 {@code defineClass} 加载新类。<b>这不是配置问题，是模型约束</b>，
 * 所以 native 模式必须绕开「运行期产生新类」这条链路。</p>
 *
 * <p>两档对外契约一致：都返回 {@code MetaClass}、都支持 CRUD / Mapper / resultMap / 分页 /
 * JSON；差异只在 {@link #CODE} 多出类型化 getter/setter 与「逐行零反射」，{@link #GENERIC}
 * 每行按列名写入属性列表（无反射、无代码生成，因此可以被 native-image 静态分析）。</p>
 */
public enum MetaEntityStrategy {

	/** 运行期生成并编译 {@code MetaClass} 子类；需要完整 JDK，JVM 默认。 */
	CODE,

	/** 不生成子类，逐行写通用 {@code MetaClass}；零反射，Native Image 默认。 */
	GENERIC;

	/** GraalVM Native Image 在构建期/运行期都会设置这个系统属性；普通 JVM 上为 {@code null}。 */
	private static final String NATIVE_IMAGE_PROPERTY = "org.graalvm.nativeimage.imagecode";

	/** 当前进程是否运行在 Native Image 里（构建期也算，避免构建期初始化走到代码生成）。 */
	public static boolean inNativeImage() {
		String code = System.getProperty(NATIVE_IMAGE_PROPERTY);
		return code != null && !code.isBlank();
	}

	/** 未显式配置时的默认：native → {@link #GENERIC}，否则 {@link #CODE}。 */
	public static MetaEntityStrategy auto() {
		return inNativeImage() ? GENERIC : CODE;
	}

	/**
	 * 解析配置值：{@code auto}/{@code true}/{@code code}/{@code codegen} → {@link #CODE}（native 下拒绝），
	 * {@code false}/{@code generic}/{@code native} → {@link #GENERIC}。
	 */
	public static MetaEntityStrategy resolve(String value) {
		if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim())) {
			return auto();
		}
		String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
		return switch (normalized) {
			case "true", "code", "codegen" -> requireCodegen("entity-codegen=" + value);
			case "false", "generic", "native" -> GENERIC;
			default -> throw new IllegalArgumentException(
					"unknown entity codegen mode '" + value + "'; expected auto|true|false|code|generic");
		};
	}

	/** native 里没有 javac / defineClass，显式要求代码生成时尽早失败，而不是等到首次访问表。 */
	public static MetaEntityStrategy requireCodegen(String source) {
		if (inNativeImage()) {
			throw new MetaSqlException("entity code generation is not available in GraalVM Native Image ("
					+ source + "); use entity-codegen=generic or auto");
		}
		return CODE;
	}
}
