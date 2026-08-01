package bm3.plasma;

import com.google.gson.JsonObject;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SnippetEvaluator {
	private static final String PACKAGE = "plasma.snippet";

	private SnippetEvaluator() {
	}

	public static String extractCode(JsonObject object) {
		String code = getString(object, "code");
		if (code == null) {
			code = getString(object, "snippet");
		}
		return code;
	}

	public static void evaluate(String code, String method) throws Exception {
		String className = PACKAGE + ".Snippet_" + Long.toHexString(System.nanoTime());
		String source = buildSource(className, method, code);
		Map<String, byte[]> classes = compile(className, source);

		ClassLoader parent = Thread.currentThread().getContextClassLoader();
		try (MapClassLoader loader = new MapClassLoader(classes, parent)) {
			Class<?> target = Class.forName(className, true, loader);
			if ("main".equalsIgnoreCase(method)) {
				target.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
			} else {
				((Runnable) target.getDeclaredConstructor().newInstance()).run();
			}
		}
	}

	private static String buildSource(String className, String method, String code) {
		String simpleName = className.substring(className.lastIndexOf('.') + 1);
		if ("main".equalsIgnoreCase(method)) {
			return "package " + PACKAGE + ";\n"
				+ "public class " + simpleName + " {\n"
				+ "    public static void main(String[] args) {\n"
				+ code + "\n"
				+ "    }\n"
				+ "}\n";
		}
		return "package " + PACKAGE + ";\n"
			+ "public class " + simpleName + " implements Runnable {\n"
			+ "    public void run() {\n"
			+ code + "\n"
			+ "    }\n"
			+ "}\n";
	}

	private static Map<String, byte[]> compile(String className, String source) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IllegalStateException("No system Java compiler available (not running on a JDK)");
		}

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		JavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
		InMemoryFileManager fileManager = new InMemoryFileManager(standard);

		JavaFileObject sourceFile = new SimpleJavaFileObject(
			URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
			JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return source;
			}
		};

		List<String> options = new ArrayList<>();
		String classPath = buildClasspath();
		if (classPath != null && !classPath.isBlank()) {
			options.add("-classpath");
			options.add(classPath);
		}

		JavaCompiler.CompilationTask task = compiler.getTask(
			null, fileManager, diagnostics, options, null, List.of(sourceFile));
		boolean ok = task.call();
		if (!ok) {
			StringBuilder error = new StringBuilder("COMPILATION FAILED");
			for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
				error.append("\n").append(diagnostic.getKind())
					.append(" line ").append(diagnostic.getLineNumber())
					.append(": ").append(diagnostic.getMessage(Locale.ROOT));
			}
			throw new IllegalStateException(error.toString());
		}
		return fileManager.getClasses();
	}

	private static String buildClasspath() {
		java.util.Set<String> entries = new java.util.LinkedHashSet<>();
		String property = System.getProperty("java.class.path");
		if (property != null && !property.isBlank()) {
			for (String entry : property.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
				if (!entry.isBlank()) {
					entries.add(entry.trim());
				}
			}
		}
		for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null;
				loader = loader.getParent()) {
			if (loader instanceof java.net.URLClassLoader urlClassLoader) {
				for (java.net.URL url : urlClassLoader.getURLs()) {
					if (!url.getProtocol().equals("file")) {
						continue;
					}
					try {
						entries.add(java.nio.file.Paths.get(url.toURI()).toString());
					} catch (java.net.URISyntaxException ignored) {
					}
				}
			}
		}
		return String.join(java.io.File.pathSeparator, entries);
	}

	private static String getString(JsonObject object, String key) {
		if (object.has(key) && object.get(key).isJsonPrimitive()) {
			return object.get(key).getAsString();
		}
		return null;
	}

	private static final class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
		private final Map<String, ByteArrayOutputStream> outputs = new HashMap<>();

		InMemoryFileManager(JavaFileManager fileManager) {
			super(fileManager);
		}

		@Override
		public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
				JavaFileObject.Kind kind, FileObject sibling) {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			outputs.put(className, output);
			return new SimpleJavaFileObject(
				URI.create("mem:///" + className.replace('.', '/') + JavaFileObject.Kind.CLASS.extension),
				JavaFileObject.Kind.CLASS) {
				@Override
				public OutputStream openOutputStream() {
					return output;
				}
			};
		}

		Map<String, byte[]> getClasses() {
			Map<String, byte[]> classes = new HashMap<>();
			outputs.forEach((name, out) -> classes.put(name, out.toByteArray()));
			return classes;
		}
	}

	private static final class MapClassLoader extends ClassLoader implements AutoCloseable {
		private final Map<String, byte[]> classes;

		MapClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
			super(parent);
			this.classes = classes;
		}

		@Override
		public void close() {
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) {
				throw new ClassNotFoundException(name);
			}
			return defineClass(name, bytes, 0, bytes.length);
		}
	}
}
