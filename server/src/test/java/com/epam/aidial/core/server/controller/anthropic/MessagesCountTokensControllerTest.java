package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.tracing.TracingSettings;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import io.opentelemetry.api.trace.Span;
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
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code count_tokens} finalizes and ends the response in a single {@code forwardResponse} method
 * (no shared {@code completeProxyResponse} funnel), so its own {@code finalizeRequest()}-before-{@code
 * end()} ordering needs its own coverage - see {@code MessagesControllerTest} for the sibling surface.
 */
@ExtendWith(MockitoExtension.class)
class MessagesCountTokensControllerTest {

    @Mock
    private ProxyContext context;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private LogStore logStore;

    @Mock
    private HttpServerRequest request;

    @InjectMocks
    private MessagesCountTokensController controller;

    @Test
    void testForwardResponse_PublishesLatencyAttributesToSpanBeforeResponseEnds() {
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.getResponse()).thenReturn(response);
        when(response.headers()).thenReturn(new HeadersMultiMap());
        when(proxy.getLogStore()).thenReturn(logStore);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        when(proxyResponse.headers()).thenReturn(new HeadersMultiMap());
        Buffer body = Buffer.buffer("{}");
        when(proxyResponse.body()).thenReturn(Future.succeededFuture(body));
        when(response.end(body)).thenReturn(Future.succeededFuture());

        when(context.getTracingSettings()).thenReturn(new TracingSettings(true, false, List.of()));
        when(context.getTracingAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(context.getRequestTimestamp()).thenReturn(1000L);
        when(context.getRequestBodyTimestamp()).thenReturn(1010L);
        when(context.getProxyConnectTimestamp()).thenReturn(1020L);
        when(context.getProxyResponseTimestamp()).thenReturn(1030L);
        when(context.getResponseBodyTimestamp()).thenReturn(1040L);
        when(context.getRequest()).thenReturn(request);
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.uri()).thenReturn("/anthropic/v1/messages/count_tokens");
        when(request.headers()).thenReturn(new HeadersMultiMap());

        try (var ignored = mockStatic(Span.class)) {
            Span span = mock(Span.class);
            when(span.isRecording()).thenReturn(true);
            when(Span.current()).thenReturn(span);

            controller.processResponse(proxyResponse);

            InOrder order = inOrder(span, response);
            order.verify(span).setAttribute(eq(longKey("dial.latency.client_body_ms")), anyLong());
            order.verify(span).setAttribute(eq(longKey("dial.latency.upstream_connect_ms")), anyLong());
            order.verify(span).setAttribute(eq(longKey("dial.latency.upstream_header_ms")), anyLong());
            order.verify(span).setAttribute(eq(longKey("dial.latency.upstream_body_ms")), anyLong());
            order.verify(response).end(body);
        }
    }
}
