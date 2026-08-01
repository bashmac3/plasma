package bm3.plasma;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalBridgeTest {

	@Test
	void authorizeMatchesTokenCaseInsensitively() {
		LocalBridge bridge = new LocalBridge("Secret123");
		assertTrue(bridge.isAuthorized("SECRET123"));
		assertFalse(bridge.isAuthorized("DifferentToken"));
	}

	@Test
	void normalizeTokenLowercasesAndTrims() {
		assertEquals("abc", LocalBridge.normalizeToken("  AbC  "));
		assertEquals("", LocalBridge.normalizeToken(null));
		assertEquals("", LocalBridge.normalizeToken("   "));
		assertEquals("", LocalBridge.normalizeToken(""));
	}

	@Test
	void isAuthorizedIsCaseInsensitive() {
		LocalBridge bridge = new LocalBridge("ToK");
		assertTrue(bridge.isAuthorized("tOk"));
	}

	@Test
	void setTokenRotatesExpectedValue() {
		LocalBridge bridge = new LocalBridge("old");
		bridge.setToken("newToken");
		assertTrue(bridge.isAuthorized("newToken"));
		assertFalse(bridge.isAuthorized("old"));
	}

	@Test
	void getTokenReturnsCurrentValue() {
		LocalBridge bridge = new LocalBridge("abc");
		assertEquals("abc", bridge.getToken());
		bridge.setToken("xyz");
		assertEquals("xyz", bridge.getToken());
	}

	@Test
	void bridgeStartsClosedAndReportsNotRunning() {
		LocalBridge bridge = new LocalBridge("tok");
		assertFalse(bridge.isRunning());
		assertEquals(-1, bridge.getPort());
	}

	@Test
	void parseRequestExtractsTokenAndPayload() {
		LocalBridge.Request r = LocalBridge.parseRequest("{\"token\":\"t\",\"payload\":{\"className\":\"x.Y\"}}");
		assertEquals("t", r.token());
		assertEquals("x.Y", r.payload().getAsJsonObject().get("className").getAsString());
	}

	@Test
	void parseRequestMissingPayloadYieldsNull() {
		LocalBridge.Request r = LocalBridge.parseRequest("{\"token\":\"t\"}");
		assertNull(r.payload());
	}

	@Test
	void parseRequestMissingTokenYieldsEmpty() {
		LocalBridge.Request r = LocalBridge.parseRequest("{\"payload\":{}}");
		assertEquals("", r.token());
	}

	@Test
	void parseRequestRejectsMalformedJson() {
		assertThrows(Exception.class, () -> LocalBridge.parseRequest("not json"));
	}

	@Test
	void parseRequestRejectsNonObjectJson() {
		assertThrows(Exception.class, () -> LocalBridge.parseRequest("[\"a\"]"));
	}
}
