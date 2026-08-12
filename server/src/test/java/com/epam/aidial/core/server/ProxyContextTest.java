package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

public class ProxyContextTest {

    @Test
    public void testOperationDuration() {
        ProxyContext context = context(null);
        context.setRequestTimestamp(1000L);
        context.setResponseBodyTimestamp(2500L);

        assertEquals(1500, context.calculateOperationDurationMs());
    }

    @Test
    public void testOperationDurationWhenResponseBodyTimestampNotSet() {
        // e.g. a deployment without an endpoint, which responds without ever producing a response body
        ProxyContext context = context(null);
        context.setResponseBodyTimestamp(0L);

        long duration = context.calculateOperationDurationMs();
        assertTrue(duration >= 0 && duration < 60_000, "Unexpected duration: " + duration);
    }

    @Test
    public void testOperationDurationIsNeverNegative() {
        ProxyContext context = context(null);
        context.setRequestTimestamp(2000L);
        // clock moved backwards between the two measurements
        context.setResponseBodyTimestamp(1000L);

        assertEquals(0, context.calculateOperationDurationMs());
    }

    @Test
    public void testUserClaims() {
        ObjectNode claims = ProxyUtil.MAPPER.createObjectNode().put("email", "jane.doe@example.com");

        assertEquals(claims, context(new ExtractedClaims("sub", List.of("role"), "hash", claims, null, "Jane Doe"))
                .getUserClaims());
    }

    @Test
    public void testUserClaimsAreAbsentForApiKeyAuthentication() {
        assertNull(context(null).getUserClaims());
    }

    private static ProxyContext context(ExtractedClaims claims) {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(new Key());
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        return new ProxyContext(null, request, apiKeyData, claims, "trace-id", "span-id", "01");
    }
}
