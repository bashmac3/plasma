package bm3.plasma;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
