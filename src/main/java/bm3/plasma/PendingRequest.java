package bm3.plasma;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class PendingRequest {
	private static final OutputStream NOOP = OutputStream.nullOutputStream();

	private final LocalBridge bridge;
	private final Socket socket;
	private final OutputStream output;
	private final JsonElement payload;
	private final String source;
	private final String sourceIp;
	private final String ruleKey;
	private final String description;
	private final int packetCount;
	private final String payloadHash;
	private volatile boolean resolved;

	PendingRequest(LocalBridge bridge, Socket socket, OutputStream output, JsonElement payload) {
		this.bridge = bridge;
		this.socket = socket;
		this.output = output;
		this.payload = payload;

		InetAddress address = socket.getInetAddress();
		this.sourceIp = address == null ? "unknown" : address.getHostAddress();
		this.source = this.sourceIp + ":" + socket.getPort();
		this.payloadHash = hashOf(payload);

		PayloadInfo info = PayloadInfo.of(payload);
		this.ruleKey = info.ruleKey();
		this.description = info.description();
		this.packetCount = info.packetCount();
	}

	public PendingRequest(LocalBridge bridge, JsonElement payload, String source, String sourceIp) {
		this.bridge = bridge;
		this.socket = null;
		this.output = null;
		this.payload = payload;
		this.source = source;
		this.sourceIp = sourceIp;
		this.payloadHash = hashOf(payload);

		PayloadInfo info = PayloadInfo.of(payload);
		this.ruleKey = info.ruleKey();
		this.description = info.description();
		this.packetCount = info.packetCount();
	}

	public String getSource() {
		return source;
	}

	public String getSourceIp() {
		return sourceIp;
	}

	public String getRuleKey() {
		return ruleKey;
	}

	public String describe() {
		return description;
	}

	public int getPacketCount() {
		return packetCount;
	}

	public String getPayloadHash() {
		return payloadHash;
	}

	public JsonElement getPayload() {
		return payload;
	}

	public boolean isResolved() {
		return resolved;
	}

	public void execute() {
		bridge.execute(this);
	}

	public void deny(String reason) {
		bridge.deny(this, reason);
	}

	OutputStream getOutputStream() {
		return output == null ? NOOP : output;
	}

	void close() {
		resolved = true;
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static String getString(JsonObject object, String key) {
		if (object.has(key) && object.get(key).isJsonPrimitive()) {
			return object.get(key).getAsString();
		}
		return null;
	}

	private static String truncate(String text, int max) {
		if (text == null || text.length() <= max) {
			return text;
		}
		return text.substring(0, max) + "...";
	}

	private static String formatArgs(JsonObject object) {
		if (!object.has("args") || !object.get("args").isJsonArray()) {
			return "";
		}
		JsonArray array = object.getAsJsonArray("args");
		List<String> parts = new ArrayList<>();
		for (JsonElement element : array) {
			parts.add(element.isJsonPrimitive() ? element.getAsString() : element.toString());
		}
		return String.join(", ", parts);
	}

	private static String hashOf(JsonElement payload) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(payload.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte b : bytes) {
				builder.append(String.format("%02x", b));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(payload.toString().hashCode());
		}
	}

	private record PayloadInfo(String ruleKey, String description, int packetCount) {
		static PayloadInfo of(JsonElement payload) {
			JsonElement primary = payload;
			int count = 1;
			if (payload.isJsonArray()) {
				JsonArray array = payload.getAsJsonArray();
				count = array.size();
				if (!array.isEmpty()) {
					primary = array.get(0);
				}
			}

			String className = "?";
			String method = "?";
			String argsText = "";
			if (primary.isJsonObject()) {
				JsonObject object = primary.getAsJsonObject();
				String code = SnippetEvaluator.extractCode(object);
				if (code != null) {
					String methodValue = getString(object, "method");
					method = methodValue == null ? "run" : methodValue;
					className = "snippet";
					argsText = truncate(code, 80);
					String ruleKey = "snippet." + method + "." + Integer.toHexString(code.hashCode());
					return new PayloadInfo(ruleKey, "snippet." + method + "(): " + argsText, count);
				}
				String classNameValue = getString(object, "className");
				if (classNameValue == null) {
					classNameValue = getString(object, "class");
				}
				if (classNameValue != null) {
					className = classNameValue;
				}
				String methodValue2 = getString(object, "method");
				if (methodValue2 != null) {
					method = methodValue2;
				}
				argsText = formatArgs(object);
			} else if (primary.isJsonPrimitive()) {
				className = primary.getAsString();
			}

			String ruleKey = className + "." + method;
			return new PayloadInfo(ruleKey,
				ruleKey + "(" + argsText + ")" + (count > 1 ? " x" + count + " packets" : ""), count);
		}
	}
}
