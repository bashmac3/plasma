package bm3.plasma;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileStoreTest {

	@TempDir
	Path tempDir;

	private ProfileStore store() {
		return new ProfileStore(tempDir);
	}

	@Test
	void putGetRoundTrip() {
		ProfileStore store = store();
		store.put("hello", JsonParser.parseString("{\"className\":\"x.Y\"}"));
		assertEquals("x.Y", store.get("hello").getAsJsonObject().get("className").getAsString());
		assertEquals(1, store.size());
		assertTrue(store.names().contains("hello"));
	}

	@Test
	void storeArraysOfPackets() {
		ProfileStore store = store();
		store.put("rain", JsonParser.parseString("[1,2,3]"));
		assertEquals(3, store.get("rain").getAsJsonArray().size());
	}

	@Test
	void persistsAcrossInstances() {
		store().put("rain", JsonParser.parseString("[1,2,3]"));
		ProfileStore reloaded = new ProfileStore(tempDir);
		assertTrue(reloaded.names().contains("rain"));
		assertEquals(3, reloaded.get("rain").getAsJsonArray().size());
	}

	@Test
	void putOverwritesExistingProfile() {
		ProfileStore store = store();
		store.put("x", JsonParser.parseString("{\"a\":1}"));
		store.put("x", JsonParser.parseString("{\"a\":2}"));
		assertEquals(1, store.size());
		assertEquals(2, store.get("x").getAsJsonObject().get("a").getAsInt());
	}

	@Test
	void removeDeletesAndPersists() {
		ProfileStore store = store();
		store.put("x", JsonParser.parseString("{}"));
		assertTrue(store.remove("x"));
		assertFalse(store.remove("x"));
		assertTrue(new ProfileStore(tempDir).names().isEmpty());
	}

	@Test
	void emptyStoreHasNoProfiles() {
		ProfileStore store = store();
		assertEquals(0, store.size());
		assertTrue(store.names().isEmpty());
		assertNull(store.get("missing"));
		assertFalse(store.remove("missing"));
	}

	@Test
	void namesAreInsertionOrdered() {
		ProfileStore store = store();
		store.put("z", JsonParser.parseString("{}"));
		store.put("a", JsonParser.parseString("{}"));
		store.put("m", JsonParser.parseString("{}"));
		assertEquals("[z, a, m]", store.names().toString());
	}
}
