package xyz.wewin.autumn.data.jdbc.compiler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Association;
import xyz.wewin.autumn.data.jdbc.compiler.EntityMetadata.Column;

/**
 * 实体元数据提取（纯编译期，基于 {@link Elements} / {@link Types}，不做文件 IO 与反射）。
 *
 * <p>约定（违反即在 APT 阶段报错，不留到运行期）：</p>
 * <ul>
 *   <li>实体需标 {@code @Entity} 或 {@code @Table}；表名取 {@code @Table(name)}，否则类名转下划线；</li>
 *   <li>必须有且仅有一个 {@code @Id} 字段；{@code @GeneratedValue} 视为数据库自增主键；</li>
 *   <li>列名取 {@code @Column(name)}，否则字段名转下划线；{@code static} / {@code transient} /
 *       {@code @Transient} 字段忽略；</li>
 *   <li>每个列字段需要 JavaBean 读写方法，以及无参构造器（record 暂不支持）；</li>
 *   <li>单表标量列之外的关联字段按注解识别：{@code @OneToMany} 集合按「两条语句 + 按外键分组」装配
 *       （深度一层，{@code fetch = LAZY} 表示不自动装配）；其余关联注解只给 WARNING，不当列处理。</li>
 * </ul>
 */
final class EntityIntrospector {

	private static final String JPA = "jakarta.persistence.";

	private static final String ENTITY = JPA + "Entity";

	private static final String TABLE = JPA + "Table";

	private static final String COLUMN = JPA + "Column";

	private static final String ID = JPA + "Id";

	private static final String GENERATED_VALUE = JPA + "GeneratedValue";

	private static final String TRANSIENT = JPA + "Transient";

	private static final String ONE_TO_MANY = JPA + "OneToMany";

	private static final String MANY_TO_ONE = JPA + "ManyToOne";

	private static final String JOIN_COLUMN = JPA + "JoinColumn";

	/** 关联最多向下收集的层级：根(0) 收集关联、子(1) 收集关联、孙(2) 只取表名与列清单。避免无限递归。 */
	private static final int MAX_ASSOCIATION_DEPTH = 2;

	private static final Set<String> ASSOCIATION_ANNOTATIONS = Set.of(ONE_TO_MANY, MANY_TO_ONE,
			JPA + "ManyToMany", JPA + "OneToOne");

	private static final Set<String> SUPPORTED_COLLECTIONS = Set.of("java.util.List", "java.util.Set",
			"java.util.Collection");

	private EntityIntrospector() {
	}

	/** 提取失败时输出 ERROR 诊断并返回 {@code null}，由调用方跳过生成。 */
	static EntityMetadata introspect(TypeMirror entityType, Elements elements, Types types, Messager messager,
			Element reportOn) {
		return introspect(entityType, elements, types, messager, reportOn, 0, new HashSet<>());
	}

	/**
	 * @param depth 关联深度：根实体（0）收集 {@code @OneToMany}/{@code @ManyToOne}，子实体（1）同样收集关联
	 *            （支持两层以上关联路径），孙实体（≥{@link #MAX_ASSOCIATION_DEPTH}）只取表名与列清单，
	 *            {@code visiting} 记录已展开的实体，避免父子互访引起的无限递归
	 */
	private static EntityMetadata introspect(TypeMirror entityType, Elements elements, Types types, Messager messager,
			Element reportOn, int depth, Set<String> visiting) {
		if (entityType.getKind() != TypeKind.DECLARED) {
			messager.printMessage(Diagnostic.Kind.ERROR,
					"Entity type must be a class, but is " + entityType, reportOn);
			return null;
		}
		TypeElement entityElement = (TypeElement) ((DeclaredType) types.erasure(entityType)).asElement();
		if (entityElement.getKind() == ElementKind.RECORD) {
			messager.printMessage(Diagnostic.Kind.ERROR, "record entity " + entityElement.getQualifiedName()
					+ " is not supported yet (needs a no-arg constructor and setters)", reportOn);
			return null;
		}
		if (!hasAnnotation(entityElement, ENTITY) && !hasAnnotation(entityElement, TABLE)) {
			messager.printMessage(Diagnostic.Kind.ERROR, "Entity " + entityElement.getQualifiedName()
					+ " must be annotated with @Entity or @Table", reportOn);
			return null;
		}
		if (!hasNoArgConstructor(entityElement)) {
			messager.printMessage(Diagnostic.Kind.ERROR, "Entity " + entityElement.getQualifiedName()
					+ " needs an accessible no-arg constructor", reportOn);
			return null;
		}

		String table = namedValue(entityElement, TABLE, snakeCase(entitySimpleName(entityElement)));
		Set<String> methods = memberNames(elements.getAllMembers(entityElement));
		List<Column> columns = new ArrayList<>();
		List<Association> associations = new ArrayList<>();
		// 多对一需要读父表上的外键列，但该列由同一循环里的标量字段收集，故先记下、循环结束后再解析 getter
		List<String> pendingManyToOne = new ArrayList<>();
		Column id = null;
		for (TypeElement current = entityElement; current != null; current = superclass(current, types)) {
			for (Element field : current.getEnclosedElements()) {
				if (field.getKind() != ElementKind.FIELD || isSkipped(field)) {
					continue;
				}
				String fieldName = field.getSimpleName().toString();
				String javaType = typeSignature(field.asType(), types);
				if (hasAnyAssociationAnnotation(field)) {
					if (depth >= MAX_ASSOCIATION_DEPTH) {
						// 关联实体只取表名与列清单，不再展开其关联，避免无限递归
						continue;
					}
					if (hasAnnotation(field, ONE_TO_MANY)) {
						Association association = oneToMany(field, entityElement, elements, types, messager, reportOn,
								methods, depth, visiting);
						if (association != null) {
							associations.add(association);
						}
					}
					else if (hasAnnotation(field, MANY_TO_ONE)) {
						// 外键列名先记下，标量字段收集完毕后才能定位对应 getter
						pendingManyToOne.add(fieldName);
					}
					else {
						warn(messager, "Association field " + entityElement.getQualifiedName() + "." + fieldName
								+ " uses an association annotation that autumn does not generate yet"
								+ " (only @OneToMany and @ManyToOne are supported this round)", reportOn);
					}
					continue;
				}
				if (!isScalarType(javaType)) {
					messager.printMessage(Diagnostic.Kind.ERROR, "Field " + current.getQualifiedName() + "."
							+ fieldName + " of type " + javaType + " is not a supported column type"
							+ " (add a JPA association annotation, or map it as a scalar column)", reportOn);
					return null;
				}
				String getter = firstPresent(methods, "get" + capitalize(fieldName), "is" + capitalize(fieldName));
				String setter = "set" + capitalize(fieldName);
				if (getter == null || !methods.contains(setter)) {
					messager.printMessage(Diagnostic.Kind.ERROR, "Entity " + entityElement.getQualifiedName()
							+ " needs JavaBean accessor " + getter + "/" + setter + " for field '" + fieldName + "'",
							reportOn);
					return null;
				}
				boolean isId = hasAnnotation(field, ID);
				Column column = new Column(fieldName, namedValue(field, COLUMN, snakeCase(fieldName)), javaType,
						getter, setter, isId, hasAnnotation(field, GENERATED_VALUE));
				if (isId) {
					if (id != null) {
						messager.printMessage(Diagnostic.Kind.ERROR, "Entity " + entityElement.getQualifiedName()
								+ " declares more than one @Id field: " + id.field() + " and " + fieldName, reportOn);
						return null;
					}
					id = column;
				}
				columns.add(column);
			}
		}
		if (id == null) {
			messager.printMessage(Diagnostic.Kind.ERROR, "Entity " + entityElement.getQualifiedName()
					+ " has no @Id field", reportOn);
			return null;
		}
		// 多对一：父表上必须有指向子表主键的外键列字段，否则无法用两条语句取回子实体
		for (String fieldName : pendingManyToOne) {
			Association association = manyToOne(fieldName, entityElement, elements, types, messager, reportOn, methods,
					columns, depth, visiting);
			if (association != null) {
				associations.add(association);
			}
		}
		return new EntityMetadata(entityElement.getQualifiedName().toString(), table, id, List.copyOf(columns),
				List.copyOf(associations));
	}

	/**
	 * 父实体上的 {@code @OneToMany} 集合字段 → 关联元数据；不满足约定时给 WARNING 并返回 {@code null}
	 * （即该字段既不当事务列，也不生成装配代码）。
	 */
	private static Association oneToMany(Element field, TypeElement parent, Elements elements, Types types,
			Messager messager, Element reportOn, Set<String> methods, int depth, Set<String> visiting) {
		String fieldName = field.getSimpleName().toString();
		TypeMirror collectionType = field.asType();
		if (collectionType.getKind() != TypeKind.DECLARED || ((DeclaredType) collectionType).getTypeArguments()
			.size() != 1) {
			warn(messager, "@OneToMany field " + parent.getQualifiedName() + "." + fieldName
					+ " must be a parameterised java.util.List/Set/Collection of the child entity", reportOn);
			return null;
		}
		String collection = typeSignature(collectionType, types);
		if (!SUPPORTED_COLLECTIONS.contains(collection)) {
			warn(messager, "@OneToMany field " + parent.getQualifiedName() + "." + fieldName + " has unsupported"
					+ " collection type " + collection + " (use List, Set or Collection)", reportOn);
			return null;
		}
		TypeMirror elementType = ((DeclaredType) collectionType).getTypeArguments().get(0);
		if (elementType.getKind() != TypeKind.DECLARED) {
			warn(messager, "@OneToMany field " + parent.getQualifiedName() + "." + fieldName
					+ " must have an entity type argument", reportOn);
			return null;
		}
		String getter = firstPresent(methods, "get" + capitalize(fieldName), "is" + capitalize(fieldName));
		String setter = "set" + capitalize(fieldName);
		if (getter == null || !methods.contains(setter)) {
			messager.printMessage(Diagnostic.Kind.ERROR, "Entity " + parent.getQualifiedName() + " needs JavaBean"
					+ " accessor " + getter + "/" + setter + " for @OneToMany field '" + fieldName + "'", reportOn);
			return null;
		}
		EntityMetadata target = introspectChild(elementType, elements, types, messager, reportOn, depth, visiting,
				parent.getQualifiedName().toString());
		if (target == null) {
			return null;
		}
		String element = ((TypeElement) ((DeclaredType) types.erasure(elementType)).asElement()).getQualifiedName()
			.toString();
		return new Association(fieldName, getter, setter, collection, element,
				foreignKeyColumn(field, parent, elementType, types, elements), target,
				!"LAZY".equals(enumValue(field, ONE_TO_MANY, "fetch")), false, null);
	}

	/**
	 * 父实体上的 {@code @ManyToOne} 单值字段 → 关联元数据：两条语句里用父表外键 {@code IN} 子表主键取回子实体。
	 * 外键列必须由父实体的一个标量字段提供（与一对多里「子表暴露外键列」对称），否则编译期报错。
	 */
	private static Association manyToOne(String fieldName, TypeElement parent, Elements elements, Types types,
			Messager messager, Element reportOn, Set<String> methods, List<Column> columns, int depth,
			Set<String> visiting) {
		Element field = findField(elements.getAllMembers(parent), fieldName);
		if (field == null) {
			warn(messager, "@ManyToOne field " + parent.getQualifiedName() + "." + fieldName
					+ " could not be resolved on the entity", reportOn);
			return null;
		}
		TypeMirror elementType = field.asType();
		if (elementType.getKind() != TypeKind.DECLARED) {
			warn(messager, "@ManyToOne field " + parent.getQualifiedName() + "." + fieldName
					+ " must reference an entity type", reportOn);
			return null;
		}
		String getter = firstPresent(methods, "get" + capitalize(fieldName), "is" + capitalize(fieldName));
		String setter = "set" + capitalize(fieldName);
		if (getter == null || !methods.contains(setter)) {
			messager.printMessage(Diagnostic.Kind.ERROR, "Entity " + parent.getQualifiedName() + " needs JavaBean"
					+ " accessor " + getter + "/" + setter + " for @ManyToOne field '" + fieldName + "'", reportOn);
			return null;
		}
		// 父表上的外键列：@JoinColumn(name) 优先，否则按字段名转下划线加 _id
		String foreignKeyColumn = annotationString(field, JOIN_COLUMN, "name");
		if (!isPresent(foreignKeyColumn)) {
			foreignKeyColumn = snakeCase(fieldName) + "_id";
		}
		// 外键 getter 只在根实体（depth 0）上需要：只有根实体的多对一才会生成第二条装配语句；
		// 子实体的多对一只用于派生查询的关联路径（相关子查询），不生成装配，故不强求外键标量字段
		String foreignKeyGetter = null;
		if (depth == 0) {
			Column fkColumn = null;
			for (Column column : columns) {
				if (column.column().equals(foreignKeyColumn)) {
					fkColumn = column;
					break;
				}
			}
			if (fkColumn == null) {
				messager.printMessage(Diagnostic.Kind.ERROR, "Entity " + parent.getQualifiedName() + " needs a scalar"
						+ " @Column field for the @ManyToOne foreign key '" + foreignKeyColumn + "' (e.g. a '" + fieldName
						+ "Id' field) so the second statement can read the key", reportOn);
				return null;
			}
			foreignKeyGetter = fkColumn.getter();
		}
		EntityMetadata target = introspectChild(elementType, elements, types, messager, reportOn, depth, visiting,
				parent.getQualifiedName().toString());
		if (target == null) {
			return null;
		}
		String element = ((TypeElement) ((DeclaredType) types.erasure(elementType)).asElement()).getQualifiedName()
			.toString();
		return new Association(fieldName, getter, setter, null, element, foreignKeyColumn, target,
				!"LAZY".equals(enumValue(field, MANY_TO_ONE, "fetch")), true, foreignKeyGetter);
	}

	/** 递归提取关联目标实体；防止父子互访无限递归：已展开过的实体只取表名与列清单。 */
	private static EntityMetadata introspectChild(TypeMirror elementType, Elements elements, Types types,
			Messager messager, Element reportOn, int depth, Set<String> visiting, String parentQualifiedName) {
		String element = ((TypeElement) ((DeclaredType) types.erasure(elementType)).asElement()).getQualifiedName()
			.toString();
		Set<String> childVisiting = new HashSet<>(visiting);
		childVisiting.add(parentQualifiedName);
		if (childVisiting.contains(element)) {
			// 形成环（A→B→A）：只取表名与列清单，不再收集其关联
			return introspect(elementType, elements, types, messager, reportOn, MAX_ASSOCIATION_DEPTH, childVisiting);
		}
		return introspect(elementType, elements, types, messager, reportOn, depth + 1, childVisiting);
	}

	/**
	 * 外键列（子表上指回父表主键的列）：{@code @JoinColumn(name)} 优先；
	 * 写了 {@code mappedBy} 时读子实体那一侧的 {@code @JoinColumn}；都没有则按父实体类名转下划线加 {@code _id}。
	 */
	private static String foreignKeyColumn(Element field, TypeElement parent, TypeMirror elementType, Types types,
			Elements elements) {
		String declared = annotationString(field, JOIN_COLUMN, "name");
		if (isPresent(declared)) {
			return declared;
		}
		String mappedBy = annotationString(field, ONE_TO_MANY, "mappedBy");
		if (isPresent(mappedBy)) {
			TypeElement child = (TypeElement) ((DeclaredType) types.erasure(elementType)).asElement();
			Element back = findField(elements.getAllMembers(child), mappedBy);
			if (back != null) {
				String onChild = annotationString(back, JOIN_COLUMN, "name");
				if (isPresent(onChild)) {
					return onChild;
				}
			}
		}
		return snakeCase(entitySimpleName(parent)) + "_id";
	}

	private static Element findField(Iterable<? extends Element> members, String name) {
		for (Element member : members) {
			if (member.getKind() == ElementKind.FIELD && member.getSimpleName().contentEquals(name)) {
				return member;
			}
		}
		return null;
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}

	private static boolean hasAnyAssociationAnnotation(Element field) {
		for (AnnotationMirror mirror : field.getAnnotationMirrors()) {
			if (ASSOCIATION_ANNOTATIONS.contains(annotationName(mirror))) {
				return true;
			}
		}
		return false;
	}

	private static void warn(Messager messager, String message, Element reportOn) {
		messager.printMessage(Diagnostic.Kind.WARNING, message, reportOn);
	}

	private static boolean isSkipped(Element field) {
		Set<Modifier> modifiers = field.getModifiers();
		return modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.TRANSIENT)
				|| hasAnnotation(field, TRANSIENT);
	}

	/** 有显式构造器时必须存在无参那个；未声明构造器则隐含的默认构造器可用。 */
	private static boolean hasNoArgConstructor(TypeElement entityElement) {
		boolean declaresAny = false;
		for (Element enclosed : entityElement.getEnclosedElements()) {
			if (enclosed.getKind() != ElementKind.CONSTRUCTOR) {
				continue;
			}
			declaresAny = true;
			if (((ExecutableElement) enclosed).getParameters().isEmpty()) {
				return true;
			}
		}
		return !declaresAny;
	}

	private static TypeElement superclass(TypeElement element, Types types) {
		TypeMirror supertype = element.getSuperclass();
		if (supertype.getKind() != TypeKind.DECLARED) {
			return null;
		}
		TypeElement typeElement = (TypeElement) ((DeclaredType) supertype).asElement();
		return typeElement.getQualifiedName().contentEquals("java.lang.Object") ? null : typeElement;
	}

	private static String entitySimpleName(TypeElement element) {
		String qualified = element.getQualifiedName().toString();
		return qualified.substring(qualified.lastIndexOf('.') + 1);
	}

	/** {@code @Table(name)} / {@code @Column(name)}：未写属性时用默认命名策略。 */
	private static String namedValue(Element element, String annotationFqn, String defaultValue) {
		String declared = annotationString(element, annotationFqn, "name");
		return (declared == null || declared.isBlank()) ? defaultValue : declared;
	}

	private static String annotationString(Element element, String annotationFqn, String attribute) {
		for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
			if (!annotationName(mirror).contentEquals(annotationFqn)) {
				continue;
			}
			for (var entry : mirror.getElementValues().entrySet()) {
				if (entry.getKey().getSimpleName().contentEquals(attribute)) {
					AnnotationValue value = entry.getValue();
					return (value == null) ? null : String.valueOf(value.getValue());
				}
			}
			return null;
		}
		return null;
	}

	/** 注解里的枚举属性（如 {@code fetch = FetchType.LAZY}）取常量名；未写或读不到返回 {@code null}。 */
	private static String enumValue(Element element, String annotationFqn, String attribute) {
		for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
			if (!annotationName(mirror).contentEquals(annotationFqn)) {
				continue;
			}
			for (var entry : mirror.getElementValues().entrySet()) {
				if (entry.getKey().getSimpleName().contentEquals(attribute)) {
					Object raw = entry.getValue().getValue();
					if (raw instanceof VariableElement constant) {
						return constant.getSimpleName().toString();
					}
					String text = String.valueOf(raw);
					return text.substring(text.lastIndexOf('.') + 1);
				}
			}
			return null;
		}
		return null;
	}

	private static boolean hasAnnotation(Element element, String annotationFqn) {
		for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
			if (annotationName(mirror).contentEquals(annotationFqn)) {
				return true;
			}
		}
		return false;
	}

	private static String annotationName(AnnotationMirror mirror) {
		return ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString();
	}

	/** 擦除泛型后的可书写类型名：基本类型保留 {@code int}，数组保留 {@code byte[]}。 */
	private static String typeSignature(TypeMirror type, Types types) {
		if (type.getKind() == TypeKind.DECLARED) {
			return ((TypeElement) ((DeclaredType) types.erasure(type)).asElement()).getQualifiedName().toString();
		}
		return type.toString();
	}

	private static boolean isScalarType(String javaType) {
		return !javaType.contains(".") || javaType.endsWith("[]") || javaType.startsWith("java.lang.")
				|| javaType.startsWith("java.time.") || javaType.startsWith("java.math.")
				|| javaType.startsWith("java.sql.") || javaType.equals("java.util.Date")
				|| javaType.equals("java.util.UUID");
	}

	private static Set<String> memberNames(Iterable<? extends Element> members) {
		Set<String> names = new HashSet<>();
		for (Element member : members) {
			if (member.getKind() == ElementKind.METHOD) {
				names.add(member.getSimpleName().toString());
			}
		}
		return names;
	}

	private static String firstPresent(Set<String> candidates, String... names) {
		for (String name : names) {
			if (candidates.contains(name)) {
				return name;
			}
		}
		return null;
	}

	private static String capitalize(String name) {
		return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	/** 驼峰转下划线：{@code createdAt} → {@code created_at}，{@code HTTPUrl} → {@code http_url}。 */
	static String snakeCase(String name) {
		StringBuilder sb = new StringBuilder(name.length() + 4);
		for (int i = 0; i < name.length(); i++) {
			char current = name.charAt(i);
			if (Character.isUpperCase(current) && i > 0 && isWordBoundary(name, i)) {
				sb.append('_');
			}
			sb.append(Character.toLowerCase(current));
		}
		return sb.toString();
	}

	private static boolean isWordBoundary(String name, int index) {
		char previous = name.charAt(index - 1);
		if (Character.isLowerCase(previous) || Character.isDigit(previous)) {
			return true;
		}
		return index + 1 < name.length() && Character.isLowerCase(name.charAt(index + 1));
	}
}
