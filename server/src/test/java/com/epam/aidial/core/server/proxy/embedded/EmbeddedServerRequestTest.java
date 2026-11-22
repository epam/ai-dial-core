package com.epam.aidial.core.server.proxy.embedded;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class EmbeddedServerRequestTest {

    @Test
    void testPathQueryAndMethod() {
        EmbeddedServerRequest request = request("/openai/deployments/m/chat/completions?api-version=1&x=a%20b", MultiMap.caseInsensitiveMultiMap());

        assertEquals(HttpMethod.POST, request.method());
        assertEquals("/openai/deployments/m/chat/completions", request.path());
        assertEquals("api-version=1&x=a%20b", request.query());
        assertEquals("/openai/deployments/m/chat/completions?api-version=1&x=a%20b", request.uri());
        assertEquals("1", request.getParam("api-version"));
        assertEquals("a b", request.getParam("x"));
        assertTrue(request.isEnded());
        assertEquals(0, request.body().result().length());
    }

    @Test
    void testPathWithoutQuery() {
        EmbeddedServerRequest request = request("/openai/v1/responses", MultiMap.caseInsensitiveMultiMap());

        assertEquals("/openai/v1/responses", request.path());
        assertNull(request.query());
        assertTrue(request.params().isEmpty());
    }

    @Test
    void testHeadersCoreSetsForSomeTargetsAreDropped() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("X-UPSTREAM-ENDPOINT", "http://cloud").add("x-upstream-key", "k").add("X-UPSTREAM-EXTRA-DATA", "{}")
                .add("X-DIAL-CACHE-BREAKPOINT-PATH", "p").add("X-DIAL-CACHE-EXTRA-METADATA", "m")
                .add("X-DIAL-OVERRIDE-NAME", "o").add("X-DIAL-DEPLOYMENT-ID", "d")
                .add("X-DIAL-APPLICATION-ID", "app").add("X-DIAL-APPLICATION-PROPERTIES", "{}").add("X-DIAL-DEPLOYMENT-FEATURES", "{}")
                .add("Content-Type", "text/plain").add("X-Custom", "kept");

        EmbeddedServerRequest request = request("/openai/v1/chat/completions", headers);

        MultiMap seen = request.headers();
        for (String dropped : EmbeddedServerRequest.DROPPED_HEADERS) {
            assertFalse(seen.contains(dropped), dropped);
        }
        assertEquals("kept", seen.get("X-Custom"));
        assertEquals("application/json", request.getHeader("content-type"));
        // the call's map is copied, not kept
        headers.add("X-Later", "v");
        assertFalse(seen.contains("X-Later"));
    }

    @Test
    void testResponse() {
        HttpServerResponse response = mock(HttpServerResponse.class);

        assertSame(response, new EmbeddedServerRequest("/x", MultiMap.caseInsensitiveMultiMap(), response).response());
    }

    private static EmbeddedServerRequest request(String uri, MultiMap headers) {
        return new EmbeddedServerRequest(uri, headers, mock(HttpServerResponse.class));
    }
}
