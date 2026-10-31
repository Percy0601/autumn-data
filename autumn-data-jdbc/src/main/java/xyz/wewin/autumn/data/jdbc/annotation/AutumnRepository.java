package xyz.wewin.autumn.data.jdbc.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个仓储接口，交由 autumn-data-jdbc-compiler 在编译期生成实现（参考 Micronaut Data）。
 *
 * <p>被标注的接口必须继承 {@link xyz.wewin.autumn.data.jdbc.repository.Repository}
 * （或其子接口如 {@code CrudRepository}），违反约定将在编译期报错，而非运行期。</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface AutumnRepository {

	/** 可选：数据来源限定符，多 DataSource 场景下选择绑定的仓储（预留）。 */
	String value() default "";
}
