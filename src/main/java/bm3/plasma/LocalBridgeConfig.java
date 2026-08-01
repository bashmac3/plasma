package bm3.plasma;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Properties;

public class LocalBridgeConfig {
	public static final String FILE_NAME = "plasma.properties";
	public static final String KEY_TOKEN = "token";
	public static final String KEY_TIMEOUT_SECONDS = "execution_timeout_seconds";
	public static final String KEY_MAX_ATTEMPTS = "max_failed_attempts";
	public static final long DEFAULT_TIMEOUT_SECONDS = 60;
	public static final int DEFAULT_MAX_ATTEMPTS = 5;

	private final Path configPath;
	private final String token;
	private final long executionTimeoutMillis;
	private final int maxFailedAttempts;

	private LocalBridgeConfig(Path configPath, String token, long executionTimeoutMillis, int maxFailedAttempts) {
		this.configPath = configPath;
		this.token = token;
		this.executionTimeoutMillis = executionTimeoutMillis;
		this.maxFailedAttempts = maxFailedAttempts;
	}

	public static LocalBridgeConfig load(Path configDirectory) throws IOException {
		Files.createDirectories(configDirectory);
		Path configPath = configDirectory.resolve(FILE_NAME);
		Properties properties = new Properties();

		if (Files.exists(configPath)) {
			try (InputStream input = Files.newInputStream(configPath)) {
				properties.load(input);
			}
		}

		String token = properties.getProperty(KEY_TOKEN, "").trim();
		if (token.isEmpty()) {
			token = generateToken();
			saveToken(configPath, token);
		}

		long timeoutSeconds = parseLong(properties.getProperty(KEY_TIMEOUT_SECONDS), DEFAULT_TIMEOUT_SECONDS);
		int maxAttempts = parseInt(properties.getProperty(KEY_MAX_ATTEMPTS), DEFAULT_MAX_ATTEMPTS);
		return new LocalBridgeConfig(configPath, token, Math.max(0, timeoutSeconds) * 1000, Math.max(0, maxAttempts));
	}

	private static long parseLong(String value, long fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int parseInt(String value, int fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public String getToken() {
		return token;
	}

	public long getExecutionTimeoutMillis() {
		return executionTimeoutMillis;
	}

	public int getMaxFailedAttempts() {
		return maxFailedAttempts;
	}

	public static String rotate(Path configDirectory) throws IOException {
		Files.createDirectories(configDirectory);
		Path configPath = configDirectory.resolve(FILE_NAME);
		String token = generateToken();
		saveToken(configPath, token);
		return token;
	}

	private static final String TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final int TOKEN_LENGTH = 32;

	private static String generateToken() {
		SecureRandom random = new SecureRandom();
		StringBuilder builder = new StringBuilder(TOKEN_LENGTH);
		for (int i = 0; i < TOKEN_LENGTH; i++) {
			builder.append(TOKEN_ALPHABET.charAt(random.nextInt(TOKEN_ALPHABET.length())));
		}
		return builder.toString();
	}

	private static void saveToken(Path configPath, String token) throws IOException {
		Properties properties = new Properties();
		if (Files.exists(configPath)) {
			try (InputStream input = Files.newInputStream(configPath)) {
				properties.load(input);
			}
		}
		properties.setProperty(KEY_TOKEN, token);
		try (OutputStream output = Files.newOutputStream(configPath)) {
			properties.store(output, "Plasma local bridge config");
		}
	}
}
