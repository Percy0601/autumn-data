package xyz.wewin.autumn.data.jdbc.compiler;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

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
 * 批量读写与一对多装配。派生查询（{@code findByNameAndAge} 之类，见
 * docs/spring-data-derived-query.md 第 8 节）是下一步。</p>
 */
@SupportedAnnotationTypes("xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class AutoRepositoryProcessor extends AbstractProcessor {

	static final String REPOSITORY_FQN = "xyz.wewin.autumn.data.jdbc.repository.Repository";

	private static final String PAGEABLE_FQN = "xyz.wewin.autumn.data.jdbc.page.Pageable";

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
			try {
				Map<String, String> statements = CrudSqlGenerator.generate(metadata);
				writeImplementation(repositoryInterface, packageName, entity, id, metadata, statements,
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

	/** 把类型实参解析成可写入源码的名字；无法表达的类型回退为 Object（骨架阶段够用）。 */
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
		return "java.lang.Object";
	}

	private static void writeImplementation(TypeElement repositoryInterface, String packageName, String entity,
			String id, EntityMetadata metadata, Map<String, String> statements, boolean paging, Filer filer)
			throws IOException {
		String simpleName = repositoryInterface.getSimpleName() + "Impl";
		String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
		JavaFileObject file = filer.createSourceFile(qualifiedName, repositoryInterface);
		try (Writer writer = file.openWriter()) {
			if (!packageName.isEmpty()) {
				writer.write("package " + packageName + ";\n\n");
			}
			writer.write(ImplementationSource.generate(repositoryInterface.getSimpleName().toString(), simpleName,
					entity, id, metadata, statements, paging));
		}
	}

	/** 把本模块处理到的所有仓储接口 FQN 落盘为 {@code META-INF/autumn-data/repositories}，
	 * 供 starter 的 {@code RepositoryRegistrar} 读取并注册成 Spring Bean（无需 @Service）。 */
	private static void writeRepositoryRegistry(List<String> repositories, Filer filer) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (String fqn : repositories) {
			sb.append(fqn).append('\n');
		}
		FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
				"META-INF/autumn-data/repositories");
		try (Writer writer = file.openWriter()) {
			writer.write(sb.toString());
		}
	}

	private void error(String message, Element element) {
		processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
	}
}
