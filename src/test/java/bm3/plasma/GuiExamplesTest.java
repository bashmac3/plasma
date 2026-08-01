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
	private static final Path SCRIPT = Path.of("scripts/send_payload.py");

	@Test
	void guiExamplePayloadsExist() throws IOException {
		assertTrue(Files.isDirectory(GUI_DIR), "missing examples/gui");
		try (Stream<Path> stream = Files.list(GUI_DIR)) {
			long jsonCount = stream.filter(p -> p.toString().endsWith(".json")).count();
			assertTrue(jsonCount >= 3, "expected at least 3 json payloads, got " + jsonCount);
		}
	}

	@Test
	void everyGuiPayloadIsASnippetThatCallsShow() throws IOException {
		try (Stream<Path> stream = Files.list(GUI_DIR)) {
			for (Path file : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
				JsonObject object = parse(file);
				assertEquals("run", object.get("method").getAsString(),
					file.getFileName() + " should use method run");
				String code = object.get("code").getAsString();
				assertTrue(code.contains("SimpleGui"), file.getFileName() + " should use SimpleGui");
				assertTrue(code.contains(".show()"), file.getFileName() + " should call .show()");
			}
		}
	}

	@Test
	void everyGuiPayloadReferencesOnlyExistingSimpleGuiMethods() throws IOException {
		String simpleGui = readClientSource("bm3/plasma/client/gui/SimpleGui.java");
		List<String> builderCalls = List.of(".button(", ".label(", ".editBox(", ".checkbox(",
			".cycle(", ".show(", ".text(", ".checked(", ".selected(", ".close()");
		try (Stream<Path> stream = Files.list(GUI_DIR)) {
			for (Path file : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
				String code = parse(file).get("code").getAsString();
				for (String call : builderCalls) {
					if (code.contains(call)) {
						String method = call.substring(1, call.length() - 1);
						if (!simpleGui.contains(method)) {
							fail(file.getFileName() + " calls builder." + method
								+ "() but SimpleGui does not define it");
						}
					}
				}
			}
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
	void devScreenHasNoCompiledClassDependencies() throws IOException {
		JsonObject dev = parse(GUI_DIR.resolve("dev_screen.json"));
		assertTrue(dev.has("code"), "dev_screen.json should be a snippet");
		assertTrue(!dev.has("className"), "dev_screen.json must not reference a compiled class");
	}

	@Test
	void sendScriptSupportsWatchMode() throws IOException {
		String script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
		assertTrue(script.contains("--watch"), "send_payload.py should support --watch");
		assertTrue(script.contains("time.sleep"), "watch mode should poll for changes");
	}

	private JsonObject parse(Path file) throws IOException {
		String json = Files.readString(file, StandardCharsets.UTF_8);
		return JsonParser.parseString(json).getAsJsonObject();
	}

	private String readClientSource(String relative) throws IOException {
		Path path = CLIENT_SRC.resolve(relative);
		assertTrue(Files.exists(path), "missing client source " + path);
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
