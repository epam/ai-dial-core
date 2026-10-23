package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * An unreadable limit counter must reject the request rather than let it through unmetered.
 */
@ExtendWith(MockitoExtension.class)
class MessagesControllerLimitTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private RateLimiter rateLimiter;

    @Test
    void testVerifyLimit_failsClosedWhenLimiterFails() {
        Model model = new Model();
        model.setName("claude-spend");
        when(context.getDeployment()).thenReturn(model);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(rateLimiter.limit(any(), any())).thenReturn(Future.failedFuture(new IllegalStateException("redis down")));

        Future<Void> result = new MessagesController(proxy, context).verifyLimit();

        assertTrue(result.failed());
        HttpException exception = assertInstanceOf(HttpException.class, result.cause());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        assertEquals("false", exception.getHeaders().get("x-should-retry"));
        assertEquals("fetch_error", exception.getHeaders().get("anthropic-ratelimit-unified-overage-disabled-reason"));
        assertFalse(exception.getHeaders().keySet().stream()
                .anyMatch(name -> name.toLowerCase().startsWith("anthropic-ratelimit-")
                        && !name.equalsIgnoreCase("anthropic-ratelimit-unified-overage-disabled-reason")));
    }
}
