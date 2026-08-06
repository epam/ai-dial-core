package com.epam.aidial.core.server.service;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ResponsesApiClientTest {

    @Test
    public void testParseTerminalBodyExtractsCompletedAt() {
        ResponsesApiClient.TerminalResult result = parse("{\"status\":\"completed\",\"completed_at\":1712345678}");

        assertNotNull(result);
        assertEquals(1712345678000L, result.completedAtMs());
    }

    @Test
    public void testParseTerminalBodyWithoutCompletedAt() {
        ResponsesApiClient.TerminalResult result = parse("{\"status\":\"completed\"}");

        assertNotNull(result);
        assertNull(result.completedAtMs());
    }

    @Test
    public void testParseTerminalBodyIgnoresNonNumericCompletedAt() {
        ResponsesApiClient.TerminalResult result = parse("{\"status\":\"failed\",\"completed_at\":\"2024-04-05T00:00:00Z\"}");

        assertNotNull(result);
        assertNull(result.completedAtMs());
    }

    @Test
    public void testParseTerminalBodyReturnsNullForNonTerminalStatus() {
        assertNull(parse("{\"status\":\"in_progress\",\"completed_at\":1712345678}"));
    }

    private static ResponsesApiClient.TerminalResult parse(String body) {
        return ResponsesApiClient.parseTerminalBody(Buffer.buffer(body));
    }
}
