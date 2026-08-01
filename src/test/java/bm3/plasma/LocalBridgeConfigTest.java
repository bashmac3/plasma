package bm3.plasma;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalBridgeConfigTest {

	@TempDir
	Path tempDir;

	@Test
	void loadCreatesConfigWithGeneratedToken() throws IOException {
		LocalBridgeConfig config = LocalBridgeConfig.load(tempDir);
		String token = config.getToken();
		assertTrue(token.length() >= 16, "token too short: " + token);
		assertTrue(token.matches("[A-Za-z0-9]+"), "token has invalid chars: " + token);

		Path file = tempDir.resolve(LocalBridgeConfig.FILE_NAME);
		assertTrue(Files.exists(file));
		String content = Files.readString(file);
		assertTrue(content.contains("token="), content);
		assertTrue(content.contains(token), content);
	}

	@Test
	void loadReadsExistingToken() throws IOException {
		Files.writeString(tempDir.resolve(LocalBridgeConfig.FILE_NAME), "token=existingToken123\n");
		LocalBridgeConfig config = LocalBridgeConfig.load(tempDir);
		assertEquals("existingToken123", config.getToken());
	}

	@Test
	void loadIgnoresBlankTokenAndRegenerates() throws IOException {
		Files.writeString(tempDir.resolve(LocalBridgeConfig.FILE_NAME), "token=   \n");
		LocalBridgeConfig config = LocalBridgeConfig.load(tempDir);
		assertTrue(config.getToken().length() >= 16);
	}

	@Test
	void rotateWritesNewTokenAndPersists() throws IOException {
		LocalBridgeConfig config = LocalBridgeConfig.load(tempDir);
		String old = config.getToken();
		String rotated = LocalBridgeConfig.rotate(tempDir);
		assertNotEquals(old, rotated);

		LocalBridgeConfig reloaded = LocalBridgeConfig.load(tempDir);
		assertEquals(rotated, reloaded.getToken());
	}

	@Test
	void rotateProducesDistinctTokens() throws IOException {
		String a = LocalBridgeConfig.rotate(tempDir);
		String b = LocalBridgeConfig.rotate(tempDir);
		assertNotEquals(a, b);
	}

	@Test
	void generatedTokensVaryAcrossLoads() throws IOException {
		String a = LocalBridgeConfig.rotate(tempDir);
		Files.deleteIfExists(tempDir.resolve(LocalBridgeConfig.FILE_NAME));
		String b = LocalBridgeConfig.rotate(tempDir);
		assertNotEquals(a, b);
	}

	@Test
	void loadsCustomTimeoutAndMaxAttempts() throws IOException {
		Files.writeString(tempDir.resolve(LocalBridgeConfig.FILE_NAME),
			"token=t\n"
				+ LocalBridgeConfig.KEY_TIMEOUT_SECONDS + "=5\n"
				+ LocalBridgeConfig.KEY_MAX_ATTEMPTS + "=3\n");
		LocalBridgeConfig config = LocalBridgeConfig.load(tempDir);
		assertEquals(5000, config.getExecutionTimeoutMillis());
		assertEquals(3, config.getMaxFailedAttempts());
	}

	@Test
	void defaultsApplyWhenSettingsMissing() throws IOException {
		Files.writeString(tempDir.resolve(LocalBridgeConfig.FILE_NAME), "token=t\n");
		LocalBridgeConfig config = LocalBridgeConfig.load(tempDir);
		assertEquals(LocalBridgeConfig.DEFAULT_TIMEOUT_SECONDS * 1000, config.getExecutionTimeoutMillis());
		assertEquals(LocalBridgeConfig.DEFAULT_MAX_ATTEMPTS, config.getMaxFailedAttempts());
	}

	@Test
	void invalidValuesFallBackToDefaults() throws IOException {
		Files.writeString(tempDir.resolve(LocalBridgeConfig.FILE_NAME),
			"token=t\n"
				+ LocalBridgeConfig.KEY_TIMEOUT_SECONDS + "=abc\n"
				+ LocalBridgeConfig.KEY_MAX_ATTEMPTS + "=-1\n");
		LocalBridgeConfig config = LocalBridgeConfig.load(tempDir);
		assertEquals(LocalBridgeConfig.DEFAULT_TIMEOUT_SECONDS * 1000, config.getExecutionTimeoutMillis());
		assertEquals(0, config.getMaxFailedAttempts());
	}

	@Test
	void rotatePreservesCustomSettings() throws IOException {
		Files.writeString(tempDir.resolve(LocalBridgeConfig.FILE_NAME),
			"token=t\n"
				+ LocalBridgeConfig.KEY_TIMEOUT_SECONDS + "=7\n"
				+ LocalBridgeConfig.KEY_MAX_ATTEMPTS + "=2\n");
		LocalBridgeConfig.rotate(tempDir);
		LocalBridgeConfig config = LocalBridgeConfig.load(tempDir);
		assertEquals(7000, config.getExecutionTimeoutMillis());
		assertEquals(2, config.getMaxFailedAttempts());
	}
}
