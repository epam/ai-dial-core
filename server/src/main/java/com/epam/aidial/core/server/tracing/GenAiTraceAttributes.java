package com.epam.aidial.core.server.tracing;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Tracing;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.token.CompletionTokensDetails;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import org.apache.commons.lang3.Strings;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GenAiTraceAttributes {
    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "00-(?!0{32})([0-9a-f]{32})-(?!0{16})([0-9a-f]{16})-([0-9a-f]{2})");
    private static final String CONVERSATION_ID_ATTRIBUTE = "gen_ai.conversation.id";
    private static final String PARENT_SPAN_ATTRIBUTE = "dial.request.parent_span.id";
    private static final int MAX_ATTRIBUTE_LENGTH = 256;

    private GenAiTraceAttributes() {
    }

    public static void initialize(ProxyContext context) {
        setStringAttribute(context, CONVERSATION_ID_ATTRIBUTE, resolveConversationId(context));
        setStringAttribute(context, PARENT_SPAN_ATTRIBUTE, parseParentSpanId(context));
    }

    public static void setRequestAttributes(ProxyContext context, InterfaceType type, ObjectNode request) {
        if (!isEnabled(context)) {
            return;
        }
        setOperationAttributes(context, type);
        setStringAttribute(context, "gen_ai.request.model", text(request.get("model")));
        setBooleanAttribute(context, "gen_ai.request.stream", request.get("stream"));
        switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> {
                setLongAttribute(context, "gen_ai.request.max_tokens",
                        firstNode(request, "max_tokens", "max_completion_tokens"));
                setDoubleAttribute(context, "gen_ai.request.temperature", request.get("temperature"));
                setDoubleAttribute(context, "gen_ai.request.top_p", request.get("top_p"));
                setStringArrayAttribute(context, "gen_ai.request.stop_sequences", request.get("stop"));
                setLongAttribute(context, "gen_ai.request.choice.count", request.get("n"));
                setDoubleAttribute(context, "gen_ai.request.frequency_penalty", request.get("frequency_penalty"));
                setDoubleAttribute(context, "gen_ai.request.presence_penalty", request.get("presence_penalty"));
                setLongAttribute(context, "gen_ai.request.seed", request.get("seed"));
                setStringAttribute(context, "gen_ai.request.reasoning.level", text(request.get("reasoning_effort")));
            }
            case OPENAI_EMBEDDINGS -> setStringArrayAttribute(context, "gen_ai.request.encoding_formats",
                    firstNode(request, "encoding_format", "encoding_formats"));
            case OPENAI_RESPONSES -> {
                setLongAttribute(context, "gen_ai.request.max_tokens", request.get("max_output_tokens"));
                setDoubleAttribute(context, "gen_ai.request.temperature", request.get("temperature"));
                setDoubleAttribute(context, "gen_ai.request.top_p", request.get("top_p"));
                setStringAttribute(context, "gen_ai.request.previous_response.id",
                        text(request.get("previous_response_id")));
                setStringAttribute(context, "gen_ai.request.reasoning.level",
                        text(request.path("reasoning").get("effort")));
            }
            case ANTHROPIC_MESSAGES -> {
                setLongAttribute(context, "gen_ai.request.max_tokens", request.get("max_tokens"));
                setDoubleAttribute(context, "gen_ai.request.temperature", request.get("temperature"));
                setDoubleAttribute(context, "gen_ai.request.top_p", request.get("top_p"));
                setStringArrayAttribute(context, "gen_ai.request.stop_sequences", request.get("stop_sequences"));
            }
            default -> throw new IllegalArgumentException("Unsupported interface type: " + type);
        }
    }

    public static void setResponseAttributes(ProxyContext context, InterfaceType type, Buffer responseBody) {
        if (!isEnabled(context)) {
            return;
        }
        setOperationAttributes(context, type);
        setResponseAttributes(context, type, responseTree(context, type, responseBody));
    }

    private static void setResponseAttributes(ProxyContext context, InterfaceType type, JsonNode response) {
        setStringAttribute(context, "gen_ai.response.id", text(response.get("id")));
        setStringAttribute(context, "gen_ai.response.model", text(response.get("model")));
        List<String> finishReasons = finishReasons(response, type);
        setStringArrayAttribute(context, "gen_ai.response.finish_reasons", finishReasons);
        setStringAttribute(context, "gen_ai.response.status", responseStatus(context, response, finishReasons));
    }

    public static void setFetchResponseAttributes(ProxyContext context, Buffer responseBody) {
        if (!isEnabled(context)) {
            return;
        }
        setStringAttribute(context, "gen_ai.operation.name", "fetch_response");
        setStringAttribute(context, "dial.api", "openai_responses");
        setStringAttribute(context, "gen_ai.provider.name", "dial");
        JsonNode response = responseTree(context, InterfaceType.OPENAI_RESPONSES, responseBody);
        setResponseAttributes(context, InterfaceType.OPENAI_RESPONSES, response);
        setUsageAttributes(context, tokenUsage(response.get("usage")));
    }

    public static void setUsageAttributes(ProxyContext context, TokenUsage usage) {
        if (usage == null || usage.isEmpty() || !isEnabled(context)) {
            return;
        }
        setLongAttribute(context, "gen_ai.usage.input_tokens", usage.getPromptTokens());
        setLongAttribute(context, "gen_ai.usage.output_tokens", usage.getCompletionTokens());
        PromptTokensDetails promptDetails = usage.getPromptTokensDetails();
        if (promptDetails != null) {
            setLongAttribute(context, "gen_ai.usage.cache_read.input_tokens", promptDetails.getCachedTokens());
            setLongAttribute(context, "gen_ai.usage.cache_write.input_tokens", promptDetails.getCacheWriteTokens());
        }
        CompletionTokensDetails completionDetails = usage.getCompletionTokensDetails();
        if (completionDetails != null) {
            setLongAttribute(context, "gen_ai.usage.reasoning.output_tokens",
                    completionDetails.getReasoningTokens());
        }
        setLongAttribute(context, "dial.usage.total_tokens", usage.getTotalTokens());
    }

    private static void setOperationAttributes(ProxyContext context, InterfaceType type) {
        setStringAttribute(context, "gen_ai.operation.name", switch (type) {
            case OPENAI_CHAT_COMPLETIONS, ANTHROPIC_MESSAGES -> "chat";
            case OPENAI_EMBEDDINGS -> "embeddings";
            case OPENAI_RESPONSES -> "generate_content";
        });
        setStringAttribute(context, "dial.api", switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> "openai_chat_completions";
            case OPENAI_EMBEDDINGS -> "openai_embeddings";
            case OPENAI_RESPONSES -> "openai_responses";
            case ANTHROPIC_MESSAGES -> "anthropic_messages";
        });
        setStringAttribute(context, "gen_ai.provider.name", "dial");
    }

    private static List<String> finishReasons(JsonNode response, InterfaceType type) {
        List<String> result = new ArrayList<>();
        if (type == InterfaceType.OPENAI_CHAT_COMPLETIONS) {
            for (JsonNode choice : response.path("choices")) {
                addText(result, choice.get("finish_reason"));
            }
        } else if (type == InterfaceType.ANTHROPIC_MESSAGES) {
            addText(result, response.get("stop_reason"));
        } else if (type == InterfaceType.OPENAI_RESPONSES) {
            addText(result, response.path("incomplete_details").get("reason"));
        }
        return result.isEmpty() ? null : result;
    }

    private static String responseStatus(ProxyContext context, JsonNode response, List<String> finishReasons) {
        String status = text(response.get("status"));
        if (status != null) {
            return status;
        }
        HttpClientResponse proxyResponse = context.getProxyResponse();
        int statusCode = proxyResponse == null ? 200 : proxyResponse.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            return "failed";
        }
        if (finishReasons != null && (finishReasons.contains("length")
                || finishReasons.contains("content_filter") || finishReasons.contains("max_tokens"))) {
            return "incomplete";
        }
        return "completed";
    }

    private static JsonNode responseTree(ProxyContext context, InterfaceType type, Buffer responseBody) {
        if (!isEventStream(context, responseBody)) {
            return JsonUtil.tryParse(responseBody.getBytes());
        }
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> JsonUtil.tryParse(AnalyticsLogContext
                    .assembleStreamingChatCompletionsResponse(responseBody)
                    .getBytes(StandardCharsets.UTF_8));
            case OPENAI_RESPONSES -> responsesEvent(responseBody);
            case ANTHROPIC_MESSAGES -> anthropicResponse(responseBody);
            case OPENAI_EMBEDDINGS -> JsonUtil.tryParse(responseBody.getBytes());
        };
    }

    private static JsonNode responsesEvent(Buffer responseBody) {
        for (String data : sseData(responseBody)) {
            JsonNode event = JsonUtil.tryParse(data.getBytes(StandardCharsets.UTF_8));
            String type = text(event.get("type"));
            if ("response.completed".equals(type) || "response.incomplete".equals(type)
                    || "response.failed".equals(type) || "response.cancelled".equals(type)) {
                return event.get("response");
            }
        }
        return ProxyUtil.MAPPER.createObjectNode();
    }

    private static JsonNode anthropicResponse(Buffer responseBody) {
        ObjectNode result = ProxyUtil.MAPPER.createObjectNode();
        for (String data : sseData(responseBody)) {
            JsonNode event = JsonUtil.tryParse(data.getBytes(StandardCharsets.UTF_8));
            String type = text(event.get("type"));
            if ("message_start".equals(type)) {
                JsonNode message = event.get("message");
                result.set("id", message.get("id"));
                result.set("model", message.get("model"));
            } else if ("message_delta".equals(type)) {
                result.set("stop_reason", event.path("delta").get("stop_reason"));
            }
        }
        return result;
    }

    private static List<String> sseData(Buffer responseBody) {
        List<String> result = new ArrayList<>();
        for (String data : responseBody.toString().split("(?m)^data: *")) {
            String value = data.trim();
            if (!value.isEmpty() && !"[DONE]".equals(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static boolean isEventStream(ProxyContext context, Buffer responseBody) {
        if (isEventStream(context.getProxyResponse())) {
            return true;
        }
        String body = responseBody.toString();
        return body.startsWith("data:") || body.startsWith("event:") || body.contains("\ndata:");
    }

    private static boolean isEventStream(HttpClientResponse response) {
        String contentType = response == null ? null : response.getHeader(HttpHeaders.CONTENT_TYPE);
        return Strings.CI.contains(contentType, "text/event-stream");
    }

    private static String resolveConversationId(ProxyContext context) {
        if (!isEnabled(context)) {
            return null;
        }
        Tracing tracing = context.getConfig().getTracing();
        for (String header : tracing.getConversationIdHeaders()) {
            if (header == null || header.isBlank()) {
                continue;
            }
            List<String> values = context.getRequest().headers().getAll(header);
            if (values.size() > 1) {
                return null;
            }
            if (values.isEmpty()) {
                continue;
            }
            String value = values.get(0);
            if (value == null || value.isBlank()) {
                continue;
            }
            String conversationId = value.trim();
            return conversationId.length() <= MAX_ATTRIBUTE_LENGTH ? conversationId : null;
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
        if (usage == null || !usage.isObject()) {
            return null;
        }
        try {
            return ProxyUtil.MAPPER.convertValue(usage, TokenUsage.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isEnabled(ProxyContext context) {
        Config config = context.getConfig();
        return config != null && config.getTracing().isGenAiSpanAttributes();
    }

    private static boolean canSet(ProxyContext context, String key) {
        return isEnabled(context) && !context.getConfig().getTracing().isAttributeBlacklisted(key);
    }

    private static void setStringAttribute(ProxyContext context, String key, String value) {
        if (value == null || !canSet(context, key)) {
            return;
        }
        context.getTracingAttributes().put(key, value);
        Span span = Span.current();
        if (span.isRecording()) {
            span.setAttribute(key, value);
        }
    }

    private static void setLongAttribute(ProxyContext context, String key, Long value) {
        if (value == null || !canSet(context, key)) {
            return;
        }
        context.getTracingAttributes().put(key, value);
        Span span = Span.current();
        if (span.isRecording()) {
            span.setAttribute(key, value);
        }
    }

    private static void setLongAttribute(ProxyContext context, String key, JsonNode node) {
        if (node != null && node.isNumber() && node.canConvertToLong()) {
            setLongAttribute(context, key, node.longValue());
        }
    }

    private static void setDoubleAttribute(ProxyContext context, String key, Double value) {
        if (value == null || !canSet(context, key)) {
            return;
        }
        context.getTracingAttributes().put(key, value);
        Span span = Span.current();
        if (span.isRecording()) {
            span.setAttribute(key, value);
        }
    }

    private static void setDoubleAttribute(ProxyContext context, String key, JsonNode node) {
        if (node != null && node.isNumber()) {
            setDoubleAttribute(context, key, node.doubleValue());
        }
    }

    private static void setBooleanAttribute(ProxyContext context, String key, Boolean value) {
        if (value == null || !canSet(context, key)) {
            return;
        }
        context.getTracingAttributes().put(key, value);
        Span span = Span.current();
        if (span.isRecording()) {
            span.setAttribute(key, value);
        }
    }

    private static void setBooleanAttribute(ProxyContext context, String key, JsonNode node) {
        if (node != null && node.isBoolean()) {
            setBooleanAttribute(context, key, node.booleanValue());
        }
    }

    private static void setStringArrayAttribute(ProxyContext context, String key, List<String> value) {
        if (value == null || value.isEmpty() || !canSet(context, key)) {
            return;
        }
        context.getTracingAttributes().put(key, value);
        Span span = Span.current();
        if (span.isRecording()) {
            span.setAttribute(AttributeKey.stringArrayKey(key), value);
        }
    }

    private static void setStringArrayAttribute(ProxyContext context, String key, JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            setStringArrayAttribute(context, key, List.of(node.asText()));
            return;
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                addText(values, item);
            }
            setStringArrayAttribute(context, key, values);
        }
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
        return value == null || value.isBlank() ? null : value;
    }

    private static void addText(List<String> values, JsonNode node) {
        String value = text(node);
        if (value != null) {
            values.add(value);
        }
    }
}
