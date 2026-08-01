package bm3.plasma;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetEvaluatorTest {

	private static JsonObject object(String key, String value) {
		JsonObject o = new JsonObject();
		o.addProperty(key, value);
		return o;
	}

	@Test
	void extractsCodeFromCodeKey() {
		assertEquals("int x = 1;", SnippetEvaluator.extractCode(object("code", "int x = 1;")));
	}

	@Test
	void extractsCodeFromSnippetKey() {
		assertEquals("int y = 2;", SnippetEvaluator.extractCode(object("snippet", "int y = 2;")));
	}

	@Test
	void codeKeyWinsOverSnippetKey() {
		JsonObject o = object("code", "first");
		o.addProperty("snippet", "second");
		assertEquals("first", SnippetEvaluator.extractCode(o));
	}

	@Test
	void returnsNullWhenNoCodePresent() {
		assertNull(SnippetEvaluator.extractCode(new JsonObject()));
		assertNull(SnippetEvaluator.extractCode(object("className", "x.Y")));
	}

	@Test
	void evaluatesRunnableSnippet() throws Exception {
		SnippetEvaluator.evaluate("System.out.println(\"snippet-ran\");", "run");
	}

	@Test
	void evaluatesMainSnippet() throws Exception {
		SnippetEvaluator.evaluate("System.out.println(\"snippet-main\");", "main");
	}

	@Test
	void methodIsCaseInsensitiveForMain() throws Exception {
		SnippetEvaluator.evaluate("System.out.println(\"snippet-main-upper\");", "MAIN");
	}

	@Test
	void snippetCanUseEnvironment() throws Exception {
		SnippetEvaluator.evaluate("System.out.println(System.getProperty(\"java.version\"));", "run");
	}

	@Test
	void compilationFailureRaisesFlaggedError() {
		Exception e = assertThrows(IllegalStateException.class,
			() -> SnippetEvaluator.evaluate("this is not valid java;", "run"));
		assertTrue(e.getMessage().contains("COMPILATION FAILED"), e.getMessage());
	}

	@Test
	void runtimeExceptionPropagatesToCaller() {
		RuntimeException e = assertThrows(RuntimeException.class,
			() -> SnippetEvaluator.evaluate("throw new RuntimeException(\"snippet-boom\");", "run"));
		assertTrue(e.getMessage().contains("snippet-boom"), e.getMessage());
	}

	@Test
	void snippetCanReferenceClassesFromTheRuntimeClasspath() throws Exception {
		SnippetEvaluator.evaluate(
			"System.out.println(bm3.plasma.SampleTask.class.getName());", "run");
	}

	@Test
	void snippetCanReferenceClassesReachableOnlyViaContextClassLoaderUrls() throws Exception {
		Path dir = Files.createTempDirectory("plasma-snippet");
		Path sourceFile = dir.resolve("DevExtra.java");
		Files.writeString(sourceFile,
			"package bm3.plasma;\n"
			+ "public class DevExtra {\n"
			+ "    public static String tag() { return \"extra-ok\"; }\n"
			+ "}\n");
		javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "needs a JDK for in-test compilation");
		int exit = compiler.run(null, null, null,
			"-d", dir.toString(), sourceFile.toString());
		assertEquals(0, exit, "helper class should compile");

		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try (java.net.URLClassLoader loader =
				new java.net.URLClassLoader(new java.net.URL[]{dir.toUri().toURL()}, previous)) {
			Thread.currentThread().setContextClassLoader(loader);
			SnippetEvaluator.evaluate(
				"System.out.println(bm3.plasma.DevExtra.tag());", "run");
		} finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
	}
}
