package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * 自定义返回类型（VO）的编译期提取：判断形态（record / 普通类）并收集可映射的成员。
 *
 * <p>约定（违反即在方法声明处报错，不留到运行期）：</p>
 * <ul>
 *   <li>VO 必须是 {@code public} 的 {@code record} 或 {@code public} 普通类 —— 生成实现类要能直接
 *       {@code new} 它，且全程零反射，因此不支持基于接口的投影；</li>
 *   <li>{@code record}：每个组件都是一个成员（规范构造器按声明顺序接收）；</li>
 *   <li>普通类：每个公共 {@code setXxx} 是一个成员，必须有可访问的无参构造器；</li>
 *   <li>成员类型只收 JDBC 标量（基本类型与包装类、{@code String}、{@code BigDecimal}、
 *       {@code java.time}、{@code java.sql}、{@code UUID}、{@code byte[]}）与枚举；</li>
 *   <li>{@code @Column(name)} 可显式指定派生查询的实体列，或 {@code @Query} 结果集的列别名。</li>
 * </ul>
 */
final class VoIntrospector {

	private static final String COLUMN = "jakarta.persistence.Column";

	private VoIntrospector() {
	}

	/** 提取失败时输出 ERROR 诊断并返回 {@code null}，由调用方跳过生成。 */
	static VoMetadata introspect(TypeMirror type, Elements elements, Types types, Messager messager,
			Element reportOn) {
		if (type.getKind() != TypeKind.DECLARED) {
			return error(messager, "query result type '" + type + "' is neither the repository entity nor a VO "
					+ "(a VO must be a public record or a public class with setters)", reportOn);
		}
		DeclaredType declared = (DeclaredType) type;
		TypeElement element = (TypeElement) declared.asElement();
		String qualifiedName = element.getQualifiedName().toString();
		if (!declared.getTypeArguments().isEmpty()) {
			return error(messager, "query result VO '" + qualifiedName
					+ "' must not be generic - the generated mapper cannot know the type arguments", reportOn);
		}
		if (!element.getModifiers().contains(Modifier.PUBLIC)) {
			return error(messager, "query result VO '" + qualifiedName + "' must be public: the generated"
					+ " repository implementation has to instantiate it", reportOn);
		}
		if (element.getKind() == ElementKind.RECORD) {
			return recordVo(element, types, messager, reportOn);
		}
		if (element.getKind() == ElementKind.CLASS) {
			return classVo(element, elements, types, messager, reportOn);
		}
		return error(messager, "query result VO '" + qualifiedName + "' must be a record or a class, but is a "
				+ element.getKind().toString().toLowerCase() + " - interface-based projections would need runtime"
				+ " proxies, which this zero-reflection mapper does not support", reportOn);
	}

	/** record：每个组件一个成员，按声明顺序交给规范构造器。 */
	private static VoMetadata recordVo(TypeElement element, Types types, Messager messager, Element reportOn) {
		List<VoMetadata.Member> members = new ArrayList<>();
		for (Element enclosed : element.getEnclosedElements()) {
			if (enclosed.getKind() != ElementKind.RECORD_COMPONENT) {
				continue;
			}
			RecordComponentElement component = (RecordComponentElement) enclosed;
			String name = component.getSimpleName().toString();
			String override = columnName(component);
			if (override == null) {
				override = columnName(field(element, name));
			}
			VoMetadata.Member member = member(name, component.asType(), null, override, types,
					element.getQualifiedName() + "#" + name, messager, reportOn);
			if (member == null) {
				return null;
			}
			members.add(member);
		}
		if (members.isEmpty()) {
			return error(messager, "query result VO '" + element.getQualifiedName()
					+ "' has no record components to map", reportOn);
		}
		return new VoMetadata(element.getQualifiedName().toString(), true, List.copyOf(members));
	}

	/** 普通类：公共 setter 决定成员，无参构造 + setter 写入。 */
	private static VoMetadata classVo(TypeElement element, Elements elements, Types types, Messager messager,
			Element reportOn) {
		boolean declaresConstructor = false;
		boolean accessibleNoArg = false;
		for (Element enclosed : element.getEnclosedElements()) {
			if (enclosed.getKind() != ElementKind.CONSTRUCTOR) {
				continue;
			}
			declaresConstructor = true;
			ExecutableElement constructor = (ExecutableElement) enclosed;
			if (constructor.getParameters().isEmpty()
					&& constructor.getModifiers().contains(Modifier.PUBLIC)) {
				accessibleNoArg = true;
			}
		}
		if (declaresConstructor && !accessibleNoArg) {
			return error(messager, "query result VO '" + element.getQualifiedName()
					+ "' declares constructors but none is a public no-arg one - the generated mapper writes"
					+ " fields through setters and needs 'new " + element.getSimpleName() + "()'", reportOn);
		}
		List<VoMetadata.Member> members = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Element candidate : elements.getAllMembers(element)) {
			if (candidate.getKind() != ElementKind.METHOD || !(candidate instanceof ExecutableElement method)) {
				continue;
			}
			String methodName = method.getSimpleName().toString();
			if (!methodName.startsWith("set") || methodName.length() == "set".length()
					|| method.getParameters().size() != 1
					|| !method.getModifiers().contains(Modifier.PUBLIC)
					|| method.getModifiers().contains(Modifier.STATIC)) {
				continue;
			}
			String property = decapitalize(methodName.substring("set".length()));
			if (!seen.add(property)) {
				continue;
			}
			VariableElement parameter = method.getParameters().get(0);
			VoMetadata.Member member = member(property, parameter.asType(), methodName,
					columnName(field(element, property)), types,
					element.getQualifiedName() + "#" + property, messager, reportOn);
			if (member == null) {
				return null;
			}
			members.add(member);
		}
		if (members.isEmpty()) {
			return error(messager, "query result VO '" + element.getQualifiedName() + "' has no public setters to"
					+ " map; use a record or expose setXxx methods", reportOn);
		}
		return new VoMetadata(element.getQualifiedName().toString(), false, List.copyOf(members));
	}

	/**
	 * 单个成员的校验与登记。
	 *
	 * @param setter 全参构造形态传 {@code null}
	 */
	private static VoMetadata.Member member(String name, TypeMirror type, String setter, String columnOverride,
			Types types, String location, Messager messager, Element reportOn) {
		String typeSource = EntityIntrospector.typeSignature(type, types);
		boolean enumeration = isEnum(type);
		if (!enumeration && !EntityIntrospector.isScalarType(typeSource)) {
			messager.printMessage(Diagnostic.Kind.ERROR, "VO member '" + location + "' has unsupported type "
					+ typeSource + " - only JDBC scalar types and enums can be mapped", reportOn);
			return null;
		}
		if (typeSource.endsWith("[]") && !typeSource.equals("byte[]")) {
			messager.printMessage(Diagnostic.Kind.ERROR, "VO member '" + location + "' is " + typeSource
					+ " - only byte[] arrays are mapped from a result set", reportOn);
			return null;
		}
		if (typeSource.equals("void")) {
			messager.printMessage(Diagnostic.Kind.ERROR, "VO member '" + location + "' cannot be void", reportOn);
			return null;
		}
		return new VoMetadata.Member(name, typeSource, setter, columnOverride, enumeration);
	}

	/** {@code @Column(name)}：未写或为空白串时返回 {@code null}，表示按名字推导。 */
	private static String columnName(Element element) {
		if (element == null) {
			return null;
		}
		String name = EntityIntrospector.annotationString(element, COLUMN, "name");
		return (name == null || name.isBlank()) ? null : name;
	}

	/** 在类层次里找同名字段（父类字段也算）：{@code @Column} 通常标在字段上。 */
	private static Element field(TypeElement type, String name) {
		TypeElement current = type;
		while (current != null) {
			for (Element enclosed : current.getEnclosedElements()) {
				if (enclosed.getKind() == ElementKind.FIELD
						&& enclosed.getSimpleName().contentEquals(name)) {
					return enclosed;
				}
			}
			TypeMirror superclass = current.getSuperclass();
			if (superclass.getKind() != TypeKind.DECLARED) {
				return null;
			}
			TypeElement parent = (TypeElement) ((DeclaredType) superclass).asElement();
			current = parent.getQualifiedName().contentEquals("java.lang.Object") ? null : parent;
		}
		return null;
	}

	private static boolean isEnum(TypeMirror type) {
		return type.getKind() == TypeKind.DECLARED
				&& ((TypeElement) ((DeclaredType) type).asElement()).getKind() == ElementKind.ENUM;
	}

	/** JavaBeans 的驼峰还原：{@code setURL} → {@code URL}，{@code setOrderNo} → {@code orderNo}。 */
	private static String decapitalize(String name) {
		if (name.isEmpty()) {
			return name;
		}
		if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
			return name;
		}
		return Character.toLowerCase(name.charAt(0)) + name.substring(1);
	}

	private static VoMetadata error(Messager messager, String message, Element reportOn) {
		messager.printMessage(Diagnostic.Kind.ERROR, message, reportOn);
		return null;
	}
}
