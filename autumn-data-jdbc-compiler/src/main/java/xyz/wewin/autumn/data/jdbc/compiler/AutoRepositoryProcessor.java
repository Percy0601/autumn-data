package xyz.wewin.autumn.data.jdbc.compiler;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

import java.util.Locale;

import xyz.wewin.autumn.data.common.dialect.DefaultDialect;
import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.common.dialect.MySqlDialect;
import xyz.wewin.autumn.data.common.dialect.PostgreSqlDialect;

/**
 * JSR-269 注解处理器：为 {@code @AutumnRepository} 接口生成 {@code <名称>Impl} 实现。
 *
 * <p>流程：校验约定（必须是接口、必须继承 {@code Repository<T, ID>}）→ 提取实体元数据
 * （{@link EntityIntrospector}，含一对多关联）→ 生成标准 CRUD / 分页 / 子表加载的 SQL
 * （{@link CrudSqlGenerator}，返回 {@code Map<key, sql>}）→ 生成实现类源码
 * （{@link ImplementationSource}，SQL 在编译期内联为生成类的 {@code private static final String} 常量，
 * 运行期零资源 I/O）。约定违规一律在编译期报错，不生成半成品。</p>
 *
 * <p>覆盖范围：{@code CrudRepository} 全部方法、{@code PagingRepository#findAll(Pageable)}、
 * 批量读写、一对多装配，以及第 8.3 节约定的派生查询（{@code findByNameAndAge} 之类，
 * 由 {@link DerivedQueryParser} 在编译期解析）。接口上任何生成器不实现的抽象方法都必须解析成功，
 * 否则在方法声明位置报错 —— 不静默跳过，避免把「少实现一个方法」的锅甩给生成类的编译错误。</p>
 */
@SupportedAnnotationTypes("xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository")
// 可选的编译期方言：只用来决定生成的 SQL 里的标识符是否加引号（保留字安全），运行期方言仍由 DialectResolver 按 DataSource 判定
@SupportedOptions(AutoRepositoryProcessor.DIALECT_OPTION)
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class AutoRepositoryProcessor extends AbstractProcessor {

	static final String REPOSITORY_FQN = "xyz.wewin.autumn.data.jdbc.repository.Repository";

	/** {@code -Aautumn.data.jdbc.dialect=<mysql|postgresql|h2|default>}：开启编译期标识符引号。 */
	static final String DIALECT_OPTION = "autumn.data.jdbc.dialect";

	/** {@code @Query} 注解，方法上写了它就用其 SQL 而非方法名派生（对标 Spring Data）。 */
	private static final String QUERY_FQN = "xyz.wewin.autumn.data.jdbc.annotation.Query";

	/** 仓储清单资源的路径，与 starter 的 {@code RepositoryRegistrar} 读的是同一个。 */
	private static final String REGISTRY_PATH = "META-INF/autumn-data/repositories";

	private static final String PAGEABLE_FQN = "xyz.wewin.autumn.data.jdbc.page.Pageable";

	private static final String COLLECTION_FQN = "java.util.Collection";

	/**
	 * 生成器按约定直接实现的标准方法（{@code 方法名/参数个数}）。这里面出现的名字一律不当派生查询解析；
	 * {@code findAll/1} 额外要求实参是 {@code Pageable}，否则视为用户自己的声明、必须能解析成查询。
	 */
	private static final Set<String> CONVENTION_SIGNATURES = Set.of("save/1", "saveAll/1", "findById/1",
			"existsById/1", "findAll/0", "findAllById/1", "count/0", "deleteById/1", "delete/1", "deleteAllById/1",
			"deleteAll/1", "deleteAll/0");

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		if (roundEnv.processingOver() || annotations.isEmpty()) {
			return false;
		}
		Elements elements = processingEnv.getElementUtils();
		Types types = processingEnv.getTypeUtils();
		TypeElement repositoryType = elements.getTypeElement(REPOSITORY_FQN);
		if (repositoryType == null) {
			error("autumn-data-jdbc must be on the compile classpath to process @AutumnRepository", null);
			return false;
		}
		List<String> repositories = new ArrayList<>();
		for (Element element : roundEnv.getElementsAnnotatedWith(annotations.iterator().next())) {
			if (element.getKind() != ElementKind.INTERFACE) {
				error("@AutumnRepository can only be applied to interfaces, but "
						+ ((TypeElement) element).getQualifiedName() + " is a "
						+ element.getKind().toString().toLowerCase(), element);
				continue;
			}
			TypeElement repositoryInterface = (TypeElement) element;
			DeclaredType binding = findRepositoryBinding(repositoryInterface, repositoryType, types);
			if (binding == null) {
				error("@AutumnRepository interface " + repositoryInterface.getQualifiedName()
						+ " must extend Repository<T, ID>", repositoryInterface);
				continue;
			}
			String entity = sourceName(binding.getTypeArguments().get(0), types);
			String id = sourceName(binding.getTypeArguments().get(1), types);
			EntityMetadata metadata = EntityIntrospector.introspect(binding.getTypeArguments().get(0), elements,
					types, processingEnv.getMessager(), repositoryInterface);
			if (metadata == null) {
				continue;
			}
			String packageName = elements.getPackageOf(repositoryInterface).getQualifiedName().toString();
			// SQL 生成吃「引号视图」，生成实现类的代码仍吃原元数据（结果集标签不受引号影响）
			Dialect dialect = compileTimeDialect(repositoryInterface);
			EntityMetadata sqlMetadata = EntityMetadata.quoted(metadata, dialect);
			List<DerivedQuery> queries = collectDerivedQueries(repositoryInterface, sqlMetadata, elements, types);
			if (queries == null) {
				// 已经有方法解析失败并在声明处报错，不生成半成品实现
				continue;
			}
			try {
				Map<String, String> statements = CrudSqlGenerator.generate(sqlMetadata);
				writeImplementation(repositoryInterface, packageName, entity, id, metadata, statements, queries,
						declaresPaging(repositoryInterface, elements, types), processingEnv.getFiler());
				repositories.add(repositoryInterface.getQualifiedName().toString());
			}
			catch (IOException ex) {
				error("Failed to generate implementation for " + repositoryInterface.getQualifiedName() + ": " + ex,
						repositoryInterface);
			}
		}
		if (!repositories.isEmpty()) {
			try {
				writeRepositoryRegistry(repositories, processingEnv.getFiler());
			}
			catch (IOException ignored) {
				// 同一编译单元重复写注册表时忽略（APT 不允许重复 create 同名资源）
			}
		}
		return false;
	}

	/**
	 * 收集接口上需要派生查询解析的抽象方法。
	 *
	 * <p>判定顺序：先跳过 {@code default}/{@code static}（本来就不需要实现），再跳过
	 * {@link #CONVENTION_SIGNATURES} 里的标准方法（生成器已经实现），剩下的每个方法都必须解析成功。
	 * 任一解析失败就在该方法声明位置报错并返回 {@code null}，让调用方放弃生成 —— 这正是
	 * docs 第 8.3 条「不允许静默生成」的落点：过去这里对未知方法什么都不做，
	 * 结果由 javac 在生成类里报「不是抽象的，并且未覆盖 …」，把框架的能力边界伪装成用户的代码错误。</p>
	 *
	 * @return 解析成功的查询列表；返回 {@code null} 表示已有方法报错、不应继续生成
	 */
	private List<DerivedQuery> collectDerivedQueries(TypeElement repositoryInterface, EntityMetadata metadata,
			Elements elements, Types types) {
		TypeElement collectionType = elements.getTypeElement(COLLECTION_FQN);
		List<DerivedQuery> queries = new ArrayList<>();
		Set<String> visited = new LinkedHashSet<>();
		boolean failed = false;
		for (Element member : elements.getAllMembers(repositoryInterface)) {
			if (member.getKind() != ElementKind.METHOD || !(member instanceof ExecutableElement method)) {
				continue;
			}
			if (declaredOnObject(method)) {
				// getAllMembers 会把 java.lang.Object 的 equals/hashCode/toString… 一起列进来，它们不是接口契约
				continue;
			}
			if (method.isDefault() || method.getModifiers().contains(Modifier.STATIC)) {
				continue;
			}
			if (isImplementedByConvention(method, types)) {
				continue;
			}
			if (!visited.add(signatureOf(method, types))) {
				continue;
			}
			String returnType = sourceName(method.getReturnType(), types);
			List<DerivedQuery.Parameter> parameters = new ArrayList<>();
			for (VariableElement parameter : method.getParameters()) {
				TypeMirror parameterType = parameter.asType();
				boolean collection = collectionType != null && types.isAssignable(types.erasure(parameterType),
						collectionType.asType());
				parameters.add(new DerivedQuery.Parameter(sourceName(parameterType, types),
						parameter.getSimpleName().toString(), collection));
			}
			String querySql = queryAnnotationValue(method);
			if (querySql != null) {
				// 方法上写了 @Query：直接采用其 SQL，不再解析方法名
				try {
					queries.add(DerivedQueryParser.custom(member.getSimpleName().toString(), querySql, returnType,
							parameters));
				}
				catch (DerivedQueryException ex) {
					error(ex.getMessage(), method);
					failed = true;
				}
				continue;
			}
			try {
				queries.add(DerivedQueryParser.parse(member.getSimpleName().toString(), returnType, parameters,
						metadata));
			}
			catch (DerivedQueryException ex) {
				error(ex.getMessage(), method);
				failed = true;
			}
		}
		return failed ? null : queries;
	}

	/** 方法是否来自 {@code java.lang.Object}（接口的成员清单会把它们一起列出来）。 */
	private static boolean declaredOnObject(ExecutableElement method) {
		return method.getEnclosingElement() instanceof TypeElement owner
				&& owner.getQualifiedName().contentEquals("java.lang.Object");
	}

	/** 读取方法上的 {@code @Query#value()}；没有该注解返回 {@code null}。 */
	private static String queryAnnotationValue(ExecutableElement method) {
		for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
			if (!((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(QUERY_FQN)) {
				continue;
			}
			for (var entry : mirror.getElementValues().entrySet()) {
				if (entry.getKey().getSimpleName().contentEquals("value")) {
					return String.valueOf(entry.getValue().getValue());
				}
			}
		}
		return null;
	}

	/** 方法名是否属于生成器按约定直接实现的标准方法。 */
	private static boolean isImplementedByConvention(ExecutableElement method, Types types) {
		String key = method.getSimpleName() + "/" + method.getParameters().size();
		if (!"findAll/1".equals(key)) {
			return CONVENTION_SIGNATURES.contains(key);
		}
		return types.erasure(method.getParameters().get(0).asType()).toString().contentEquals(PAGEABLE_FQN);
	}

	/** 用于跨继承链去重：同一个签名被多个父接口声明时只生成一次。 */
	private static String signatureOf(ExecutableElement method, Types types) {
		StringBuilder sb = new StringBuilder(method.getSimpleName()).append('/');
		for (VariableElement parameter : method.getParameters()) {
			sb.append(types.erasure(parameter.asType())).append(',');
		}
		return sb.toString();
	}

	/** 接口（含继承的 {@code PagingRepository}）里是否声明了 {@code findAll(Pageable)}。 */
	private static boolean declaresPaging(TypeElement repositoryInterface, Elements elements, Types types) {
		for (Element member : elements.getAllMembers(repositoryInterface)) {
			if (member.getKind() != ElementKind.METHOD || !(member instanceof ExecutableElement method)) {
				continue;
			}
			if (!method.getSimpleName().contentEquals("findAll") || method.getParameters().size() != 1) {
				continue;
			}
			TypeMirror parameter = method.getParameters().get(0).asType();
			if (types.erasure(parameter).toString().contentEquals(PAGEABLE_FQN)) {
				return true;
			}
		}
		return false;
	}

	private static DeclaredType findRepositoryBinding(TypeElement iface, TypeElement repositoryType, Types types) {
		return findBindingInSupertypes(iface.asType(), repositoryType, types);
	}

	private static DeclaredType findBindingInSupertypes(TypeMirror type, TypeElement repositoryType, Types types) {
		if (type.getKind() != TypeKind.DECLARED) {
			return null;
		}
		DeclaredType declared = (DeclaredType) type;
		if (((TypeElement) declared.asElement()).getQualifiedName().contentEquals(REPOSITORY_FQN)) {
			return declared;
		}
		for (TypeMirror supertype : types.directSupertypes(declared)) {
			DeclaredType found = findBindingInSupertypes(supertype, repositoryType, types);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/** 把类型解析成可写入源码的名字；基本类型给出字面名（{@code long countByStatus} 的返回类型要靠它判定），
	 * 无法表达的其余类型回退为 Object。 */
	private static String sourceName(TypeMirror type, Types types) {
		if (type.getKind() == TypeKind.DECLARED) {
			DeclaredType declared = (DeclaredType) type;
			String base = ((TypeElement) declared.asElement()).getQualifiedName().toString();
			if (declared.getTypeArguments().isEmpty()) {
				return base;
			}
			StringBuilder sb = new StringBuilder(base).append('<');
			List<? extends TypeMirror> args = declared.getTypeArguments();
			for (int i = 0; i < args.size(); i++) {
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(sourceName(args.get(i), types));
			}
			return sb.append('>').toString();
		}
		if (type.getKind() == TypeKind.ARRAY) {
			return sourceName(((ArrayType) type).getComponentType(), types) + "[]";
		}
		return switch (type.getKind()) {
			case VOID -> "void";
			case BOOLEAN -> "boolean";
			case BYTE -> "byte";
			case SHORT -> "short";
			case INT -> "int";
			case LONG -> "long";
			case CHAR -> "char";
			case FLOAT -> "float";
			case DOUBLE -> "double";
			default -> "java.lang.Object";
		};
	}

	private static void writeImplementation(TypeElement repositoryInterface, String packageName, String entity,
			String id, EntityMetadata metadata, Map<String, String> statements, List<DerivedQuery> queries,
			boolean paging, Filer filer) throws IOException {
		String simpleName = repositoryInterface.getSimpleName() + "Impl";
		String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
		JavaFileObject file = filer.createSourceFile(qualifiedName, repositoryInterface);
		try (Writer writer = file.openWriter()) {
			if (!packageName.isEmpty()) {
				writer.write("package " + packageName + ";\n\n");
			}
			writer.write(ImplementationSource.generate(repositoryInterface.getSimpleName().toString(), simpleName,
					entity, id, metadata, statements, queries, paging));
		}
	}

	/**
	 * 把本模块处理到的所有仓储接口 FQN 落盘为 {@code META-INF/autumn-data/repositories}，
	 * 供 starter 的 {@code RepositoryRegistrar} 读取并注册成 Spring Bean（无需 @Service）。
	 *
	 * <p>增量编译（改一个文件后 IDE/构建只重编那一个）本轮只看得见改动的接口，直接覆盖会把
	 * 别人从清单里抹掉，表现为启动时 {@code required a bean of type 'X' that could not be found}
	 * —— 实现类 .class 明明在，只是没被注册。因此先读回既有清单再合并写入。</p>
	 */
	private static void writeRepositoryRegistry(List<String> repositories, Filer filer) throws IOException {
		Set<String> entries = new LinkedHashSet<>(readRegistry(filer));
		entries.addAll(repositories);
		StringBuilder sb = new StringBuilder();
		for (String fqn : entries) {
			sb.append(fqn).append('\n');
		}
		FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "", REGISTRY_PATH);
		try (Writer writer = file.openWriter()) {
			writer.write(sb.toString());
		}
	}

	/** 读回既有清单；读不到（首轮编译、目录还没这个文件）就当作空清单。 */
	private static List<String> readRegistry(Filer filer) {
		List<String> entries = new ArrayList<>();
		try {
			FileObject existing = filer.getResource(StandardLocation.CLASS_OUTPUT, "", REGISTRY_PATH);
			StringBuilder content = new StringBuilder();
			try (Reader reader = existing.openReader(true)) {
				for (int c = reader.read(); c != -1; c = reader.read()) {
					content.append((char) c);
				}
			}
			for (String line : content.toString().split("\n")) {
				if (!line.isBlank()) {
					entries.add(line.trim());
				}
			}
		}
		catch (IOException | IllegalArgumentException | IllegalStateException ex) {
			// 清单不存在或不可读：本轮清单照样全量写入，不做合并
		}
		return entries;
	}

	/**
	 * 编译期可选的方言：由 {@code -Aautumn.data.jdbc.dialect} 指定，决定生成的 SQL 是否给标识符加引号。
	 *
	 * <p>默认不加（返回 {@code null}）：既有的 schema、断言与运行期行为都基于裸标识符，
	 * 盲加引号会让「未引号建表 + 引号查询」在大小写敏感的库上落空。需要保留字安全时显式开启。</p>
	 */
	private Dialect compileTimeDialect(Element reportOn) {
		String configured = processingEnv.getOptions().get(DIALECT_OPTION);
		if (configured == null || configured.isBlank()) {
			return null;
		}
		return switch (configured.trim().toLowerCase(Locale.ROOT)) {
			case "mysql" -> new MySqlDialect();
			case "postgresql", "postgres" -> new PostgreSqlDialect();
			case "h2" -> new H2Dialect();
			case "default", "ansi" -> new DefaultDialect();
			default -> {
				error("unsupported value '" + configured + "' for -A" + DIALECT_OPTION
						+ " (use mysql, postgresql, h2 or default)", reportOn);
				yield null;
			}
		};
	}

	private void error(String message, Element element) {
		processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
	}
}
