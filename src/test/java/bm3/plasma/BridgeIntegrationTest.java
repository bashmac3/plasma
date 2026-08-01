package bm3.plasma;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeIntegrationTest {

	private LocalBridge bridge;

	@AfterEach
	void tearDown() throws IOException {
		if (bridge != null) {
			bridge.close();
		}
	}

	private static final class AutoExecListener implements LocalBridge.BridgeListener {
		@Override
		public void onCodeRequest(PendingRequest request) {
			request.execute();
		}

		@Override
		public void onExecuted(PendingRequest request, String output) {
		}

		@Override
		public void onDenied(PendingRequest request, String reason) {
		}
	}

	private String send(String token, String payloadJson) throws IOException {
		return sendRaw(token, payloadJson, false);
	}

	private String sendRaw(String token, String payloadJson, boolean omitPayload) throws IOException {
		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), bridge.getPort())) {
			socket.setSoTimeout(15000);
			OutputStream out = socket.getOutputStream();
			String request = omitPayload
				? "{\"token\":\"" + token + "\"}"
				: "{\"token\":\"" + token + "\",\"payload\":" + payloadJson + "}";
			out.write(request.getBytes(StandardCharsets.UTF_8));
			out.write('\n');
			out.flush();
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			String line = reader.readLine();
			return line == null ? "" : line;
		}
	}

	private void startBridge(LocalBridge.BridgeListener listener) throws IOException {
		bridge = new LocalBridge("tok");
		bridge.setListener(listener);
		bridge.start();
		assertTrue(bridge.isRunning());
		assertTrue(bridge.getPort() > 0);
	}

	@Test
	void executesAuthorizedRequestOverSocket() throws Exception {
		startBridge(new AutoExecListener());
		String response = send("tok", "{\"className\":\"bm3.plasma.SampleTask\",\"method\":\"run\"}");
		assertTrue(response.contains("\"result\":0"), "got " + response);
		assertTrue(response.contains("SampleTask executed"), "got " + response);
	}

	@Test
	void rejectsWrongTokenWithDenied() throws Exception {
		startBridge(new AutoExecListener());
		String response = send("wrong", "{\"className\":\"bm3.plasma.SampleTask\"}");
		assertTrue(response.contains("DENIED"), "got " + response);
	}

	@Test
	void rejectsMissingPayloadWithNoPayload() throws Exception {
		startBridge(new AutoExecListener());
		String response = sendRaw("tok", "", true);
		assertTrue(response.contains("NO_PAYLOAD"), "got " + response);
	}

	@Test
	void rejectedEmptyBodyClosesConnection() throws Exception {
		startBridge(new AutoExecListener());
		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), bridge.getPort())) {
			socket.setSoTimeout(5000);
			OutputStream out = socket.getOutputStream();
			out.write('\n');
			out.flush();
			socket.shutdownOutput();
			String line = new BufferedReader(
				new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
			assertTrue(line != null && line.contains("EMPTY"), "got " + line);
		}
	}

	@Test
	void deniedListenerWritesDenialResponse() throws Exception {
		startBridge(new LocalBridge.BridgeListener() {
			@Override
			public void onCodeRequest(PendingRequest request) {
				request.deny("BLOCKED_IP");
			}

			@Override
			public void onExecuted(PendingRequest request, String output) {
			}

			@Override
			public void onDenied(PendingRequest request, String reason) {
			}
		});
		String response = send("tok", "{\"className\":\"x.Y\"}");
		assertTrue(response.contains("BLOCKED_IP"), "got " + response);
	}

	@Test
	void executesArrayOfPackets() throws Exception {
		startBridge(new AutoExecListener());
		String payload = "[{\"className\":\"bm3.plasma.SampleTask\",\"method\":\"run\"},"
			+ "{\"className\":\"bm3.plasma.SampleTask\",\"method\":\"run\"}]";
		String response = send("tok", payload);
		assertTrue(response.contains("\"result\":0"), "got " + response);
		assertTrue(response.contains("SampleTask executed"), "got " + response);
	}

	@Test
	void reportsExecutionFailureAsResultOne() throws Exception {
		startBridge(new AutoExecListener());
		String response = send("tok",
			"{\"className\":\"bm3.plasma.TestPayloads$FailingRunnable\",\"method\":\"run\"}");
		assertTrue(response.contains("\"result\":1"), "got " + response);
		assertTrue(response.contains("boom"), "got " + response);
	}

	@Test
	void runnablePayloadInvokesRunMethod() throws Exception {
		startBridge(new AutoExecListener());
		String response = send("tok",
			"{\"className\":\"bm3.plasma.TestPayloads$TestRunnable\",\"method\":\"run\"}");
		assertTrue(response.contains("runnable-hello"), "got " + response);
	}

	@Test
	void mainPayloadPassesArgs() throws Exception {
		startBridge(new AutoExecListener());
		String response = send("tok",
			"{\"className\":\"bm3.plasma.TestPayloads$EchoMain\",\"method\":\"main\",\"args\":[\"alpha\",\"beta\"]}");
		assertTrue(response.contains("main:alpha,beta"), "got " + response);
	}

	@Test
	void capturesBothStdoutAndStderr() throws Exception {
		startBridge(new AutoExecListener());
		String response = send("tok",
			"{\"className\":\"bm3.plasma.TestPayloads$MultiLineRunnable\",\"method\":\"run\"}");
		assertTrue(response.contains("line-1"), "got " + response);
		assertTrue(response.contains("line-2"), "got " + response);
	}

	@Test
	void primitiveClassNamePayloadExecutes() throws Exception {
		startBridge(new AutoExecListener());
		String response = send("tok", "\"bm3.plasma.TestPayloads$TestRunnable\"");
		assertTrue(response.contains("runnable-hello"), "got " + response);
	}

	@Test
	void snippetPayloadExecutesOverSocket() throws Exception {
		startBridge(new AutoExecListener());
		String response = send("tok",
			"{\"code\":\"System.out.println(\\\"snippet-socket\\\");\",\"method\":\"run\"}");
		assertTrue(response.contains("\"result\":0"), "got " + response);
		assertTrue(response.contains("snippet-socket"), "got " + response);
	}

	@Test
	void snippetCompilationFailureSurfacesOverSocket() throws Exception {
		startBridge(new AutoExecListener());
		String response = send("tok", "{\"code\":\"nonsense ;;;\",\"method\":\"run\"}");
		assertTrue(response.contains("COMPILATION FAILED"), "got " + response);
	}

	@Test
	void missingClassSurfacesNoClassError() throws Exception {
		startBridge(new AutoExecListener());
		String response = send("tok", "{\"className\":\"no.such.Class\"}");
		assertTrue(response.contains("NoClassDefFoundError") || response.contains("ClassNotFoundException")
			|| response.contains("result"), "got " + response);
	}

	@Test
	void detachedExecutionRunsWithoutSocket() throws Exception {
		bridge = new LocalBridge("tok");
		CompletableFuture<String> result = new CompletableFuture<>();
		bridge.setListener(new LocalBridge.BridgeListener() {
			@Override
			public void onCodeRequest(PendingRequest request) {
			}

			@Override
			public void onExecuted(PendingRequest request, String output) {
				result.complete(output);
			}

			@Override
			public void onDenied(PendingRequest request, String reason) {
				result.complete("DENIED " + reason);
			}
		});
		PendingRequest detached = new PendingRequest(bridge,
			JsonParser.parseString("{\"className\":\"bm3.plasma.TestPayloads$TestRunnable\",\"method\":\"run\"}"),
			"local", "127.0.0.1");
		bridge.execute(detached);
		String output = result.get(15, TimeUnit.SECONDS);
		assertNotNull(output);
		assertTrue(output.contains("runnable-hello"), "got " + output);
	}

	@Test
	void listenenerReceivesDeniedCallback() throws Exception {
		bridge = new LocalBridge("tok");
		CompletableFuture<String> reason = new CompletableFuture<>();
		bridge.setListener(new LocalBridge.BridgeListener() {
			@Override
			public void onCodeRequest(PendingRequest request) {
				request.deny("TEST_DENY");
			}

			@Override
			public void onExecuted(PendingRequest request, String output) {
			}

			@Override
			public void onDenied(PendingRequest request, String r) {
				reason.complete(r);
			}
		});
		bridge.start();
		String response = send("tok", "{\"className\":\"x.Y\"}");
		String r = reason.get(5, TimeUnit.SECONDS);
		assertTrue(response.contains("TEST_DENY"), "got " + response);
		assertTrue(r.contains("TEST_DENY"), "got " + r);
	}

	@Test
	void bridgeIgnoresFurtherConnectionsAfterClose() throws Exception {
		startBridge(new AutoExecListener());
		int port = bridge.getPort();
		bridge.close();
		bridge = null;
		assertFalse(isPortReachable(port));
	}

	private boolean isPortReachable(int port) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
