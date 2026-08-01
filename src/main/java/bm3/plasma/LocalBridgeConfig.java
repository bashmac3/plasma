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

    private final Path configPath;
    private final String token;

    private LocalBridgeConfig(Path configPath, String token) {
        this.configPath = configPath;
        this.token = token;
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

        String token = properties.getProperty("token", "").trim();
        if (token.isEmpty()) {
            token = generateToken();
            saveToken(configPath, token);
        }
        return new LocalBridgeConfig(configPath, token);
    }

    public String getToken() {
        return token;
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
        properties.setProperty("token", token);
        try (OutputStream output = Files.newOutputStream(configPath)) {
            properties.store(output, "Plasma local bridge config");
        }
    }
}
