package bm3.plasma;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalBridge implements AutoCloseable {
	public interface BridgeListener {
		void onCodeRequest(PendingRequest request);

		void onExecuted(PendingRequest request, String output);

		void onDenied(PendingRequest request, String reason);
	}

	private volatile String expectedToken;
	private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
		Thread thread = new Thread(r);
		thread.setDaemon(true);
		return thread;
	});
	private final Random random = new Random();
	private volatile ServerSocket serverSocket;
	private volatile boolean running;
	private volatile BridgeListener listener;

	public LocalBridge(String expectedToken) {
		this.expectedToken = normalizeToken(expectedToken);
	}

	public void setListener(BridgeListener listener) {
		this.listener = listener;
	}

	public void setToken(String token) {
		this.expectedToken = normalizeToken(token);
	}

	public String getToken() {
		return expectedToken;
	}

	public boolean isAuthorized(String token) {
		return normalizeToken(token).equals(expectedToken);
	}

	public boolean isRunning() {
		return running;
	}

	public void start() throws IOException {
		if (running) {
			return;
		}

		List<Integer> ports = new ArrayList<>();
		for (int port = 30000; port <= 50000; port++) {
			ports.add(port);
		}
		Collections.shuffle(ports, random);

		for (int port : ports) {
			try {
				ServerSocket socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
				this.serverSocket = socket;
				this.running = true;
				executor.submit(() -> serve(socket));
				return;
			} catch (IOException ignored) {
				// Try the next port.
			}
		}

		throw new IOException("Unable to bind any localhost port in range 30000-50000");
	}

	public int getPort() {
		return serverSocket == null ? -1 : serverSocket.getLocalPort();
	}

	private void serve(ServerSocket socket) {
		while (running) {
			try {
				Socket client = socket.accept();
				client.setSoTimeout(5000);
				executor.submit(() -> handleClient(client));
			} catch (IOException e) {
				if (running) {
					throw new RuntimeException(e);
				}
			}
		}
	}

	private void handleClient(Socket client) {
		try {
			BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
			OutputStream output = client.getOutputStream();
			String request = input.readLine();
			if (request == null || request.isBlank()) {
				respondAndClose(client, output, 1, "EMPTY");
				return;
			}

			Request parsed = parseRequest(request);
			if (!isAuthorized(parsed.token())) {
				respondAndClose(client, output, 1, "DENIED");
				return;
			}
			if (parsed.payload() == null || parsed.payload().isJsonNull()) {
				respondAndClose(client, output, 1, "NO_PAYLOAD");
				return;
			}

			PendingRequest pending = new PendingRequest(this, client, output, parsed.payload());
			BridgeListener currentListener = listener;
			if (currentListener != null) {
				currentListener.onCodeRequest(pending);
			} else {
				pending.execute();
			}
		} catch (Exception e) {
			try {
				client.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void respondAndClose(Socket client, OutputStream output, int result, String text) {
		try {
			writeJsonResponse(output, result, text);
			output.flush();
		} catch (IOException ignored) {
		}
		try {
			client.close();
		} catch (IOException ignored) {
		}
	}

	public void execute(PendingRequest pending) {
		executor.submit(() -> {
			int result = 0;
			String output;
			try {
				output = runPayload(pending.getPayload(), pending.getOutputStream());
			} catch (Exception e) {
				result = 1;
				output = "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
				try {
					writeJsonResponse(pending.getOutputStream(), result, output);
				} catch (IOException ignored) {
				}
			}
			try {
				pending.getOutputStream().flush();
			} catch (IOException ignored) {
			}
			pending.close();

			BridgeListener currentListener = listener;
			if (currentListener != null) {
				if (result == 0) {
					currentListener.onExecuted(pending, output);
				} else {
					currentListener.onDenied(pending, output);
				}
			}
		});
	}

	void deny(PendingRequest pending, String reason) {
		String text = "DENIED" + (reason == null ? "" : " " + reason);
		try {
			writeJsonResponse(pending.getOutputStream(), 1, text);
			pending.getOutputStream().flush();
		} catch (IOException ignored) {
		}
		pending.close();

		BridgeListener currentListener = listener;
		if (currentListener != null) {
			currentListener.onDenied(pending, text);
		}
	}

	private String runPayload(JsonElement payload, OutputStream responseOutput) throws Exception {
		ByteArrayOutputStream global = new ByteArrayOutputStream();
		ByteArrayOutputStream packetSink = new ByteArrayOutputStream();
		PrintStream capture = new PrintStream(new TeeOutputStream(global, packetSink), true, StandardCharsets.UTF_8);

		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		try {
			System.setOut(capture);
			System.setErr(capture);
			if (payload.isJsonArray()) {
				JsonArray array = payload.getAsJsonArray();
				for (JsonElement packet : array) {
					packetSink.reset();
					executePacket(packet, responseOutput, packetSink);
				}
			} else {
				packetSink.reset();
				executePacket(payload, responseOutput, packetSink);
			}
			return global.toString(StandardCharsets.UTF_8);
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
	}

	private void executePacket(JsonElement packet, OutputStream output, ByteArrayOutputStream packetSink) throws Exception {
		String packetId = null;
		String maxPacketId = null;

		if (packet.isJsonPrimitive()) {
			String className = packet.getAsString();
			runClass(className, "run", new String[0]);
			writeJsonResponse(output, 0, packetSink.toString(StandardCharsets.UTF_8), null, null);
			return;
		}

		if (!packet.isJsonObject()) {
			throw new IllegalArgumentException("INVALID_PACKET");
		}

		JsonObject object = packet.getAsJsonObject();
		packetId = getString(object, "packetid");
		maxPacketId = getString(object, "maxpacketid");

		String code = SnippetEvaluator.extractCode(object);
		if (code != null) {
			String methodValue = getString(object, "method");
			final String snippetMethod = methodValue == null ? "run" : methodValue;
			SnippetEvaluator.evaluate(code, snippetMethod);
			writeJsonResponse(output, 0, packetSink.toString(StandardCharsets.UTF_8), packetId, maxPacketId);
			return;
		}

		String className = getString(object, "className", "class");
		String methodValue = getString(object, "method");
		final String method = methodValue == null ? "main" : methodValue;
		String[] args = parseArgs(object);

		if (className == null || className.isBlank()) {
			writeJsonResponse(output, 1, "NO_CLASS", packetId, maxPacketId);
			return;
		}

		runClass(className, method, args);
		writeJsonResponse(output, 0, packetSink.toString(StandardCharsets.UTF_8), packetId, maxPacketId);
	}

	private String[] parseArgs(JsonObject object) {
		if (!object.has("args") || !object.get("args").isJsonArray()) {
			return new String[0];
		}
		JsonArray argsArray = object.getAsJsonArray("args");
		String[] args = new String[argsArray.size()];
		for (int i = 0; i < argsArray.size(); i++) {
			args[i] = argsArray.get(i).getAsString();
		}
		return args;
	}

	private static final class TeeOutputStream extends OutputStream {
		private final ByteArrayOutputStream global;
		private final ByteArrayOutputStream packet;

		TeeOutputStream(ByteArrayOutputStream global, ByteArrayOutputStream packet) {
			this.global = global;
			this.packet = packet;
		}

		@Override
		public void write(int b) {
			global.write(b);
			packet.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) {
			global.write(b, off, len);
			packet.write(b, off, len);
		}
	}

	private void runClass(String className, String method, String[] args) throws Exception {
		Class<?> target = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
		if (Runnable.class.isAssignableFrom(target)) {
			((Runnable) target.getDeclaredConstructor().newInstance()).run();
			return;
		}
		Method main = target.getMethod("main", String[].class);
		main.invoke(null, (Object) args);
	}

	public static Request parseRequest(String rawRequest) {
		JsonObject object = JsonParser.parseString(rawRequest).getAsJsonObject();
		String token = object.has("token") ? object.get("token").getAsString() : "";
		JsonElement payload = object.has("payload") ? object.get("payload") : null;
		return new Request(token, payload);
	}

	private String getString(JsonObject object, String... keys) {
		for (String key : keys) {
			if (object.has(key) && object.get(key) != null && !object.get(key).isJsonNull()) {
				return object.get(key).getAsString();
			}
		}
		return null;
	}

	private void writeJsonResponse(OutputStream output, int result, String text) throws IOException {
		writeJsonResponse(output, result, text, null, null);
	}

	private void writeJsonResponse(OutputStream output, int result, String text, String packetId, String maxPacketId) throws IOException {
		JsonObject response = new JsonObject();
		response.addProperty("result", result);
		if (packetId != null) {
			response.addProperty("packetid", packetId);
		}
		if (maxPacketId != null) {
			response.addProperty("maxpacketid", maxPacketId);
		}
		response.addProperty("text", text == null ? "" : text);
		output.write(response.toString().getBytes(StandardCharsets.UTF_8));
		output.write('\n');
	}

	@Override
	public void close() throws IOException {
		running = false;
		executor.shutdownNow();
		if (serverSocket != null) {
			serverSocket.close();
		}
	}

	public static String normalizeToken(String token) {
		if (token == null) {
			return "";
		}
		String trimmed = token.trim();
		if (trimmed.isEmpty()) {
			return "";
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}

	public record Request(String token, JsonElement payload) {
	}
}
