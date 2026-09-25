package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.tracing.TracingSettings;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import io.opentelemetry.api.trace.Span;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@code DeploymentPostControllerTest}'s span-ordering coverage for the chat-completions /
 * Responses API surfaces, for the Anthropic Messages surface: {@code completeProxyResponse} must
 * publish {@code dial.latency.*} onto the still-recording span before the call that ends it, since
 * Vert.x ends the request's OTel span synchronously inside {@code response.end()}/
 * {@code responseStream.end()}.
 */
@ExtendWith(MockitoExtension.class)
class MessagesControllerTest {

    @Mock
    private ProxyContext context;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private LogStore logStore;

    @Mock
    private HttpServerRequest request;

    @InjectMocks
    private MessagesController controller;

    private void enableLatencyTracing() {
        when(context.getTracingSettings()).thenReturn(new TracingSettings(true, false, List.of()));
        when(context.getTracingAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(context.getRequestTimestamp()).thenReturn(1000L);
        when(context.getRequestBodyTimestamp()).thenReturn(1010L);
        when(context.getProxyConnectTimestamp()).thenReturn(1020L);
        when(context.getProxyResponseTimestamp()).thenReturn(1030L);
        when(context.getResponseBodyTimestamp()).thenReturn(1040L);
        // AnalyticsLogContext.from(context, ...), read inside completeProxyResponse() before finalizeRequest()
        when(context.getRequest()).thenReturn(request);
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.uri()).thenReturn("/anthropic/v1/messages");
        when(request.headers()).thenReturn(new HeadersMultiMap());
    }

    private static void assertLatencyPublishedBeforeEnd(InOrder order, Span span) {
        order.verify(span).setAttribute(eq(longKey("dial.latency.client_body_ms")), anyLong());
        order.verify(span).setAttribute(eq(longKey("dial.latency.upstream_connect_ms")), anyLong());
        order.verify(span).setAttribute(eq(longKey("dial.latency.upstream_header_ms")), anyLong());
        order.verify(span).setAttribute(eq(longKey("dial.latency.upstream_body_ms")), anyLong());
        verify(span, times(1)).setAttribute(eq(longKey("dial.latency.client_body_ms")), anyLong());
        verify(span, times(1)).setAttribute(eq(longKey("dial.latency.upstream_connect_ms")), anyLong());
        verify(span, times(1)).setAttribute(eq(longKey("dial.latency.upstream_header_ms")), anyLong());
        verify(span, times(1)).setAttribute(eq(longKey("dial.latency.upstream_body_ms")), anyLong());
    }

    @Test
    void testHandleNonStreamingResponse_PublishesLatencyAttributesToSpanBeforeResponseEnds() {
        Model model = new Model();
        when(context.getDeployment()).thenReturn(model);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.getResponse()).thenReturn(response);
        when(response.headers()).thenReturn(new HeadersMultiMap());
        when(proxy.getLogStore()).thenReturn(logStore);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        when(proxyResponse.headers()).thenReturn(new HeadersMultiMap());
        Buffer body = Buffer.buffer("{}");
        enableLatencyTracing();

        try (var ignored = mockStatic(Span.class)) {
            Span span = mock(Span.class);
            when(span.isRecording()).thenReturn(true);
            when(Span.current()).thenReturn(span);

            controller.handleNonStreamingResponse(proxyResponse, body);

            InOrder order = inOrder(span, response);
            assertLatencyPublishedBeforeEnd(order, span);
            order.verify(response).end(body);
        }
    }

    @Test
    void testHandleResponse_PublishesLatencyAttributesToSpanBeforeResponseEnds() {
        Model model = new Model();
        when(context.getDeployment()).thenReturn(model);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.getResponse()).thenReturn(response);
        when(proxy.getLogStore()).thenReturn(logStore);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        BufferingReadStream responseStream = mock(BufferingReadStream.class);
        when(responseStream.getContent()).thenReturn(Buffer.buffer("{}"));
        enableLatencyTracing();

        try (var ignored = mockStatic(Span.class)) {
            Span span = mock(Span.class);
            when(span.isRecording()).thenReturn(true);
            when(Span.current()).thenReturn(span);

            controller.handleResponse(responseStream);

            InOrder order = inOrder(span, responseStream);
            assertLatencyPublishedBeforeEnd(order, span);
            order.verify(responseStream).end(response);
        }
    }
}
