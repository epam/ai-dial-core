package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AnthropicUsageData;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.limiter.SpendCaps;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnthropicUsageControllerTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private HttpServerResponse response;

    @Test
    void testHandle_respondsInternalErrorWhenSpendCapsFail() {
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(rateLimiter.getSpendCaps(context)).thenReturn(Future.failedFuture(new IllegalStateException("redis down")));

        new AnthropicUsageController(proxy, context).handle();

        verify(context).respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to get spend limits");
    }

    @Test
    void testHandle_respondsUsageWithNoStore() {
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(rateLimiter.getSpendCaps(context)).thenReturn(Future.succeededFuture(new SpendCaps(List.of(), null)));
        when(context.getResponse()).thenReturn(response);

        new AnthropicUsageController(proxy, context).handle();

        verify(response).putHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        verify(context).respond(eq(HttpStatus.OK), any(AnthropicUsageData.class));
    }
}
