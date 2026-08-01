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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LocalBridge implements AutoCloseable {
	public interface BridgeListener {
		void onCodeRequest(PendingRequest request);

		void onExecuted(PendingRequest request, String output);

		void onDenied(PendingRequest request, String reason);
	}

	static final int MAX_REQUEST_CHARS = 1_000_000;
	private static final long DEFAULT_TIMEOUT_MILLIS = 60_000;
	private static final int DEFAULT_MAX_ATTEMPTS = 5;

	private volatile String expectedToken;
	private final long executionTimeoutMillis;
	private final int maxAttempts;
	private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
		Thread thread = new Thread(r);
		thread.setDaemon(true);
		return thread;
	});
	private final ScheduledExecutorService watchdog = Executors.newScheduledThreadPool(1, r -> {
		Thread thread = new Thread(r, "plasma-watchdog");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicInteger failedAttempts = new AtomicInteger();
	private final AtomicBoolean locked = new AtomicBoolean();
	private final Random random = new Random();
	private volatile ServerSocket serverSocket;
	private volatile boolean running;
	private volatile BridgeListener listener;

	public LocalBridge(String expectedToken) {
		this(expectedToken, DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_ATTEMPTS);
	}

	public LocalBridge(String expectedToken, long executionTimeoutMillis, int maxAttempts) {
		this.expectedToken = normalizeToken(expectedToken);
		this.executionTimeoutMillis = Math.max(0, executionTimeoutMillis);
		this.maxAttempts = Math.max(0, maxAttempts);
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
		return constantTimeEquals(normalizeToken(token), expectedToken);
	}

	public boolean isRunning() {
		return running;
	}

	public boolean isLocked() {
		return locked.get();
	}

	public int getFailedAttempts() {
		return failedAttempts.get();
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public long getExecutionTimeoutMillis() {
		return executionTimeoutMillis;
	}

	public boolean unlock() {
		boolean wasLocked = locked.getAndSet(false);
		failedAttempts.set(0);
		return wasLocked;
	}

	public void start() throws IOException {
		if (running) {
			return;
		}
		if (expectedToken.isEmpty()) {
			throw new IOException("Cannot open the bridge with an empty token");
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
			String request = readRequest(input);
			if (request == null || request.isBlank()) {
				respondAndClose(client, output, 1, "EMPTY");
				return;
			}
			if (request.length() > MAX_REQUEST_CHARS) {
				respondAndClose(client, output, 1, "TOO_LARGE");
				return;
			}
			if (isLocked()) {
				respondAndClose(client, output, 1, "LOCKED");
				return;
			}

			Request parsed;
			try {
				parsed = parseRequest(request);
			} catch (Exception e) {
				respondAndClose(client, output, 1, "BAD_REQUEST");
				return;
			}
			if (!isAuthorized(parsed.token())) {
				registerFailure();
				respondAndClose(client, output, 1, "DENIED");
				return;
			}
			resetFailures();
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

	private String readRequest(BufferedReader input) throws IOException {
		StringBuilder builder = new StringBuilder();
		int c;
		while (true) {
			c = input.read();
			if (c == -1 || c == '\n') {
				break;
			}
			builder.append((char) c);
			if (builder.length() > MAX_REQUEST_CHARS) {
				break;
			}
		}
		return builder.toString();
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
			Thread worker = Thread.currentThread();
			AtomicBoolean timedOut = new AtomicBoolean(false);
			ScheduledFuture<?> kill = null;
			if (executionTimeoutMillis > 0) {
				kill = watchdog.schedule(() -> {
					timedOut.set(true);
					worker.interrupt();
				}, executionTimeoutMillis, TimeUnit.MILLISECONDS);
			}
			int result = 0;
			String output;
			try {
				output = runPayload(pending.getPayload(), pending.getOutputStream());
				if (timedOut.get()) {
					result = 1;
					output = "TIMEOUT";
				}
			} catch (Exception e) {
				result = 1;
				output = timedOut.get()
					? "TIMEOUT " + e.getClass().getSimpleName()
					: "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
			}
			if (kill != null) {
				kill.cancel(false);
			}
			if (result != 0) {
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
		CaptureSink sink = new CaptureSink();
		CaptureSink previous = ACTIVE_SINK.get();
		ACTIVE_SINK.set(sink);
		try {
			if (payload.isJsonArray()) {
				JsonArray array = payload.getAsJsonArray();
				for (JsonElement packet : array) {
					sink.resetPacket();
					executePacket(packet, responseOutput, sink);
				}
			} else {
				sink.resetPacket();
				executePacket(payload, responseOutput, sink);
			}
			return sink.globalText();
		} finally {
			ACTIVE_SINK.set(previous);
		}
	}

	private void executePacket(JsonElement packet, OutputStream output, CaptureSink sink) throws Exception {
		String packetId = null;
		String maxPacketId = null;

		if (packet.isJsonPrimitive()) {
			String className = packet.getAsString();
			runClass(className, "run", new String[0]);
			writeJsonResponse(output, 0, sink.packetText(), null, null);
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
			writeJsonResponse(output, 0, sink.packetText(), packetId, maxPacketId);
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
		writeJsonResponse(output, 0, sink.packetText(), packetId, maxPacketId);
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

	private void registerFailure() {
		int attempts = failedAttempts.incrementAndGet();
		if (maxAttempts > 0 && attempts >= maxAttempts) {
			locked.set(true);
		}
	}

	private void resetFailures() {
		failedAttempts.set(0);
	}

	@Override
	public void close() throws IOException {
		running = false;
		executor.shutdownNow();
		watchdog.shutdownNow();
		if (serverSocket != null) {
			serverSocket.close();
		}
	}

	public static String normalizeToken(String token) {
		if (token == null) {
			return "";
		}
		return token.trim();
	}

	private static boolean constantTimeEquals(String a, String b) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] left = digest.digest(a.getBytes(StandardCharsets.UTF_8));
			byte[] right = digest.digest(b.getBytes(StandardCharsets.UTF_8));
			return MessageDigest.isEqual(left, right);
		} catch (NoSuchAlgorithmException e) {
			return a.equals(b);
		}
	}

	private static final ThreadLocal<CaptureSink> ACTIVE_SINK = new ThreadLocal<>();

	private static final class CaptureSink {
		private final ByteArrayOutputStream global = new ByteArrayOutputStream();
		private final ByteArrayOutputStream packet = new ByteArrayOutputStream();

		void writeGlobal(int b) {
			global.write(b);
			packet.write(b);
		}

		void writeGlobal(byte[] b, int off, int len) {
			global.write(b, off, len);
			packet.write(b, off, len);
		}

		void resetPacket() {
			packet.reset();
		}

		String globalText() {
			return global.toString(StandardCharsets.UTF_8);
		}

		String packetText() {
			return packet.toString(StandardCharsets.UTF_8);
		}
	}

	private static final class CapturingOutputStream extends OutputStream {
		private final OutputStream fallback;

		CapturingOutputStream(OutputStream fallback) {
			this.fallback = fallback;
		}

		@Override
		public void write(int b) throws IOException {
			CaptureSink sink = ACTIVE_SINK.get();
			if (sink != null) {
				sink.writeGlobal(b);
			} else {
				fallback.write(b);
			}
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			CaptureSink sink = ACTIVE_SINK.get();
			if (sink != null) {
				sink.writeGlobal(b, off, len);
			} else {
				fallback.write(b, off, len);
			}
		}
	}

	static {
		System.setOut(new PrintStream(new CapturingOutputStream(System.out), true, StandardCharsets.UTF_8));
		System.setErr(new PrintStream(new CapturingOutputStream(System.err), true, StandardCharsets.UTF_8));
	}

	public record Request(String token, JsonElement payload) {
	}
}
