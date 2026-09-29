package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.token.MessagesTokenUsageParser;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.tracing.TracingSettings;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ModelCostCalculator;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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
    private RateLimiter rateLimiter;

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

    /**
     * PR #2020 review item 2: the Anthropic non-streaming body used to be read twice - once by
     * {@code MessagesTokenUsageParser.parse(Buffer)} and once by {@code GenAiTraceAttributes}, both doing
     * their own {@code readTree}. It is now parsed once in the controller and that tree is what token usage
     * reads, so the {@code Buffer} entry point must not be reached on this path at all.
     */
    @Test
    void testHandleNonStreamingResponse_ReadsUsageFromTheTreeParsedOnce() {
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
        // a non-OK client status stops chargeTokenUsage before the rate limiter, keeping this focused
        when(response.getStatusCode()).thenReturn(HttpStatus.INTERNAL_SERVER_ERROR.getCode());
        Buffer body = Buffer.buffer("{\"usage\":{\"input_tokens\":10,\"output_tokens\":8}}");
        enableLatencyTracing();

        try (var mockedJson = mockStatic(JsonUtil.class, CALLS_REAL_METHODS);
                var mockedParser = mockStatic(MessagesTokenUsageParser.class)) {
            controller.handleNonStreamingResponse(proxyResponse, body);

            mockedJson.verify(() -> JsonUtil.tryParse(any(byte[].class)), times(1));
            mockedParser.verify(() -> MessagesTokenUsageParser.parse(any(Buffer.class)), never());
        }
    }

    /**
     * The same tree that tracing and token usage read must also be what {@code ModelCostCalculator} prices -
     * verified by instance identity, not just overload choice, so a caller that re-parsed the body into an
     * equal-but-distinct node would still fail this even though every {@code never()} check above would pass.
     */
    @Test
    void testHandleNonStreamingResponse_PricesTheSameTreeTracingAndUsageRead() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        when(context.getUserId()).thenReturn("test-user");
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(context.getResponse()).thenReturn(response);
        when(response.headers()).thenReturn(new HeadersMultiMap());
        when(response.getStatusCode()).thenReturn(HttpStatus.OK.getCode());
        when(proxy.getLogStore()).thenReturn(logStore);
        UpstreamRoute upstreamRoute = mock(UpstreamRoute.class, RETURNS_DEEP_STUBS);
        when(context.getUpstreamRoute()).thenReturn(upstreamRoute);
        when(proxy.getRateLimiter()).thenReturn(rateLimiter);
        when(rateLimiter.increase(any(), any(), any(), any(), any(), any())).thenReturn(Future.succeededFuture());
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class, RETURNS_DEEP_STUBS);
        when(proxyResponse.headers()).thenReturn(new HeadersMultiMap());
        Buffer body = Buffer.buffer("{\"usage\":{\"input_tokens\":10,\"output_tokens\":8}}");
        enableLatencyTracing();

        try (var mockedJson = mockStatic(JsonUtil.class, CALLS_REAL_METHODS)) {
            controller.handleNonStreamingResponse(proxyResponse, body);

            mockedJson.verify(() -> JsonUtil.tryParse(any(byte[].class)), times(1));

            ArgumentCaptor<JsonNode> parsed = ArgumentCaptor.forClass(JsonNode.class);
            verify(context).setTokenUsage(any());
            // rateLimiter is mocked, so pricing (ModelCostCalculator.resolveCost, invoked from inside its
            // real increase()) is exercised in RateLimiterTest/ModelCostCalculatorTest instead; here the
            // boundary this test controls is the ResponseSource it hands to rateLimiter.increase().
            ArgumentCaptor<ModelCostCalculator.ResponseSource> priced = ArgumentCaptor.forClass(ModelCostCalculator.ResponseSource.class);
            verify(rateLimiter).increase(eq(model), any(), any(), any(), any(), priced.capture());
            assertInstanceOf(ModelCostCalculator.ResponseSource.Tree.class, priced.getValue());
            JsonNode pricedTree = ((ModelCostCalculator.ResponseSource.Tree) priced.getValue()).responseTree();

            assertEquals(10, pricedTree.path("usage").path("input_tokens").asLong());
        }
    }

    /**
     * The streaming counterpart still holds only the buffered SSE frames, so it keeps the {@code Buffer}
     * entry point - and {@code parseTokenUsage} short-circuits to the usage
     * {@code CollectMessagesTokenUsageFn} accumulated event by event, never reading the body at all.
     */
    @Test
    void testStreamingResponse_TakesUsageFromTheContextNotTheBody() {
        when(context.isStreamingRequest()).thenReturn(true);
        TokenUsage streamed = new TokenUsage();
        streamed.setTotalTokens(18);
        when(context.getTokenUsage()).thenReturn(streamed);

        assertSame(streamed, controller.parseTokenUsage(Buffer.buffer("{}")));
        assertSame(streamed, controller.parseTokenUsage(ProxyUtil.MAPPER.createObjectNode()));
    }

    /**
     * PR #2020 review item 2: {@code dial.latency.client_body_ms} must reflect only the time to
     * receive/parse the client body, consistently with {@code ChatCompletionsController} - not the
     * enhancement chain, per-request key assignment, body re-serialization or upstream-route
     * resolution that {@code prepareUpstreamRoute()} runs afterward. {@code parseBody()} is the
     * raw-body-receipt point, so the timestamp must be taken there.
     */
    @Test
    void testParseBody_SetsRequestBodyTimestamp() {
        Buffer body = Buffer.buffer("{\"model\":\"test\"}");

        controller.parseBody(body);

        verify(context, times(1)).setRequestBodyTimestamp(anyLong());
    }
}
