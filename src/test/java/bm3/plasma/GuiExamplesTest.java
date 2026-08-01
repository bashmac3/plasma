package bm3.plasma;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class GuiExamplesTest {

	private static final Path GUI_DIR = Path.of("examples/gui");
	private static final Path CLIENT_SRC = Path.of("src/client/java");

	@Test
	void guiExamplePayloadsExist() throws IOException {
		assertTrue(Files.isDirectory(GUI_DIR), "missing examples/gui");
		try (Stream<Path> stream = Files.list(GUI_DIR)) {
			long jsonCount = stream.filter(p -> p.toString().endsWith(".json")).count();
			assertTrue(jsonCount >= 3, "expected at least 3 json payloads, got " + jsonCount);
		}
	}

	@Test
	void classNamePayloadsReferenceExistingDemoClasses() throws IOException {
		for (String file : List.of("01_button_at_origin.json", "02_widget_gallery.json")) {
			JsonObject object = parse(file);
			assertTrue(object.has("className"), file + " should declare a className");
			assertTrue(object.has("method"), file + " should declare a method");
			String className = object.get("className").getAsString();
			String relative = className.replace('.', '/') + ".java";
			assertTrue(Files.exists(CLIENT_SRC.resolve(relative)),
				file + " references missing class " + className);
		}
	}

	@Test
	void snippetPayloadIsValid() throws IOException {
		JsonObject object = parse("03_snippet_screen.json");
		assertEquals("run", object.get("method").getAsString(), "03 should use method run");
		String code = object.get("code").getAsString();
		assertTrue(code.contains("SimpleGui"), "03 should build a screen via SimpleGui");
		assertTrue(code.contains(".show()"), "03 should call show()");
	}

	@Test
	void demoClassesImplementRunnable() throws IOException {
		for (String className : List.of("ButtonDemo", "WidgetGalleryDemo")) {
			String source = readClientSource("bm3/plasma/client/gui/" + className + ".java");
			assertTrue(source.contains("implements Runnable"), className + " must implement Runnable");
			assertTrue(source.contains("public void run()"), className + " must define run()");
		}
	}

	@Test
	void simpleGuiExposesExpectedBuilderMethods() throws IOException {
		String source = readClientSource("bm3/plasma/client/gui/SimpleGui.java");
		for (String method : List.of("builder", "button", "label", "editBox", "checkbox",
				"cycle", "show", "text", "checked", "selected", "close")) {
			assertTrue(source.contains(method), "SimpleGui should expose " + method);
		}
	}

	@Test
	void demoClassesOnlyCallBuilderMethodsDefinedBySimpleGui() throws IOException {
		String simpleGui = readClientSource("bm3/plasma/client/gui/SimpleGui.java");
		List<String> builderCalls = List.of(".button(", ".label(", ".editBox(", ".checkbox(",
			".cycle(", ".show(", ".text(", ".checked(", ".selected(");
		for (String className : List.of("ButtonDemo", "WidgetGalleryDemo")) {
			String source = readClientSource("bm3/plasma/client/gui/" + className + ".java");
			for (String call : builderCalls) {
				String method = call.substring(1, call.length() - 1);
				if (source.contains(call) && !simpleGui.contains(method)) {
					fail(className + " calls builder." + method + "() but SimpleGui does not define it");
				}
			}
		}
	}

	private JsonObject parse(String file) throws IOException {
		String json = Files.readString(GUI_DIR.resolve(file), StandardCharsets.UTF_8);
		return JsonParser.parseString(json).getAsJsonObject();
	}

	private String readClientSource(String relative) throws IOException {
		Path path = CLIENT_SRC.resolve(relative);
		assertTrue(Files.exists(path), "missing client source " + path);
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
