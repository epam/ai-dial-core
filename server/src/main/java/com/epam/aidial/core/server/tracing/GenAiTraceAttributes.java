package com.epam.aidial.core.server.tracing;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.sse.SseEvent;
import com.epam.aidial.core.server.sse.SseEventListener;
import com.epam.aidial.core.server.sse.SseParser;
import com.epam.aidial.core.server.token.CompletionTokensDetails;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.opentelemetry.api.common.AttributeKey.booleanKey;
import static io.opentelemetry.api.common.AttributeKey.doubleKey;
import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringArrayKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

@Slf4j
public final class GenAiTraceAttributes {
    /**
     * Any version but the invalid {@code ff}: a version Core does not know still carries the trace and the
     * parent id in the first three fields, and W3C requires a parser to read those and ignore what follows.
     */
    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "(?!ff)[0-9a-f]{2}-(?!0{32})([0-9a-f]{32})-(?!0{16})([0-9a-f]{16})-[0-9a-f]{2}(?:-.*)?");
    private static final String CONVERSATION_ID_ATTRIBUTE = "gen_ai.conversation.id";
    private static final String PARENT_SPAN_ATTRIBUTE = "dial.request.parent_span.id";
    private static final String RESPONSE_STATUS_ATTRIBUTE = "gen_ai.response.status";
    private static final String API_ATTRIBUTE = "dial.api";
    private static final String RESPONSES_EVENT_PREFIX = "response.";
    private static final String DEFAULT_SSE_EVENT = "message";
    private static final Set<String> TERMINAL_RESPONSES_EVENTS =
            Set.of("response.completed", "response.incomplete", "response.failed", "response.cancelled");
    private static final Set<String> ANTHROPIC_ATTRIBUTE_EVENTS = Set.of("message_start", "message_delta");
    private static final int MAX_ATTRIBUTE_LENGTH = 256;
    private static final int MAX_ATTRIBUTE_VALUES = 32;
    /**
     * Above this a response body is not parsed for attributes. Tracing's parse is its own, on top of the one
     * token accounting already does, and an embeddings body is dominated by vectors that carry no attribute.
     */
    private static final int MAX_TRACED_BODY_BYTES = 512 * 1024;
    private static final int SSE_LINE_BUFFER_SIZE = 1024;

    private GenAiTraceAttributes() {
    }

    public static void initialize(ProxyContext context) {
        enrich(context, () -> {
            set(context, stringKey(CONVERSATION_ID_ATTRIBUTE), resolveConversationId(context));
            set(context, stringKey(PARENT_SPAN_ATTRIBUTE), parseParentSpanId(context));
        });
    }

    public static void setRequestAttributes(ProxyContext context, InterfaceType type, ObjectNode request) {
        enrich(context, () -> collectRequestAttributes(context, type, request));
    }

    private static void collectRequestAttributes(ProxyContext context, InterfaceType type, ObjectNode request) {
        setOperationAttributes(context, type, operationName(type));
        set(context, stringKey("gen_ai.request.model"), text(request.get("model")));
        set(context, booleanKey("gen_ai.request.stream"), booleanValue(request.get("stream")));
        switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> {
                set(context, longKey("gen_ai.request.max_tokens"),
                        longValue(firstNode(request, "max_tokens", "max_completion_tokens")));
                set(context, doubleKey("gen_ai.request.temperature"), doubleValue(request.get("temperature")));
                set(context, doubleKey("gen_ai.request.top_p"), doubleValue(request.get("top_p")));
                set(context, stringArrayKey("gen_ai.request.stop_sequences"), stringList(request.get("stop")));
                set(context, longKey("gen_ai.request.choice.count"), longValue(request.get("n")));
                set(context, doubleKey("gen_ai.request.frequency_penalty"), doubleValue(request.get("frequency_penalty")));
                set(context, doubleKey("gen_ai.request.presence_penalty"), doubleValue(request.get("presence_penalty")));
                set(context, longKey("gen_ai.request.seed"), longValue(request.get("seed")));
                set(context, stringKey("gen_ai.request.reasoning.level"), text(request.get("reasoning_effort")));
            }
            case OPENAI_EMBEDDINGS -> set(context, stringArrayKey("gen_ai.request.encoding_formats"),
                    stringList(firstNode(request, "encoding_format", "encoding_formats")));
            case OPENAI_RESPONSES -> {
                set(context, longKey("gen_ai.request.max_tokens"), longValue(request.get("max_output_tokens")));
                set(context, doubleKey("gen_ai.request.temperature"), doubleValue(request.get("temperature")));
                set(context, doubleKey("gen_ai.request.top_p"), doubleValue(request.get("top_p")));
                set(context, stringKey("gen_ai.request.previous_response.id"),
                        text(request.get("previous_response_id")));
                set(context, stringKey("gen_ai.request.reasoning.level"),
                        text(request.path("reasoning").get("effort")));
            }
            case ANTHROPIC_MESSAGES -> {
                set(context, longKey("gen_ai.request.max_tokens"), longValue(request.get("max_tokens")));
                set(context, doubleKey("gen_ai.request.temperature"), doubleValue(request.get("temperature")));
                set(context, doubleKey("gen_ai.request.top_p"), doubleValue(request.get("top_p")));
                set(context, stringArrayKey("gen_ai.request.stop_sequences"), stringList(request.get("stop_sequences")));
            }
            default -> {
                // a future interface type gets the common attributes only, never a failed request
            }
        }
    }

    public static void setResponseAttributes(ProxyContext context, InterfaceType type, Buffer responseBody) {
        setResponseAttributes(context, type, responseBody, null);
    }

    /**
     * @param responseId DIAL's own response id, or null to keep the body's. A streamed body is buffered before
     *                   {@code ReplaceResponseIdFn} rewrites it, so the buffered bytes still carry the upstream id.
     */
    public static void setResponseAttributes(ProxyContext context, InterfaceType type, Buffer responseBody, String responseId) {
        enrich(context, () -> {
            setOperationAttributes(context, type, operationName(type));
            applyResponseFields(context, type, responseTree(context, type, responseBody), responseId);
            collectUpstreamCacheAttributes(context);
        });
    }

    /**
     * For a caller that already parsed the body into a tree for its own purposes (id rewriting, terminal-result
     * detection, token usage) - skips {@link #responseTree} entirely instead of reparsing the same bytes.
     *
     * @param responseId overrides the body's own id when the caller knows the client-facing id; null keeps the body's.
     */
    public static void setResponseAttributes(ProxyContext context, InterfaceType type, JsonNode responseTree, String responseId) {
        enrich(context, () -> {
            setOperationAttributes(context, type, operationName(type));
            applyResponseFields(context, type, responseTree, responseId);
            collectUpstreamCacheAttributes(context);
        });
    }

    /**
     * @param responseId overrides the body's own id when the caller knows the client-facing id; null keeps the body's.
     */
    private static void applyResponseFields(ProxyContext context, InterfaceType type, JsonNode response, String responseId) {
        set(context, stringKey("gen_ai.response.id"), responseId == null ? text(response.get("id")) : clamp(responseId));
        set(context, stringKey("gen_ai.response.model"), text(response.get("model")));
        set(context, stringArrayKey("gen_ai.response.finish_reasons"), finishReasons(response, type));
        set(context, stringKey(RESPONSE_STATUS_ATTRIBUTE), responseStatus(context, response));
    }

    /**
     * What the upstream asked Core to cache, and whether Core acted on it. The upstream reporting a breakpoint path
     * is not enough on its own - Core also has to hold a hash for that path, so the two are separate facts.
     */
    private static void collectUpstreamCacheAttributes(ProxyContext context) {
        UpstreamRoute route = context.getUpstreamRoute();
        String breakpointPath = route == null ? null : route.getCacheBreakpointPath();
        if (breakpointPath == null) {
            // the upstream asked for nothing to be cached, so "stored" would be noise rather than a false
            return;
        }
        set(context, stringKey("dial.upstream.cache.breakpoint_path"), clamp(breakpointPath));
        set(context, booleanKey("dial.upstream.cache.stored"), route.isCacheEntryStored());
    }

    /**
     * @param responseId DIAL's own response id. A streamed body is buffered before {@code ReplaceResponseIdFn}
     *                   rewrites it, so the buffered bytes still carry the upstream id.
     */
    public static void setFetchResponseAttributes(ProxyContext context, Buffer responseBody, String responseId) {
        enrich(context, () ->
                setFetchResponseAttributes(context, responseTree(context, InterfaceType.OPENAI_RESPONSES, responseBody), responseId));
    }

    /**
     * For a caller that already parsed the body (e.g. for id rewriting or terminal-result detection) -
     * skips {@link #responseTree} entirely instead of reparsing the same bytes.
     *
     * @param responseId DIAL's own response id. A streamed body is buffered before {@code ReplaceResponseIdFn}
     *                   rewrites it, so the buffered bytes still carry the upstream id.
     */
    public static void setFetchResponseAttributes(ProxyContext context, JsonNode response, String responseId) {
        enrich(context, () -> {
            setOperationAttributes(context, InterfaceType.OPENAI_RESPONSES, "fetch_response");
            applyResponseFields(context, InterfaceType.OPENAI_RESPONSES, response, responseId);
            collectUsageAttributes(context, tokenUsage(response.get("usage")));
        });
    }

    /**
     * The outcome of a GenAI request that ended before any upstream response was collected - a rate-limit
     * rejection, a connect failure. Those paths never reach {@link #setResponseAttributes}, so without this the
     * span carries request attributes and no outcome at all, which reads the same as a request that never
     * finished. A status published from a real response always wins.
     *
     * <p>Gated on {@code dial.api}, which is published as soon as the request body is parsed: every endpoint
     * shares {@code respond}, and a {@code gen_ai.response.status} on a resource 404 or on a request rejected
     * before its GenAI surface was known describes an operation that never existed.</p>
     */
    public static void setFailureStatus(ProxyContext context, int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        enrich(context, () -> {
            Map<String, Object> attributes = context.getTracingAttributes();
            if (attributes.containsKey(API_ATTRIBUTE) && !attributes.containsKey(RESPONSE_STATUS_ATTRIBUTE)) {
                set(context, stringKey(RESPONSE_STATUS_ATTRIBUTE), "failed");
            }
        });
    }

    /**
     * The phases Core already times per request. {@code upstream_header_ms} is time to the upstream's response
     * headers, not to its first token - a DIAL application or interceptor flushes headers immediately and only
     * then starts generating, so the two differ exactly where it matters. Real time-to-first-chunk would need
     * the first SSE data frame timed, and belongs in {@code gen_ai.response.time_to_first_chunk}.
     */
    public static void setLatencyAttributes(ProxyContext context) {
        enrich(context, () -> {
            set(context, longKey("dial.latency.client_body_ms"),
                    phaseMs(context.getRequestTimestamp(), context.getRequestBodyTimestamp()));
            set(context, longKey("dial.latency.upstream_connect_ms"),
                    phaseMs(context.getRequestBodyTimestamp(), context.getProxyConnectTimestamp()));
            set(context, longKey("dial.latency.upstream_header_ms"),
                    phaseMs(context.getProxyConnectTimestamp(), context.getProxyResponseTimestamp()));
            set(context, longKey("dial.latency.upstream_body_ms"),
                    phaseMs(context.getProxyResponseTimestamp(), context.getResponseBodyTimestamp()));
        });
    }

    /**
     * @return null when the phase never happened - a request rejected before an upstream leaves its bound at 0 -
     *         or when the clock stepped backwards, as {@code calculateOperationDurationMs} also guards against.
     */
    private static Long phaseMs(long from, long to) {
        return from == 0 || to == 0 || to < from ? null : to - from;
    }

    /**
     * Mirrors the {@code X-UPSTREAM-ATTEMPTS} response header onto Core's own span, so a retried request is
     * visible in a trace without correlating it back to the client's headers.
     */
    public static void setUpstreamAttempts(ProxyContext context, int attemptCount) {
        enrich(context, () -> set(context, longKey("dial.upstream.attempts"), (long) attemptCount));
    }

    public static void setUsageAttributes(ProxyContext context, TokenUsage usage) {
        enrich(context, () -> collectUsageAttributes(context, usage));
    }

    private static void collectUsageAttributes(ProxyContext context, TokenUsage usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }
        set(context, longKey("gen_ai.usage.input_tokens"), usage.getPromptTokens());
        set(context, longKey("gen_ai.usage.output_tokens"), usage.getCompletionTokens());
        PromptTokensDetails promptDetails = usage.getPromptTokensDetails();
        if (promptDetails != null) {
            set(context, longKey("gen_ai.usage.cache_read.input_tokens"), promptDetails.getCachedTokens());
            set(context, longKey("gen_ai.usage.cache_write.input_tokens"), promptDetails.getCacheWriteTokens());
        }
        CompletionTokensDetails completionDetails = usage.getCompletionTokensDetails();
        if (completionDetails != null) {
            set(context, longKey("gen_ai.usage.reasoning.output_tokens"), completionDetails.getReasoningTokens());
        }
        set(context, longKey("dial.usage.total_tokens"), usage.getTotalTokens());
    }

    private static void setOperationAttributes(ProxyContext context, InterfaceType type, String operation) {
        set(context, stringKey("gen_ai.operation.name"), operation);
        set(context, stringKey(API_ATTRIBUTE), switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> "openai_chat_completions";
            case OPENAI_EMBEDDINGS -> "openai_embeddings";
            case OPENAI_RESPONSES -> "openai_responses";
            case ANTHROPIC_MESSAGES -> "anthropic_messages";
        });
        set(context, stringKey("gen_ai.provider.name"), "dial");
    }

    private static String operationName(InterfaceType type) {
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS, ANTHROPIC_MESSAGES -> "chat";
            case OPENAI_EMBEDDINGS -> "embeddings";
            case OPENAI_RESPONSES -> "generate_content";
        };
    }

    private static List<String> finishReasons(JsonNode response, InterfaceType type) {
        List<String> result = new ArrayList<>();
        if (type == InterfaceType.OPENAI_CHAT_COMPLETIONS) {
            for (JsonNode choice : response.path("choices")) {
                if (result.size() >= MAX_ATTRIBUTE_VALUES) {
                    break;
                }
                addText(result, choice.get("finish_reason"));
            }
        } else if (type == InterfaceType.ANTHROPIC_MESSAGES) {
            addText(result, response.get("stop_reason"));
        } else if (type == InterfaceType.OPENAI_RESPONSES) {
            addText(result, response.path("incomplete_details").get("reason"));
        }
        return result.isEmpty() ? null : result;
    }

    private static String responseStatus(ProxyContext context, JsonNode response) {
        String status = text(response.get("status"));
        if (status != null) {
            return status;
        }
        // the client-facing status, as everywhere else on this path: DIAL may rewrite a 200 upstream,
        // and a request short-circuited before any upstream call must not read as a success
        int statusCode = context.getResponse().getStatusCode();
        return statusCode < 200 || statusCode >= 300 ? "failed" : "completed";
    }

    private static JsonNode responseTree(ProxyContext context, InterfaceType type, Buffer responseBody) {
        if (!isEventStream(context)) {
            return parse(responseBody);
        }
        return switch (type) {
            // the analytics log merges the same streamed body once per request and keeps the tree it built;
            // reuse it instead of reparsing the string serialized from it for the log
            case OPENAI_CHAT_COMPLETIONS -> {
                context.assembledChatCompletionsResponse();
                JsonNode tree = context.getAssembledStreamingResponseTree();
                yield tree == null ? MissingNode.getInstance() : tree;
            }
            // the terminal frame ExtractTerminalResponseFn already kept while streaming; a run that failed or
            // was cancelled leaves none, and only then is the buffered stream scanned for it
            case OPENAI_RESPONSES -> {
                JsonNode tree = context.getAssembledStreamingResponseTree();
                yield tree == null ? responsesEvent(responseBody) : tree;
            }
            // CollectMessagesTokenUsageFn already kept a running id/model/stop_reason view while streaming,
            // from the same events; only a run that never reached message_start leaves none
            case ANTHROPIC_MESSAGES -> {
                JsonNode tree = context.getAssembledStreamingResponseTree();
                yield tree == null ? anthropicResponse(responseBody) : tree;
            }
            case OPENAI_EMBEDDINGS -> parse(responseBody);
        };
    }

    /**
     * @return {@link MissingNode} for a body too large to be worth tracing's own parse - every attribute it
     *         carries is optional, and the status fallback needs no body at all.
     */
    private static JsonNode parse(Buffer body) {
        return body == null || body.length() > MAX_TRACED_BODY_BYTES
                ? MissingNode.getInstance()
                : JsonUtil.tryParse(body.getBytes());
    }

    private static JsonNode responsesEvent(Buffer responseBody) {
        JsonNode[] terminal = new JsonNode[1];
        forEachSseEvent(responseBody, (name, data) -> {
            if (terminal[0] != null || !(isUnlabelled(name) || TERMINAL_RESPONSES_EVENTS.contains(name))) {
                return;
            }
            JsonNode event = JsonUtil.tryParse(data.getBytes(StandardCharsets.UTF_8));
            String type = text(event.get("type"));
            if (type != null && TERMINAL_RESPONSES_EVENTS.contains(type)) {
                terminal[0] = terminalResponse(event, type);
            }
        });
        return terminal[0] == null ? ProxyUtil.MAPPER.createObjectNode() : terminal[0];
    }

    private static JsonNode terminalResponse(JsonNode event, String type) {
        // path, not get: a truncated or error-only terminal frame carries no response object
        JsonNode response = event.path("response");
        if (text(response.get("status")) != null) {
            return response;
        }
        // the client-facing status is 200 - DIAL did stream a body - so the event type is the only
        // thing left that can tell a failed or cancelled run from a completed one
        ObjectNode derived = response instanceof ObjectNode object ? object : ProxyUtil.MAPPER.createObjectNode();
        return derived.put("status", type.substring(RESPONSES_EVENT_PREFIX.length()));
    }

    private static JsonNode anthropicResponse(Buffer responseBody) {
        ObjectNode result = ProxyUtil.MAPPER.createObjectNode();
        forEachSseEvent(responseBody, (name, data) -> {
            if (!isUnlabelled(name) && !ANTHROPIC_ATTRIBUTE_EVENTS.contains(name)) {
                return;
            }
            JsonNode event = JsonUtil.tryParse(data.getBytes(StandardCharsets.UTF_8));
            String type = text(event.get("type"));
            if ("message_start".equals(type)) {
                // path, not get: a truncated frame carries no message object
                JsonNode message = event.path("message");
                result.set("id", message.get("id"));
                result.set("model", message.get("model"));
            } else if ("message_delta".equals(type)) {
                result.set("stop_reason", event.path("delta").get("stop_reason"));
            }
        });
        return result;
    }

    /**
     * An upstream that labels its frames lets the scan skip the JSON parse on everything but the two or three
     * frames that carry attributes; one that emits bare {@code data:} lines forces a parse to find out.
     */
    private static boolean isUnlabelled(String eventName) {
        return eventName == null || DEFAULT_SSE_EVENT.equals(eventName);
    }

    /**
     * Hands each frame to the consumer and retains nothing. Collecting the whole stream instead would allocate
     * a JsonNode per frame, on the event loop, to read one or two fields out of the last of them.
     */
    private static void forEachSseEvent(Buffer responseBody, BiConsumer<String, String> consumer) {
        SseParser parser = new SseParser(SSE_LINE_BUFFER_SIZE, new SseEventListener() {
            @Override
            public void onEvent(SseEvent event) {
                String data = event.getData();
                if (data != null && !data.isBlank() && !"[DONE]".equals(data.trim())) {
                    consumer.accept(event.getEvent(), data);
                }
            }

            @Override
            public void onComment(String comment) {
                // not an event
            }

            @Override
            public void onComplete() {
                // nothing to flush
            }
        });
        try {
            parser.parse(responseBody);
            parser.finish();
        } finally {
            // pooled Netty buffer
            parser.close();
        }
    }

    private static boolean isEventStream(ProxyContext context) {
        HttpClientResponse response = context.getProxyResponse();
        String contentType = response == null ? null : response.getHeader(HttpHeaders.CONTENT_TYPE);
        return Strings.CI.contains(contentType, "text/event-stream");
    }

    private static String resolveConversationId(ProxyContext context) {
        // TracingSettings drops blank and credential header names
        for (String header : context.getTracingSettings().conversationIdHeaders()) {
            String value = context.getRequest().headers().get(header);
            if (value != null && !value.isBlank()) {
                String conversationId = value.trim();
                if (conversationId.length() <= MAX_ATTRIBUTE_LENGTH) {
                    return conversationId;
                }
                // an oversized value is no usable id, and truncating one would correlate unrelated requests,
                // so the next header on the priority list still gets its turn
            }
        }
        return null;
    }

    private static String parseParentSpanId(ProxyContext context) {
        String traceparent = context.getRequest().getHeader("traceparent");
        if (traceparent == null) {
            return null;
        }
        Matcher matcher = TRACEPARENT_PATTERN.matcher(traceparent);
        return matcher.matches() ? matcher.group(2) : null;
    }

    private static TokenUsage tokenUsage(JsonNode usage) {
        try {
            return ProxyUtil.MAPPER.convertValue(usage, TokenUsage.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isEnabled(ProxyContext context) {
        TracingSettings settings = context.getTracingSettings();
        return settings != null && settings.genAiSpanAttributes();
    }

    /**
     * Opt-in observability runs on the critical path - {@code collectTokenUsage} is called synchronously
     * before the client response is completed - so a tracing failure must never fail a request.
     */
    private static void enrich(ProxyContext context, Runnable enrichment) {
        if (!isEnabled(context)) {
            return;
        }
        try {
            enrichment.run();
        } catch (Throwable e) {
            log.warn("Failed to set GenAI trace attributes", e);
        }
    }

    private static <T> void set(ProxyContext context, AttributeKey<T> key, T value) {
        if (value == null) {
            return;
        }
        context.getTracingAttributes().put(key.getKey(), value);
        Span span = Span.current();
        if (span.isRecording()) {
            span.setAttribute(key, value);
        }
    }

    private static Long longValue(JsonNode node) {
        return node != null && node.isNumber() && node.canConvertToLong() ? node.longValue() : null;
    }

    private static Double doubleValue(JsonNode node) {
        return node != null && node.isNumber() ? node.doubleValue() : null;
    }

    private static Boolean booleanValue(JsonNode node) {
        return node != null && node.isBoolean() ? node.booleanValue() : null;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (values.size() >= MAX_ATTRIBUTE_VALUES) {
                    break;
                }
                addText(values, item);
            }
        } else {
            addText(values, node);
        }
        return values.isEmpty() ? null : values;
    }

    private static JsonNode firstNode(ObjectNode request, String... names) {
        for (String name : names) {
            JsonNode node = request.get(name);
            if (node != null && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        String value = node == null || !node.isTextual() ? null : node.asText();
        return value == null || value.isBlank() ? null : clamp(value);
    }

    /**
     * Model names, response ids and stop sequences are caller- or upstream-controlled and unbounded,
     * and every attribute is replayed onto each log record of the request.
     */
    private static String clamp(String value) {
        return value.length() <= MAX_ATTRIBUTE_LENGTH ? value : value.substring(0, MAX_ATTRIBUTE_LENGTH);
    }

    private static void addText(List<String> values, JsonNode node) {
        String value = text(node);
        if (value != null) {
            values.add(value);
        }
    }
}
