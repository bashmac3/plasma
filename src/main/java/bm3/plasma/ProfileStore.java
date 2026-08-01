package bm3.plasma;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class ProfileStore {
	private static final String FILE_NAME = "plasma-profiles.json";

	private final Path path;
	private final Map<String, JsonElement> profiles = new LinkedHashMap<>();

	public ProfileStore(Path configDir) {
		this.path = configDir.resolve(FILE_NAME);
		load();
	}

	public void put(String name, JsonElement payload) {
		profiles.put(name, payload);
		save();
	}

	public JsonElement get(String name) {
		return profiles.get(name);
	}

	public boolean remove(String name) {
		boolean removed = profiles.remove(name) != null;
		if (removed) {
			save();
		}
		return removed;
	}

	public Set<String> names() {
		return profiles.keySet();
	}

	public int size() {
		return profiles.size();
	}

	private void load() {
		if (!Files.exists(path)) {
			return;
		}
		try {
			String text = Files.readString(path, StandardCharsets.UTF_8);
			JsonObject root = JsonParser.parseString(text).getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				profiles.put(entry.getKey(), entry.getValue());
			}
		} catch (Exception ignored) {
		}
	}

	private void save() {
		JsonObject root = new JsonObject();
		for (Map.Entry<String, JsonElement> entry : profiles.entrySet()) {
			root.add(entry.getKey(), entry.getValue());
		}
		try {
			Files.writeString(path, new Gson().toJson(root), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}
}
