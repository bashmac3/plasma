package bm3.plasma;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingRequestTest {

	private static PendingRequest request(String json) {
		return new PendingRequest(null, JsonParser.parseString(json), "test-source", "127.0.0.1");
	}

	@Test
	void describesClassMethodAndArgs() {
		PendingRequest r = request("{\"className\":\"bm3.plasma.SampleTask\",\"method\":\"run\",\"args\":[\"a\",\"b\"]}");
		assertEquals("bm3.plasma.SampleTask.run(a, b)", r.describe());
		assertEquals("bm3.plasma.SampleTask.run", r.getRuleKey());
		assertEquals(1, r.getPacketCount());
	}

	@Test
	void defaultsMethodToMain() {
		PendingRequest r = request("{\"className\":\"bm3.plasma.SampleTask\"}");
		assertEquals("bm3.plasma.SampleTask.main", r.getRuleKey());
	}

	@Test
	void supportsClassAliasKey() {
		PendingRequest r = request("{\"class\":\"bm3.plasma.SampleTask\",\"method\":\"run\"}");
		assertEquals("bm3.plasma.SampleTask.run", r.getRuleKey());
	}

	@Test
	void describesArrayPacketsWithCount() {
		PendingRequest r = request("[{\"className\":\"a.B\",\"method\":\"run\"},{\"className\":\"c.D\",\"method\":\"run\"},{\"className\":\"e.F\",\"method\":\"run\"}]");
		assertEquals(3, r.getPacketCount());
		assertTrue(r.describe().contains(" x3 packets"));
	}

	@Test
	void emptyArrayHasZeroPackets() {
		PendingRequest r = request("[]");
		assertEquals(0, r.getPacketCount());
	}

	@Test
	void describesPrimitiveClass() {
		PendingRequest r = request("\"bm3.plasma.SampleTask\"");
		assertEquals("bm3.plasma.SampleTask.run", r.getRuleKey());
	}

	@Test
	void describesSnippetWithRuleKey() {
		PendingRequest r = request("{\"code\":\"System.out.println(1);\",\"method\":\"run\"}");
		assertTrue(r.describe().startsWith("snippet.run(): "));
		assertTrue(r.getRuleKey().startsWith("snippet.run."));
	}

	@Test
	void snippetDescriptionTruncatesLongCode() {
		String longCode = "int x = " + "1 + ".repeat(50) + "1;";
		PendingRequest r = request("{\"code\":\"" + longCode + "\",\"method\":\"run\"}");
		assertTrue(r.describe().length() < longCode.length() + 40);
	}

	@Test
	void payloadHashIsDeterministicSha256() {
		PendingRequest a = request("{\"className\":\"x.Y\",\"method\":\"run\",\"args\":[\"z\"]}");
		PendingRequest b = request("{\"className\":\"x.Y\",\"method\":\"run\",\"args\":[\"z\"]}");
		assertEquals(a.getPayloadHash(), b.getPayloadHash());
		assertTrue(a.getPayloadHash().matches("[0-9a-f]{64}"));
	}

	@Test
	void payloadHashDiffersForDifferentPayloads() {
		assertNotEquals(
			request("{\"className\":\"x.Y\"}").getPayloadHash(),
			request("{\"className\":\"x.Z\"}").getPayloadHash());
	}

	@Test
	void payloadHashDiffersWhenArgsChange() {
		assertNotEquals(
			request("{\"className\":\"x.Y\",\"args\":[\"a\"]}").getPayloadHash(),
			request("{\"className\":\"x.Y\",\"args\":[\"b\"]}").getPayloadHash());
	}

	@Test
	void payloadHashDiffersForArrayOrder() {
		String first = "{\"className\":\"a.A\",\"method\":\"run\"}";
		String second = "{\"className\":\"b.B\",\"method\":\"run\"}";
		assertNotEquals(
			request("[" + first + "," + second + "]").getPayloadHash(),
			request("[" + second + "," + first + "]").getPayloadHash());
	}

	@Test
	void detachedRequestReportsSourceFields() {
		PendingRequest r = request("{\"className\":\"x.Y\"}");
		assertEquals("test-source", r.getSource());
		assertEquals("127.0.0.1", r.getSourceIp());
		assertFalse(r.isResolved());
	}

	@Test
	void closeMarksRequestResolved() {
		PendingRequest r = request("{\"className\":\"x.Y\"}");
		assertFalse(r.isResolved());
		r.close();
		assertTrue(r.isResolved());
	}

	@Test
	void unknownClassIsDescribed() {
		PendingRequest r = request("{\"className\":\"no.such.Class\",\"method\":\"run\",\"args\":[\"q\"]}");
		assertEquals("no.such.Class.run(q)", r.describe());
	}

	@Test
	void nonObjectPrimitivePacketsAreCounted() {
		PendingRequest r = request("[\"a.Class\",\"b.Class\"]");
		assertEquals(2, r.getPacketCount());
	}
}
