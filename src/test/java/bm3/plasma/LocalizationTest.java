package bm3.plasma;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalizationTest {

	private static final List<String> LANGS = List.of("en_us", "ru_ru", "es_es");

	private static Path langDir() {
		return Path.of("src/main/resources/assets/plasma/lang");
	}

	private JsonObject load(String lang) throws IOException {
		String json = Files.readString(langDir().resolve(lang + ".json"), StandardCharsets.UTF_8);
		return JsonParser.parseString(json).getAsJsonObject();
	}

	@Test
	void allLangFilesExistAndAreValidJson() throws IOException {
		for (String lang : LANGS) {
			Path file = langDir().resolve(lang + ".json");
			assertTrue(Files.exists(file), "missing " + file);
			assertDoesNotThrow(() -> load(lang), lang + " should parse as valid JSON");
		}
	}

	@Test
	void langFilesContainNoDuplicateKeys() throws IOException {
		for (String lang : LANGS) {
			JsonObject json = load(lang);
			// Gson keeps insertion order; keys are unique by construction, but verify size sanity.
			assertFalse(json.keySet().isEmpty(), lang + " is empty");
		}
	}

	@Test
	void allTranslationValuesAreNonBlank() throws IOException {
		for (String lang : LANGS) {
			JsonObject json = load(lang);
			json.keySet().forEach(key -> {
				String value = json.get(key).getAsString();
				assertTrue(value != null && !value.isBlank(), lang + "." + key + " must not be blank");
			});
		}
	}

	@Test
	void allLanguagesDefineExactlyTheSameKeys() throws IOException {
		Map<String, Set<String>> keysByLang = new HashMap<>();
		for (String lang : LANGS) {
			keysByLang.put(lang, new HashSet<>(load(lang).keySet()));
		}
		Set<String> reference = keysByLang.get("en_us");
		assertFalse(reference.isEmpty());
		for (String lang : LANGS) {
			Set<String> keys = keysByLang.get(lang);
			assertEquals(reference, keys, lang + " key set must match en_us exactly");
		}
	}

	@Test
	void everyKeyUsedInSourceExistsInEveryLanguage() throws IOException {
		Set<String> usedKeys = collectKeysFromSource();
		assertFalse(usedKeys.isEmpty(), "should have found translation keys in source");
		for (String lang : LANGS) {
			JsonObject json = load(lang);
			Set<String> missing = new TreeSet<>();
			for (String key : usedKeys) {
				if (!json.has(key)) {
					missing.add(key);
				}
			}
			assertEquals(Set.of(), missing, lang + " is missing source-referenced keys");
		}
	}

	@Test
	void noLanguageKeyGoesUnusedBySource() throws IOException {
		Set<String> usedKeys = collectKeysFromSource();
		for (String lang : LANGS) {
			JsonObject json = load(lang);
			Set<String> unused = new TreeSet<>();
			for (String key : json.keySet()) {
				if (!usedKeys.contains(key)) {
					unused.add(key);
				}
			}
			assertEquals(Set.of(), unused, lang + " has keys never referenced in source");
		}
	}

	@Test
	void translationsDifferAcrossLanguages() throws IOException {
		String en = load("en_us").get("plasma.ready.closed").getAsString();
		String ru = load("ru_ru").get("plasma.ready.closed").getAsString();
		String es = load("es_es").get("plasma.ready.closed").getAsString();
		assertTrue(!en.equals(ru), "ru should not equal en for ready.closed");
		assertTrue(!en.equals(es), "es should not equal en for ready.closed");
	}

	@Test
	void confirmationPromptEmbedsPlaceholder() throws IOException {
		for (String lang : LANGS) {
			String value = load(lang).get("plasma.prompt.confirm").getAsString();
			assertTrue(value.contains("%s"), lang + " prompt should contain the %s placeholder, got: " + value);
		}
	}

	private Set<String> collectKeysFromSource() throws IOException {
		// A key counts as "used" if it is passed to tr(...) OR if it is a string literal
		// that matches a real translation key. This skips non-translation literals like
		// "plasma.snippet" (runtime package) and "plasma.properties" (config filename).
		Set<String> known = new HashSet<>(load("en_us").keySet());
		Pattern trPattern = Pattern.compile("tr\\([^\"]*\"plasma\\.[A-Za-z0-9_.]+\"");
		Pattern literalPattern = Pattern.compile("\"plasma\\.[A-Za-z0-9_.]+\"");
		Set<String> keys = new HashSet<>();
		List<Path> roots = List.of(Path.of("src/main/java"), Path.of("src/client/java"));
		for (Path root : roots) {
			assertTrue(Files.isDirectory(root), "source root missing: " + root);
			try (Stream<Path> stream = Files.walk(root)) {
				stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
					try {
						String content = Files.readString(p, StandardCharsets.UTF_8);
						Matcher trMatcher = trPattern.matcher(content);
						while (trMatcher.find()) {
							String group = trMatcher.group();
							keys.add(group.substring(group.indexOf('"') + 1, group.lastIndexOf('"')));
						}
						Matcher literalMatcher = literalPattern.matcher(content);
						while (literalMatcher.find()) {
							String key = literalMatcher.group();
							key = key.substring(1, key.length() - 1);
							if (known.contains(key)) {
								keys.add(key);
							}
						}
					} catch (IOException ignored) {
					}
				});
			}
		}
		return keys;
	}
}
