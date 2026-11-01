package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.BuiltinTranslator;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.tracing.TracingSettings;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A builtin translator's failed {@code 200} is charged while the response holds the upstream's status, but the span
 * records the error status the client gets.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessagesControllerTracingTest {

    @Mock
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private RateLimiter rateLimiter;

    @Test
    void testFailedTwoHundredIsTracedAsFailed() {
        Model model = new Model();
        model.setName("gpt-builtin");
        when(context.getDeployment()).thenReturn(model);
        when(context.getUserId()).thenReturn("user");
        when(context.getUpstreamRoute()).thenReturn(mock(UpstreamRoute.class, RETURNS_DEEP_STUBS));
        Map<String, Object> tracingAttributes = new ConcurrentHashMap<>();
        when(context.getTracingSettings()).thenReturn(new TracingSettings(true, false, List.of()));
        when(context.getTracingAttributes()).thenReturn(tracingAttributes);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.uri()).thenReturn("/anthropic/v1/messages");
        when(request.headers()).thenReturn(new HeadersMultiMap());
        when(context.getRequest()).thenReturn(request);

        AtomicInteger status = new AtomicInteger();
        HttpServerResponse response = mock(HttpServerResponse.class);
        doAnswer(invocation -> {
            status.set(invocation.getArgument(0));
            return response;
        }).when(response).setStatusCode(anyInt());
        when(response.getStatusCode()).thenAnswer(invocation -> status.get());
        when(response.headers()).thenReturn(new HeadersMultiMap());
        when(context.getResponse()).thenReturn(response);

        when(proxy.getGenerator()).thenReturn(() -> "id");
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(rateLimiter.increase(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        when(proxy.getLogStore()).thenReturn(mock(LogStore.class));
        TokenStatsTracker tokenStatsTracker = mock(TokenStatsTracker.class);
        when(tokenStatsTracker.endSpan(any(ProxyContext.class))).thenReturn(Future.succeededFuture());
        when(proxy.getTokenStatsTracker()).thenReturn(tokenStatsTracker);

        HttpClientResponse proxyResponse = mock(HttpClientResponse.class);
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.headers()).thenReturn(new HeadersMultiMap());
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(Buffer.buffer("""
                {"status": "failed", "error": {"code": "rate_limit_exceeded", "message": "Slow down"},
                 "usage": {"input_tokens": 12, "output_tokens": 7}}""")));

        MessagesController controller = new MessagesController(proxy, context);
        controller.builtinTranslator = BuiltinTranslator.ANTHROPIC_MESSAGES_TO_OPENAI_RESPONSES;
        controller.processResponse(proxyResponse);

        // charged while the status was the upstream's 200
        verify(rateLimiter).increase(any(), any(), any(), any(), any(), any(), any(), any());
        assertEquals(429, status.get());
        assertEquals("failed", tracingAttributes.get("gen_ai.response.status"));
    }
}
