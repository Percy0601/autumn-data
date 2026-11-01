package xyz.wewin.autumn.data.jdbc.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在仓储方法上直接给出 SQL，绕过方法名派生（对标 Spring Data 的 {@code @Query}）。
 *
 * <p>编译期由 autumn-data-jdbc-compiler 读取，SQL 内联成生成类的
 * {@code private static final String} 常量，运行期原样执行：</p>
 * <ul>
 *   <li>返回 {@code List<Entity>} → 走 {@code query}；</li>
 *   <li>返回 {@code Optional<Entity>} → 走 {@code queryOptional}；</li>
 *   <li>返回数值（{@code long/int/…}）：SQL 以 {@code SELECT} 开头按计数走 {@code count}，
 *       其余（{@code INSERT/UPDATE/DELETE}）走 {@code update} 拿受影响行数；</li>
 *   <li>返回 {@code void}：必须是 {@code INSERT/UPDATE/DELETE}；</li>
 *   <li>不支持裸实体返回（与派生查询一致，0 行时无法表达）。</li>
 * </ul>
 *
 * <p>参数按声明顺序绑定到 {@code ?} 占位符；集合参数需自行在 SQL 里写展开后的占位符，
 * 或复用 {@code :ids} 标记交给运行期按集合大小展开（与派生查询的 {@code In} 一致）。</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Query {

	/** 要执行的 SQL（SELECT / INSERT / UPDATE / DELETE 均可）。 */
	String value();
}
