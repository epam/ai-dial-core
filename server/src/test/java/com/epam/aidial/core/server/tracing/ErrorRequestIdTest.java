package com.epam.aidial.core.server.tracing;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErrorRequestIdTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String OTHER_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SUFFIX = " (DIAL request id: " + TRACE_ID + ")";
    private static final TracingSettings ON = new TracingSettings(false, false, true, List.of());
    private static final TracingSettings OFF = new TracingSettings(false, false, false, List.of());

    private MultiMap headers;
    private HttpServerResponse response;

    @BeforeEach
    void setUp() {
        headers = MultiMap.caseInsensitiveMultiMap();
        response = mock(HttpServerResponse.class);
        when(response.headers()).thenReturn(headers);
    }

    @Test
    void testJsonMessageGetsSuffixAndOtherMembersAreKept() {
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json");

        Buffer result = append(400, "{\"error\":{\"message\":\"bad\",\"code\":\"invalid_value\"},\"param\":\"temperature\"}");

        assertEquals("{\"error\":{\"message\":\"bad" + SUFFIX + "\",\"code\":\"invalid_value\"},\"param\":\"temperature\"}",
                result.toString());
        assertEquals(TRACE_ID, headers.get("X-Request-ID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json; charset=utf-8", "application/problem+json", "APPLICATION/JSON"})
    void testJsonMediaTypes(String contentType) {
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);

        Buffer result = append(422, "{\"error\":{\"message\":\"bad\"}}");

        assertEquals("{\"error\":{\"message\":\"bad" + SUFFIX + "\"}}", result.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/plain; charset=utf-8"})
    void testPlainTextDropsTrailingWhitespace(String contentType) {
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);

        Buffer result = append(403, "Access denied\n");

        assertEquals("Access denied" + SUFFIX, result.toString());
        assertEquals(TRACE_ID, headers.get("X-Request-ID"));
    }

    @Test
    void testNoContentTypeWithJsonBody() {
        Buffer result = append(502, "{\"error\":{\"message\":\"Failed\",\"display_message\":\"Failed\"}}");

        assertEquals("{\"error\":{\"message\":\"Failed" + SUFFIX + "\",\"display_message\":\"Failed\"}}", result.toString());
    }

    @Test
    void testNoContentTypeWithTextBody() {
        Buffer result = append(404, "Unknown deployment: missing");

        assertEquals("Unknown deployment: missing" + SUFFIX, result.toString());
    }

    @Test
    void testPlainTextStartingWithBraceIsText() {
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain");

        Buffer result = append(400, "{\"error\":{\"message\":\"bad\"}}");

        assertEquals("{\"error\":{\"message\":\"bad\"}}" + SUFFIX, result.toString());
    }

    @Test
    void testIdentityEncodingIsChanged() {
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain");
        headers.set(HttpHeaders.CONTENT_ENCODING, "identity");

        Buffer result = append(400, "bad");

        assertEquals("bad" + SUFFIX, result.toString());
    }

    @Test
    void testHtmlUnchanged() {
        headers.set(HttpHeaders.CONTENT_TYPE, "text/html");
        Buffer body = Buffer.buffer("<html>bad</html>");

        assertSame(body, append(400, body));
        assertEquals(TRACE_ID, headers.get("X-Request-ID"));
    }

    @Test
    void testUndecodedEncodingUnchanged() {
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain");
        headers.set(HttpHeaders.CONTENT_ENCODING, "x-custom");
        Buffer body = Buffer.buffer("bad");

        assertSame(body, append(400, body));
        assertEquals(TRACE_ID, headers.get("X-Request-ID"));
    }

    @Test
    void testBodyOverLimitUnchanged() {
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain");
        Buffer body = Buffer.buffer("x".repeat(64 * 1024 + 1));

        assertSame(body, append(400, body));
    }

    @Test
    void testBodyAtLimitChanged() {
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain");

        Buffer result = append(400, "x".repeat(64 * 1024));

        assertTrue(result.toString().endsWith("x" + SUFFIX));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"detail\":\"x\"}", "{\"error\":{\"message\":42}}", "{\"error\":\"bad\"}", "[1]", "{not json"})
    void testJsonWithoutStringMessageUnchanged(String json) {
        headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
        Buffer body = Buffer.buffer(json);

        assertSame(body, append(422, body));
        assertEquals(TRACE_ID, headers.get("X-Request-ID"));
    }

    @Test
    void testEmptyBodyGetsHeaderOnly() {
        Buffer body = Buffer.buffer();

        assertSame(body, append(403, body));
        assertEquals(TRACE_ID, headers.get("X-Request-ID"));
    }

    @Test
    void testOriginRequestIdKept() {
        headers.set("x-request-id", "up-1");

        Buffer result = append(400, "bad");

        assertEquals("bad" + SUFFIX, result.toString());
        assertEquals("up-1", headers.get("X-Request-ID"));
    }

    @Test
    void testBelow400Untouched() {
        Buffer body = Buffer.buffer("not found yet");

        assertSame(body, append(399, body));
        assertNull(headers.get("X-Request-ID"));
    }

    @Test
    void testEventStreamUntouched() {
        headers.set(HttpHeaders.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        Buffer body = Buffer.buffer("data: {\"error\":{\"message\":\"bad\"}}\n\n");

        assertSame(body, append(400, body));
        assertNull(headers.get("X-Request-ID"));
    }

    @Test
    void testSameIdAddedOnce() {
        Buffer body = Buffer.buffer("{\"error\":{\"message\":\"bad" + SUFFIX + "\"}}");

        assertSame(body, append(400, body));
    }

    @Test
    void testOtherIdGetsThisIdToo() {
        String other = " (DIAL request id: " + OTHER_TRACE_ID + ")";

        Buffer result = append(400, "bad" + other);

        assertEquals("bad" + other + SUFFIX, result.toString());
    }

    @Test
    void testSettingOffUntouched() {
        Buffer body = Buffer.buffer("bad");

        assertSame(body, ErrorRequestId.append(OFF, TRACE_ID, 400, response, body));
        assertNull(headers.get("X-Request-ID"));
        assertFalse(ErrorRequestId.isEnabled(OFF, TRACE_ID));
    }

    @Test
    void testNullSettingsUntouched() {
        Buffer body = Buffer.buffer("bad");

        assertSame(body, ErrorRequestId.append(null, TRACE_ID, 400, response, body));
        assertNull(headers.get("X-Request-ID"));
        assertFalse(ErrorRequestId.isEnabled(null, TRACE_ID));
    }

    @Test
    void testInvalidTraceIdUntouched() {
        Buffer body = Buffer.buffer("bad");
        String invalid = "00000000000000000000000000000000";

        assertSame(body, ErrorRequestId.append(ON, invalid, 400, response, body));
        assertSame(body, ErrorRequestId.append(ON, null, 400, response, body));
        assertNull(headers.get("X-Request-ID"));
        assertFalse(ErrorRequestId.isEnabled(ON, invalid));
        assertTrue(ErrorRequestId.isEnabled(ON, TRACE_ID));
    }

    private Buffer append(int status, String body) {
        return append(status, Buffer.buffer(body));
    }

    private Buffer append(int status, Buffer body) {
        return ErrorRequestId.append(ON, TRACE_ID, status, response, body);
    }
}
