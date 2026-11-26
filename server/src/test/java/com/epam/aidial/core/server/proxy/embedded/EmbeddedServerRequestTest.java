package com.epam.aidial.core.server.proxy.embedded;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                .add("Content-Type", "text/plain").add("Content-Length", "123").add("X-Custom", "kept");

        EmbeddedServerRequest request = request("/openai/v1/chat/completions", headers);

        MultiMap seen = request.headers();
        for (String dropped : EmbeddedServerRequest.DROPPED_HEADERS) {
            assertFalse(seen.contains(dropped), dropped);
        }
        assertFalse(seen.contains("Content-Length"));
        assertEquals("kept", seen.get("X-Custom"));
        assertEquals("application/json", request.getHeader("content-type"));
        // the call's map is copied, not kept
        headers.add("X-Later", "v");
        assertFalse(seen.contains("X-Later"));
    }

    @Test
    void testGetWithoutContentType() {
        EmbeddedServerRequest request = new EmbeddedServerRequest(HttpMethod.GET, "/v1/user/info", null, null, null, mock(HttpServerResponse.class));

        assertEquals(HttpMethod.GET, request.method());
        assertEquals("/v1/user/info", request.path());
        assertNull(request.getHeader("content-type"));
        assertEquals(0, request.body().result().length());
    }

    @Test
    void testBodyAsBytesSerializedOnceWhenRead() throws Exception {
        AsyncTaskExecutor serializer = mock(AsyncTaskExecutor.class);
        when(serializer.submit(any())).thenAnswer(invocation -> Future.succeededFuture(invocation.<Callable<?>>getArgument(0).call()));
        EmbeddedServerRequest request = new EmbeddedServerRequest(HttpMethod.PUT, "/v1/conversations/b/c", null, body(), serializer,
                mock(HttpServerResponse.class));
        verify(serializer, never()).submit(any());

        assertEquals("application/json", request.getHeader("content-type"));
        assertEquals("{\"model\":\"m\"}", request.body().result().toString());
        assertSame(request.body(), request.body());
        verify(serializer).submit(any());
    }

    @Test
    void testResponse() {
        HttpServerResponse response = mock(HttpServerResponse.class);

        assertSame(response, new EmbeddedServerRequest(HttpMethod.POST, "/x", MultiMap.caseInsensitiveMultiMap(), body(), null, response).response());
    }

    private static EmbeddedServerRequest request(String uri, MultiMap headers) {
        // a create operation's call: the pass takes the body as a tree
        return new EmbeddedServerRequest(HttpMethod.POST, uri, headers, body(), null, mock(HttpServerResponse.class));
    }

    private static ObjectNode body() {
        return ProxyUtil.MAPPER.createObjectNode().put("model", "m");
    }
}
