package com.epam.aidial.core.server.tracing;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.token.CompletionTokensDetails;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.trace.Span;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenAiTraceAttributesTest {

    @Test
    void initializeCollectsConversationAndIncomingParentSpan() {
        Proxy proxy = proxy(enabledSettings());
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("thread-id", "conversation-1");
        when(request.headers()).thenReturn(headers);
        when(request.getHeader("traceparent"))
                .thenReturn("00-11111111111111111111111111111111-2222222222222222-01");
        ProxyContext context = context(proxy, request);

        try (var ignored = mockStatic(Span.class)) {
            Span span = mock(Span.class);
            when(span.isRecording()).thenReturn(true);
            when(Span.current()).thenReturn(span);

            GenAiTraceAttributes.initialize(context);

            assertEquals("conversation-1", context.getTracingAttributes().get("gen_ai.conversation.id"));
            assertEquals("2222222222222222",
                    context.getTracingAttributes().get("dial.request.parent_span.id"));
            verify(span).setAttribute(stringKey("gen_ai.conversation.id"), "conversation-1");
            verify(span).setAttribute(stringKey("dial.request.parent_span.id"), "2222222222222222");
        }
    }

    @Test
    void initializeDoesNothingWhenGenAiAttributesAreDisabled() {
        Proxy proxy = proxy(TracingSettings.from(new JsonObject()));
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(request.getHeader("thread-id")).thenReturn("conversation-1");
        when(request.getHeader("traceparent"))
                .thenReturn("00-11111111111111111111111111111111-2222222222222222-01");
        ProxyContext context = context(proxy, request);

        try (var ignored = mockStatic(Span.class)) {
            Span span = mock(Span.class);
            when(span.isRecording()).thenReturn(true);
            when(Span.current()).thenReturn(span);

            GenAiTraceAttributes.initialize(context);

            assertTrue(context.getTracingAttributes().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "x-claude-code-session-id",
            "thread-id",
            "x-session-id",
            "x-dial-client-channel-id",
            "X-CONVERSATION-ID"
    })
    void initializeMapsEveryDefaultConversationHeader(String header) {
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add(header, " conversation-1 ");
        when(request.headers()).thenReturn(headers);
        ProxyContext context = context(proxy(enabledSettings()), request);

        GenAiTraceAttributes.initialize(context);

        assertEquals("conversation-1", context.getTracingAttributes().get("gen_ai.conversation.id"));
    }

    @Test
    void initializeOmitsOversizedConversationHeader() {
        HttpServerRequest oversized = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        MultiMap oversizedHeaders = MultiMap.caseInsensitiveMultiMap();
        oversizedHeaders.add("thread-id", "x".repeat(257));
        when(oversized.headers()).thenReturn(oversizedHeaders);
        ProxyContext context = context(proxy(enabledSettings()), oversized);

        GenAiTraceAttributes.initialize(context);

        assertFalse(context.getTracingAttributes().containsKey("gen_ai.conversation.id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"00-invalid", "00-00000000000000000000000000000000-2222222222222222-01",
            "ff-11111111111111111111111111111111-2222222222222222-01"})
    void initializeOmitsInvalidTraceparent(String traceparent) {
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        when(request.headers()).thenReturn(headers);
        when(request.getHeader("traceparent")).thenReturn(traceparent);
        ProxyContext context = context(proxy(enabledSettings()), request);

        GenAiTraceAttributes.initialize(context);

        assertFalse(context.getTracingAttributes().containsKey("dial.request.parent_span.id"));
    }

    @Test
    void setRequestAttributesCoversChatCompletions() throws Exception {
        ProxyContext context = context(proxy(enabledSettings()));
        var tree = ProxyUtil.MAPPER.readTree("""
                {"model":"gpt-4","stream":true,"max_tokens":100,"temperature":0.2,"top_p":0.8,
                 "stop":["stop","length"],"n":2,"frequency_penalty":0.1,"presence_penalty":0.2,
                 "seed":42,"reasoning_effort":"high"}
                """);

        GenAiTraceAttributes.setRequestAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS, (ObjectNode) tree);

        assertEquals("chat", context.getTracingAttributes().get("gen_ai.operation.name"));
        assertEquals("openai_chat_completions", context.getTracingAttributes().get("dial.api"));
        assertEquals("gpt-4", context.getTracingAttributes().get("gen_ai.request.model"));
        assertEquals(Boolean.TRUE, context.getTracingAttributes().get("gen_ai.request.stream"));
        assertEquals(100L, context.getTracingAttributes().get("gen_ai.request.max_tokens"));
        assertEquals(0.2D, context.getTracingAttributes().get("gen_ai.request.temperature"));
        assertEquals(0.8D, context.getTracingAttributes().get("gen_ai.request.top_p"));
        assertEquals(List.of("stop", "length"), context.getTracingAttributes().get("gen_ai.request.stop_sequences"));
        assertEquals(2L, context.getTracingAttributes().get("gen_ai.request.choice.count"));
        assertEquals(0.1D, context.getTracingAttributes().get("gen_ai.request.frequency_penalty"));
        assertEquals(0.2D, context.getTracingAttributes().get("gen_ai.request.presence_penalty"));
        assertEquals(42L, context.getTracingAttributes().get("gen_ai.request.seed"));
        assertEquals("high", context.getTracingAttributes().get("gen_ai.request.reasoning.level"));
    }

    @Test
    void setRequestAttributesCoversEmbeddings() throws Exception {
        ProxyContext context = context(proxy(enabledSettings()));
        var tree = ProxyUtil.MAPPER.readTree("{\"model\":\"embedding\",\"encoding_format\":\"base64\"}");

        GenAiTraceAttributes.setRequestAttributes(context, InterfaceType.OPENAI_EMBEDDINGS, (ObjectNode) tree);

        assertEquals("embeddings", context.getTracingAttributes().get("gen_ai.operation.name"));
        assertEquals("openai_embeddings", context.getTracingAttributes().get("dial.api"));
        assertEquals("embedding", context.getTracingAttributes().get("gen_ai.request.model"));
        assertEquals(List.of("base64"), context.getTracingAttributes().get("gen_ai.request.encoding_formats"));
    }

    @Test
    void setRequestAttributesCoversResponsesCreate() throws Exception {
        ProxyContext context = context(proxy(enabledSettings()));
        var tree = ProxyUtil.MAPPER.readTree("""
                {"model":"gpt-4","stream":true,"max_output_tokens":100,"temperature":0.2,
                 "top_p":0.8,"previous_response_id":"resp_1","reasoning":{"effort":"low"}}
                """);

        GenAiTraceAttributes.setRequestAttributes(context, InterfaceType.OPENAI_RESPONSES, (ObjectNode) tree);

        assertEquals("generate_content", context.getTracingAttributes().get("gen_ai.operation.name"));
        assertEquals("openai_responses", context.getTracingAttributes().get("dial.api"));
        assertEquals(100L, context.getTracingAttributes().get("gen_ai.request.max_tokens"));
        assertEquals("resp_1", context.getTracingAttributes().get("gen_ai.request.previous_response.id"));
        assertEquals("low", context.getTracingAttributes().get("gen_ai.request.reasoning.level"));
    }

    @Test
    void setRequestAttributesCoversAnthropicMessages() throws Exception {
        ProxyContext context = context(proxy(enabledSettings()));
        var tree = ProxyUtil.MAPPER.readTree("""
                {"model":"claude","stream":true,"max_tokens":100,"temperature":0.2,
                 "top_p":0.8,"stop_sequences":["end_turn"]}
                """);

        GenAiTraceAttributes.setRequestAttributes(context, InterfaceType.ANTHROPIC_MESSAGES, (ObjectNode) tree);

        assertEquals("chat", context.getTracingAttributes().get("gen_ai.operation.name"));
        assertEquals("anthropic_messages", context.getTracingAttributes().get("dial.api"));
        assertEquals(List.of("end_turn"), context.getTracingAttributes().get("gen_ai.request.stop_sequences"));
    }

    @Test
    void setResponseAttributesCoversNonStreamingChatResponse() {
        ProxyContext context = context(proxy(enabledSettings()));
        Buffer body = Buffer.buffer("""
                {"id":"chat-1","model":"gpt-4","choices":[{"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
                """);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS, body);

        assertEquals("chat-1", context.getTracingAttributes().get("gen_ai.response.id"));
        assertEquals("gpt-4", context.getTracingAttributes().get("gen_ai.response.model"));
        assertEquals(List.of("stop"), context.getTracingAttributes().get("gen_ai.response.finish_reasons"));
        assertEquals("completed", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setResponseAttributesFromAlreadyParsedTreeMatchesFromBuffer() throws Exception {
        ProxyContext context = context(proxy(enabledSettings()));
        String body = """
                {"id":"chat-1","model":"gpt-4","choices":[{"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
                """;

        // the caller already parsed the body for its own purposes (e.g. id rewriting) - no reparse here
        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS,
                ProxyUtil.MAPPER.readTree(body), null);

        assertEquals("chat-1", context.getTracingAttributes().get("gen_ai.response.id"));
        assertEquals("gpt-4", context.getTracingAttributes().get("gen_ai.response.model"));
        assertEquals(List.of("stop"), context.getTracingAttributes().get("gen_ai.response.finish_reasons"));
        assertEquals("completed", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setResponseAttributesFromTreeHonorsResponseIdOverride() throws Exception {
        ProxyContext context = context(proxy(enabledSettings()));
        var tree = ProxyUtil.MAPPER.readTree("{\"id\":\"upstream-1\",\"model\":\"gpt-4\"}");

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS, tree, "dial-1");

        assertEquals("dial-1", context.getTracingAttributes().get("gen_ai.response.id"));
    }

    @Test
    void setResponseAttributesCoversStreamingChatResponse() {
        ProxyContext context = streamingContext();
        Buffer body = Buffer.buffer("""
                data: {"id":"chat-1","model":"gpt-4","choices":[{"index":0,"finish_reason":null}]}

                data: {"id":"chat-1","model":"gpt-4","choices":[{"index":0,"finish_reason":"length"}]}

                data: [DONE]
                """);
        context.setResponseBody(body);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS, body);

        assertEquals("chat-1", context.getTracingAttributes().get("gen_ai.response.id"));
        assertEquals(List.of("length"), context.getTracingAttributes().get("gen_ai.response.finish_reasons"));
        assertEquals("completed", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setResponseAttributesCoversStreamingAnthropicResponse() {
        ProxyContext context = streamingContext();
        Buffer body = Buffer.buffer("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg-1","model":"claude"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
                """);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.ANTHROPIC_MESSAGES, body);

        assertEquals("msg-1", context.getTracingAttributes().get("gen_ai.response.id"));
        assertEquals("claude", context.getTracingAttributes().get("gen_ai.response.model"));
        assertEquals(List.of("end_turn"), context.getTracingAttributes().get("gen_ai.response.finish_reasons"));
    }

    @Test
    void setResponseAttributesCoversStreamingResponsesApi() {
        ProxyContext context = streamingContext();
        Buffer body = Buffer.buffer("""
                event: response.completed
                data: {"type":"response.completed","response":{"id":"resp-1","model":"gpt-4","status":"completed"}}
                """);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_RESPONSES, body);

        assertEquals("resp-1", context.getTracingAttributes().get("gen_ai.response.id"));
        assertEquals("completed", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setResponseAttributesPublishesTheClientFacingIdNotTheUpstreamOne() {
        ProxyContext context = streamingContext();
        // the buffered frames are the raw upstream ones, so they still carry the upstream id
        Buffer body = Buffer.buffer("""
                event: response.completed
                data: {"type":"response.completed","response":{"id":"upstream-1","model":"gpt-4","status":"completed"}}
                """);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_RESPONSES, body, "dial-1");

        assertEquals("dial-1", context.getTracingAttributes().get("gen_ai.response.id"));
        assertEquals("gpt-4", context.getTracingAttributes().get("gen_ai.response.model"));
    }

    @Test
    void setResponseAttributesPublishesWhatTheUpstreamAskedToCacheAndWhetherCoreStoredIt() {
        ProxyContext context = context(proxy(enabledSettings()));
        UpstreamRoute route = mock(UpstreamRoute.class);
        when(route.getCacheBreakpointPath()).thenReturn("prefix.body.messages[1]");
        when(route.isCacheEntryStored()).thenReturn(true);
        context.setUpstreamRoute(route);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS,
                Buffer.buffer("{\"id\":\"chat-1\"}"));

        assertEquals("prefix.body.messages[1]", context.getTracingAttributes().get("dial.upstream.cache.breakpoint_path"));
        assertEquals(true, context.getTracingAttributes().get("dial.upstream.cache.stored"));
    }

    @Test
    void setResponseAttributesOmitsCacheAttributesWhenTheUpstreamAskedForNoCache() {
        ProxyContext context = context(proxy(enabledSettings()));
        context.setUpstreamRoute(mock(UpstreamRoute.class));

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS,
                Buffer.buffer("{\"id\":\"chat-1\"}"));

        // "stored: false" against a path the upstream never reported would read as a failure to cache
        assertFalse(context.getTracingAttributes().containsKey("dial.upstream.cache.breakpoint_path"));
        assertFalse(context.getTracingAttributes().containsKey("dial.upstream.cache.stored"));
    }

    @Test
    void enrichmentFailureNeverPropagatesToTheRequest() {
        // the headline safety property: this runs before the client response is completed
        ProxyContext context = context(proxy(enabledSettings()));
        UpstreamRoute route = mock(UpstreamRoute.class);
        when(route.getCacheBreakpointPath()).thenThrow(new IllegalStateException("boom"));
        context.setUpstreamRoute(route);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS,
                Buffer.buffer("{\"id\":\"chat-1\"}"));

        // contained, not skipped: what was set before the throw survives
        assertEquals("chat-1", context.getTracingAttributes().get("gen_ai.response.id"));
    }

    @Test
    void setResponseAttributesReadsStreamsWhoseFramesCarryNoEventName() {
        // a translator emitting bare data: lines must not silently lose its response attributes
        ProxyContext context = streamingContext();
        Buffer body = Buffer.buffer("""
                data: {"type":"response.output_text.delta","delta":"hi"}

                data: {"type":"response.completed","response":{"id":"resp-1","model":"gpt-4","status":"completed"}}
                """);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_RESPONSES, body);

        assertEquals("resp-1", context.getTracingAttributes().get("gen_ai.response.id"));
        assertEquals("completed", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setLatencyAttributesPublishesEachPhase() {
        ProxyContext context = context(proxy(enabledSettings()));
        context.setRequestTimestamp(1_000L);
        context.setRequestBodyTimestamp(1_030L);
        context.setProxyConnectTimestamp(1_050L);
        context.setProxyResponseTimestamp(1_400L);
        context.setResponseBodyTimestamp(2_600L);

        GenAiTraceAttributes.setLatencyAttributes(context);

        assertEquals(30L, context.getTracingAttributes().get("dial.latency.client_body_ms"));
        assertEquals(20L, context.getTracingAttributes().get("dial.latency.upstream_connect_ms"));
        assertEquals(350L, context.getTracingAttributes().get("dial.latency.upstream_header_ms"));
        assertEquals(1200L, context.getTracingAttributes().get("dial.latency.upstream_body_ms"));
    }

    @Test
    void setLatencyAttributesOmitsPhasesThatNeverHappened() {
        // a request rejected before any upstream: connect and response bounds are still 0
        ProxyContext context = context(proxy(enabledSettings()));
        context.setRequestTimestamp(1_000L);
        context.setRequestBodyTimestamp(1_030L);

        GenAiTraceAttributes.setLatencyAttributes(context);

        assertEquals(30L, context.getTracingAttributes().get("dial.latency.client_body_ms"));
        assertFalse(context.getTracingAttributes().containsKey("dial.latency.upstream_connect_ms"));
        assertFalse(context.getTracingAttributes().containsKey("dial.latency.upstream_header_ms"));
        assertFalse(context.getTracingAttributes().containsKey("dial.latency.upstream_body_ms"));
    }

    @Test
    void setLatencyAttributesOmitsPhasesWhoseClockSteppedBackwards() {
        ProxyContext context = context(proxy(enabledSettings()));
        context.setRequestTimestamp(1_000L);
        context.setRequestBodyTimestamp(900L);

        GenAiTraceAttributes.setLatencyAttributes(context);

        assertFalse(context.getTracingAttributes().containsKey("dial.latency.client_body_ms"));
    }

    @Test
    void setUpstreamAttemptsMirrorsTheResponseHeader() {
        ProxyContext context = context(proxy(enabledSettings()));

        GenAiTraceAttributes.setUpstreamAttempts(context, 3);

        assertEquals(3L, context.getTracingAttributes().get("dial.upstream.attempts"));
    }

    @Test
    void setUpstreamAttemptsDoesNothingWhenDisabled() {
        ProxyContext context = context(proxy(TracingSettings.from(new JsonObject())));

        GenAiTraceAttributes.setUpstreamAttempts(context, 3);

        assertTrue(context.getTracingAttributes().isEmpty());
    }

    @Test
    void setResponseAttributesReportsFailedStatusOnErrorResponse() {
        ProxyContext context = context(proxy(enabledSettings()));
        when(context.getResponse().getStatusCode()).thenReturn(500);
        Buffer body = Buffer.buffer("{\"error\":{\"message\":\"upstream is down\"}}");

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS, body);

        assertEquals("failed", context.getTracingAttributes().get("gen_ai.response.status"));
        assertFalse(context.getTracingAttributes().containsKey("gen_ai.response.id"));
    }

    @Test
    void setResponseAttributesReportsTheClientStatusNotTheUpstreamOne() {
        ProxyContext context = context(proxy(enabledSettings()));
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class);
        when(proxyResponse.statusCode()).thenReturn(200);
        context.setProxyResponse(proxyResponse);
        // DIAL rewrote the status after a 200 upstream
        when(context.getResponse().getStatusCode()).thenReturn(502);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS,
                Buffer.buffer("{\"id\":\"chat-1\"}"));

        assertEquals("failed", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setFailureStatusReportsFailedWhenNoUpstreamWasReached() {
        // a rate-limit rejection never reaches setResponseAttributes, so this is the only outcome it gets
        ProxyContext context = context(proxy(enabledSettings()));
        GenAiTraceAttributes.setRequestAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS,
                ProxyUtil.MAPPER.createObjectNode());

        GenAiTraceAttributes.setFailureStatus(context, 429);

        assertEquals("failed", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setFailureStatusKeepsTheStatusAlreadyPublished() {
        ProxyContext context = context(proxy(enabledSettings()));
        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_RESPONSES,
                Buffer.buffer("{\"id\":\"resp-1\",\"status\":\"incomplete\"}"));

        GenAiTraceAttributes.setFailureStatus(context, 500);

        assertEquals("incomplete", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setFailureStatusIgnoresSuccessfulResponses() {
        ProxyContext context = context(proxy(enabledSettings()));

        GenAiTraceAttributes.setFailureStatus(context, 200);

        assertFalse(context.getTracingAttributes().containsKey("gen_ai.response.status"));
    }

    @Test
    void setResponseAttributesClampsOversizedCallerControlledText() {
        ProxyContext context = context(proxy(enabledSettings()));

        GenAiTraceAttributes.setRequestAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS,
                (ObjectNode) ProxyUtil.MAPPER.valueToTree(Map.of("model", "m".repeat(1000))));

        assertEquals("m".repeat(256), context.getTracingAttributes().get("gen_ai.request.model"));
    }

    @Test
    void setRequestAttributesCapsUnboundedStopSequences() {
        ProxyContext context = context(proxy(enabledSettings()));
        List<String> stop = IntStream.range(0, 100).mapToObj(Integer::toString).toList();

        GenAiTraceAttributes.setRequestAttributes(context, InterfaceType.OPENAI_CHAT_COMPLETIONS,
                (ObjectNode) ProxyUtil.MAPPER.valueToTree(Map.of("stop", stop)));

        assertEquals(32, ((List<?>) context.getTracingAttributes().get("gen_ai.request.stop_sequences")).size());
    }

    @Test
    void setResponseAttributesSurvivesTerminalResponsesEventWithoutResponseObject() {
        ProxyContext context = streamingContext();
        Buffer body = Buffer.buffer("""
                event: response.failed
                data: {"type":"response.failed","error":{"message":"boom"}}
                """);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_RESPONSES, body);

        assertFalse(context.getTracingAttributes().containsKey("gen_ai.response.id"));
        // DIAL streamed the body successfully, so the client-facing 200 would read as "completed" -
        // only the terminal event type knows the run failed
        assertEquals("failed", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setResponseAttributesDerivesEachTerminalResponsesEventStatus() {
        for (String event : List.of("failed", "cancelled", "incomplete")) {
            ProxyContext context = streamingContext();
            Buffer body = Buffer.buffer("""
                    event: response.%s
                    data: {"type":"response.%s","response":{"id":"resp-1"}}
                    """.formatted(event, event));

            GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_RESPONSES, body);

            assertEquals(event, context.getTracingAttributes().get("gen_ai.response.status"));
            assertEquals("resp-1", context.getTracingAttributes().get("gen_ai.response.id"));
        }
    }

    @Test
    void setResponseAttributesSurvivesAnthropicMessageStartWithoutMessage() {
        ProxyContext context = streamingContext();
        Buffer body = Buffer.buffer("""
                event: message_start
                data: {"type":"message_start"}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
                """);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.ANTHROPIC_MESSAGES, body);

        assertFalse(context.getTracingAttributes().containsKey("gen_ai.response.id"));
        assertEquals(List.of("end_turn"), context.getTracingAttributes().get("gen_ai.response.finish_reasons"));
    }

    @Test
    void setFetchResponseAttributesUsesFetchOperation() {
        ProxyContext context = context(proxy(enabledSettings()));
        Buffer body = Buffer.buffer("{\"id\":\"resp-1\",\"model\":\"gpt-4\",\"status\":\"completed\"}");

        GenAiTraceAttributes.setFetchResponseAttributes(context, body, "resp-1");

        assertEquals("fetch_response", context.getTracingAttributes().get("gen_ai.operation.name"));
        assertEquals("resp-1", context.getTracingAttributes().get("gen_ai.response.id"));
    }

    @Test
    void setFetchResponseAttributesPublishesDialResponseIdNotTheUpstreamOne() {
        ProxyContext context = streamingContext();
        // the buffered bytes are the raw upstream frames, before ReplaceResponseIdFn rewrote the id
        Buffer body = Buffer.buffer("""
                event: response.completed
                data: {"type":"response.completed","response":{"id":"upstream-1","model":"gpt-4"}}
                """);

        GenAiTraceAttributes.setFetchResponseAttributes(context, body, "dial-1");

        assertEquals("dial-1", context.getTracingAttributes().get("gen_ai.response.id"));
    }

    @Test
    void setFetchResponseAttributesFromAlreadyParsedTreeMatchesFromBuffer() throws Exception {
        ProxyContext context = context(proxy(enabledSettings()));
        String body = "{\"id\":\"resp-1\",\"model\":\"gpt-4\",\"status\":\"completed\"}";

        // the caller already parsed the body for id rewriting/terminal-result detection - no reparse here
        GenAiTraceAttributes.setFetchResponseAttributes(context, ProxyUtil.MAPPER.readTree(body), "resp-1");

        assertEquals("fetch_response", context.getTracingAttributes().get("gen_ai.operation.name"));
        assertEquals("resp-1", context.getTracingAttributes().get("gen_ai.response.id"));
    }

    @Test
    void setFetchResponseAttributesFromTreeCollectsUsage() throws Exception {
        ProxyContext context = context(proxy(enabledSettings()));
        String body = """
                {"id":"resp-1","model":"gpt-4","status":"completed",
                 "usage":{"input_tokens":5,"output_tokens":7,"total_tokens":12}}
                """;

        GenAiTraceAttributes.setFetchResponseAttributes(context, ProxyUtil.MAPPER.readTree(body), "resp-1");

        assertEquals(5L, context.getTracingAttributes().get("gen_ai.usage.input_tokens"));
        assertEquals(7L, context.getTracingAttributes().get("gen_ai.usage.output_tokens"));
    }

    @Test
    void setFetchResponseAttributesFromBufferReusesTheAssembledStreamingResponseWhenAvailable() throws Exception {
        ProxyContext context = streamingContext();
        // ResponseItemController's GET streaming path extracts and keeps the terminal frame the same way
        // ResponsesController's POST streaming path does (ExtractTerminalResponseFn), so this must be
        // read instead of rescanning the raw multi-frame SSE buffer below for the terminal event.
        context.setAssembledStreamingResponseTree(
                ProxyUtil.MAPPER.readTree("{\"id\":\"dial-1\",\"model\":\"gpt-4\",\"status\":\"completed\"}"));
        Buffer rawUpstreamFrames = Buffer.buffer("""
                event: response.in_progress
                data: {"type":"response.in_progress","response":{"id":"upstream-1"}}
                """);

        GenAiTraceAttributes.setFetchResponseAttributes(context, rawUpstreamFrames, "dial-1");

        assertEquals("gpt-4", context.getTracingAttributes().get("gen_ai.response.model"));
        assertEquals("completed", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setUsageAttributesUsesTypedValues() {
        ProxyContext context = context(proxy(enabledSettings()));
        TokenUsage usage = new TokenUsage();
        usage.setPromptTokens(10);
        usage.setCompletionTokens(20);
        usage.setTotalTokens(30);
        PromptTokensDetails promptDetails = new PromptTokensDetails();
        promptDetails.setCachedTokens(2);
        promptDetails.setCacheWriteTokens(3);
        usage.setPromptTokensDetails(promptDetails);
        CompletionTokensDetails completionDetails = new CompletionTokensDetails();
        completionDetails.setReasoningTokens(4);
        usage.setCompletionTokensDetails(completionDetails);

        try (var ignored = mockStatic(Span.class)) {
            Span span = mock(Span.class);
            when(span.isRecording()).thenReturn(true);
            when(Span.current()).thenReturn(span);

            GenAiTraceAttributes.setUsageAttributes(context, usage);

            verify(span).setAttribute(longKey("gen_ai.usage.input_tokens"), 10L);
            verify(span).setAttribute(longKey("gen_ai.usage.output_tokens"), 20L);
            verify(span).setAttribute(longKey("gen_ai.usage.cache_read.input_tokens"), 2L);
            verify(span).setAttribute(longKey("gen_ai.usage.cache_write.input_tokens"), 3L);
            verify(span).setAttribute(longKey("gen_ai.usage.reasoning.output_tokens"), 4L);
            verify(span).setAttribute(longKey("dial.usage.total_tokens"), 30L);
        }
    }

    @Test
    void setFailureStatusIgnoresRequestsWithoutGenAiOperation() {
        // every endpoint shares respond(...) - a resource 404 is not a failed GenAI operation
        ProxyContext context = context(proxy(enabledSettings()));

        GenAiTraceAttributes.setFailureStatus(context, 404);

        assertFalse(context.getTracingAttributes().containsKey("gen_ai.response.status"));
    }

    @Test
    void initializeFallsThroughAnOversizedConversationHeader() {
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("thread-id", "x".repeat(257));
        headers.add("x-session-id", "session-1");
        when(request.headers()).thenReturn(headers);
        ProxyContext context = context(proxy(enabledSettings()), request);

        GenAiTraceAttributes.initialize(context);

        assertEquals("session-1", context.getTracingAttributes().get("gen_ai.conversation.id"));
    }

    @Test
    void initializeReadsTraceparentOfUnknownVersion() {
        // a higher version still carries the ids in the first three fields, and may append more
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.getHeader("traceparent"))
                .thenReturn("02-11111111111111111111111111111111-2222222222222222-01-what-comes-next");
        ProxyContext context = context(proxy(enabledSettings()), request);

        GenAiTraceAttributes.initialize(context);

        assertEquals("2222222222222222", context.getTracingAttributes().get("dial.request.parent_span.id"));
    }

    @Test
    void setResponseAttributesReusesTheTerminalResponsesFrameAlreadyExtracted() throws Exception {
        ProxyContext context = streamingContext();
        // ExtractTerminalResponseFn kept this tree while streaming; the buffered frames are never scanned again,
        // and the tree is never reparsed from the string built from it for the log
        context.setAssembledStreamingResponseTree(
                ProxyUtil.MAPPER.readTree("{\"id\":\"resp-1\",\"model\":\"gpt-4\",\"status\":\"incomplete\"}"));
        Buffer body = Buffer.buffer("""
                event: response.completed
                data: {"type":"response.completed","response":{"id":"scanned","status":"completed"}}
                """);

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_RESPONSES, body);

        assertEquals("resp-1", context.getTracingAttributes().get("gen_ai.response.id"));
        assertEquals("gpt-4", context.getTracingAttributes().get("gen_ai.response.model"));
        assertEquals("incomplete", context.getTracingAttributes().get("gen_ai.response.status"));
    }

    @Test
    void setResponseAttributesSkipsBodyTooLargeToTrace() {
        // an embeddings body of vectors is not worth a parse of its own - the outcome still is
        ProxyContext context = context(proxy(enabledSettings()));
        Buffer body = Buffer.buffer("{\"model\":\"embed\",\"data\":\"" + "0".repeat(600 * 1024) + "\"}");

        GenAiTraceAttributes.setResponseAttributes(context, InterfaceType.OPENAI_EMBEDDINGS, body);

        assertFalse(context.getTracingAttributes().containsKey("gen_ai.response.model"));
        assertEquals("completed", context.getTracingAttributes().get("gen_ai.response.status"));
        assertEquals("openai_embeddings", context.getTracingAttributes().get("dial.api"));
    }

    private static ProxyContext context(Proxy proxy, HttpServerRequest request) {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(new Key());
        ProxyContext context = new ProxyContext(proxy, request, apiKeyData, null,
                "11111111111111111111111111111111", "3333333333333333", "01");
        // gen_ai.response.status is derived from the status the client sees
        when(context.getResponse().getStatusCode()).thenReturn(200);
        return context;
    }

    private static ProxyContext context(Proxy proxy) {
        return context(proxy, mock(HttpServerRequest.class, RETURNS_DEEP_STUBS));
    }

    /**
     * A context whose upstream response is SSE: the content type is the only streaming signal.
     */
    private static ProxyContext streamingContext() {
        ProxyContext context = context(proxy(enabledSettings()));
        HttpClientResponse proxyResponse = mock(HttpClientResponse.class);
        when(proxyResponse.statusCode()).thenReturn(200);
        when(proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("text/event-stream");
        context.setProxyResponse(proxyResponse);
        return context;
    }

    private static Proxy proxy(TracingSettings settings) {
        Proxy proxy = mock(Proxy.class, RETURNS_DEEP_STUBS);
        when(proxy.getTracingSettings()).thenReturn(settings);
        return proxy;
    }

    private static TracingSettings enabledSettings() {
        return TracingSettings.from(new JsonObject()
                .put("genAiSpanAttributes", true)
                .put("conversationIdHeaders", new JsonArray()
                        .add("x-claude-code-session-id")
                        .add("thread-id")
                        .add("x-session-id")
                        .add("x-dial-client-channel-id")
                        .add("X-CONVERSATION-ID")));
    }
}
