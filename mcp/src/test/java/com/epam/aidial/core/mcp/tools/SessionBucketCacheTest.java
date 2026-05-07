package com.epam.aidial.core.mcp.tools;

import com.epam.aidial.core.mcp.client.DialClient;
import com.epam.aidial.core.mcp.client.DialResponse;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionBucketCacheTest {

    @Test
    void rejectsNullSessionIdInsteadOfThrowingNpe() {
        DialClient client = Mockito.mock(DialClient.class);
        SessionBucketCache cache = new SessionBucketCache(client);

        java.util.concurrent.ExecutionException ex = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> cache.resolvePrivate(null, Map.of()).toFuture().get(2, TimeUnit.SECONDS));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void successfulFirstCallIsCachedForSubsequentCalls() throws Exception {
        DialClient client = Mockito.mock(DialClient.class);
        AtomicInteger calls = new AtomicInteger();
        Mockito.when(client.request(Mockito.eq(HttpMethod.GET), Mockito.eq("/v1/bucket"),
                        Mockito.anyMap(), Mockito.anyMap(), Mockito.isNull()))
                .thenAnswer(inv -> {
                    calls.incrementAndGet();
                    return Mono.just(new DialResponse(200, "{\"bucket\":\"abc\"}", MultiMap.caseInsensitiveMultiMap()));
                });
        SessionBucketCache cache = new SessionBucketCache(client);

        String first = cache.resolvePrivate("s1", Map.of()).toFuture().get(2, TimeUnit.SECONDS);
        String second = cache.resolvePrivate("s1", Map.of()).toFuture().get(2, TimeUnit.SECONDS);

        assertEquals("abc", first);
        assertEquals("abc", second);
        assertEquals(1, calls.get());
    }

    @Test
    void failedFirstCallDoesNotPoisonSubsequentCallsForSameSession() throws Exception {
        DialClient client = Mockito.mock(DialClient.class);
        AtomicInteger calls = new AtomicInteger();
        Mockito.when(client.request(Mockito.eq(HttpMethod.GET), Mockito.eq("/v1/bucket"),
                        Mockito.anyMap(), Mockito.anyMap(), Mockito.isNull()))
                .thenAnswer(inv -> {
                    int n = calls.incrementAndGet();
                    if (n == 1) {
                        return Mono.error(new IllegalStateException("transient"));
                    }
                    return Mono.just(new DialResponse(200, "{\"bucket\":\"recovered\"}", MultiMap.caseInsensitiveMultiMap()));
                });
        SessionBucketCache cache = new SessionBucketCache(client);

        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> cache.resolvePrivate("s1", Map.of()).toFuture().get(2, TimeUnit.SECONDS));

        String retry = cache.resolvePrivate("s1", Map.of()).toFuture().get(2, TimeUnit.SECONDS);

        assertEquals("recovered", retry);
        assertEquals(2, calls.get());
    }
}
