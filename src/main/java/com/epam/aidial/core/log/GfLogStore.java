package com.epam.aidial.core.log;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.MergeChunks;
import com.epam.aidial.core.util.ProxyUtil;
import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogEntry;
import com.epam.deltix.gflog.api.LogFactory;
import com.epam.deltix.gflog.api.LogLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;

@Slf4j
public class GfLogStore implements LogStore {

    private static final Log LOGGER = LogFactory.getLog("aidial.log");
    private final Vertx vertx;

    public GfLogStore(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public void save(ProxyContext context) {
        if (!LOGGER.isInfoEnabled() || !context.getRequest().method().equals(HttpMethod.POST)) {
            return;
        }

        vertx.executeBlocking(() -> doSave(context));
    }

    private Void doSave(ProxyContext context) {
        LogEntry entry = LOGGER.log(LogLevel.INFO);

        try {
            append(context, entry);
            entry.commit();
        } catch (Throwable e) {
            entry.abort();
            log.warn("Can't save log: {}", e.getMessage());
        }
        return null;
    }

    private void append(ProxyContext context, LogEntry entry) throws JsonProcessingException {
        HttpServerRequest request = context.getRequest();
        HttpServerResponse response = context.getResponse();

        append(entry, "{\"apiType\":\"DialOpenAI\",\"chat\":{\"id\":\"", false);
        append(entry, request.getHeader(Proxy.HEADER_CONVERSATION_ID), true);

        append(entry, "\"},\"project\":{\"id\":\"", false);
        append(entry, context.getProject(), true);

        append(entry, "\"},\"user\":{\"id\":\"", false);
        append(entry, context.getUserHash(), true);

        append(entry, "\",\"title\":\"", false);
        append(entry, request.getHeader(Proxy.HEADER_JOB_TITLE), true);
        append(entry, "\"}", false);

        TokenUsage tokenUsage = context.getTokenUsage();
        if (tokenUsage != null) {
            append(entry, ",\"token_usage\":{", false);
            append(entry, "\"completion_tokens\":", false);
            append(entry, Long.toString(tokenUsage.getCompletionTokens()), true);
            append(entry, ",\"prompt_tokens\":", false);
            append(entry, Long.toString(tokenUsage.getPromptTokens()), true);
            append(entry, ",\"total_tokens\":", false);
            append(entry, Long.toString(tokenUsage.getTotalTokens()), true);
            if (tokenUsage.getCost() != null) {
                append(entry, ",\"deployment_price\":", false);
                append(entry, tokenUsage.getCost().toString(), true);
            }
            if (tokenUsage.getAggCost() != null) {
                append(entry, ",\"price\":", false);
                append(entry, tokenUsage.getAggCost().toString(), true);
            }
            append(entry, "}", false);
        }

        append(entry, ",\"deployment\":\"", false);
        append(entry, context.getDeployment().getName(), true);
        append(entry, "\"", false);

        String sourceDeployment = context.getSourceDeployment();
        if (sourceDeployment != null) {
            append(entry, ",\"parent_deployment\":\"", false);
            append(entry, sourceDeployment, true);
            append(entry, "\"", false);
        }

        List<String> executionPath = context.getExecutionPath();
        if (executionPath != null) {
            append(entry, ",\"execution_path\":", false);
            append(entry, ProxyUtil.MAPPER.writeValueAsString(executionPath), false);
        }

        if (!context.isSecuredApiKey()) {
            append(entry, ",\"assembled_response\":\"", false);
            Buffer responseBody = context.getResponseBody();
            if (isStreamingResponse(responseBody)) {
                append(entry, assembleStreamingResponse(responseBody), true);
            } else {
                append(entry, responseBody);
            }
            append(entry, "\"", false);
        }

        append(entry, ",\"trace\":{\"trace_id\":\"", false);
        append(entry, context.getTraceId(), true);

        append(entry, "\",\"core_span_id\":\"", false);
        append(entry, context.getSpanId(), true);

        String parentSpanId = context.getParentSpanId();
        if (parentSpanId != null) {
            append(entry, "\",\"core_parent_span_id\":\"", false);
            append(entry, context.getParentSpanId(), true);
        }

        append(entry, "\"},\"request\":{\"protocol\":\"", false);
        append(entry, request.version().alpnName().toUpperCase(), true);

        append(entry, "\",\"method\":\"", false);
        append(entry, request.method().name(), true);

        append(entry, "\",\"uri\":\"", false);
        append(entry, request.uri(), true);

        append(entry, "\",\"time\":\"", false);
        append(entry, formatTimestamp(context.getRequestTimestamp()), true);

        if (!context.isSecuredApiKey()) {
            append(entry, "\",\"body\":\"", false);
            append(entry, context.getRequestBody());
        }

        append(entry, "\"},\"response\":{\"status\":\"", false);
        append(entry, Integer.toString(response.getStatusCode()), true);

        if (context.getUpstreamRoute() != null) {
            append(entry, "\",\"upstream_uri\":\"", false);
            append(entry, context.getUpstreamRoute().get().getEndpoint(), true);
        }

        if (!context.isSecuredApiKey()) {
            append(entry, "\",\"body\":\"", false);
            append(entry, context.getResponseBody());
        }

        append(entry, "\"}}", false);
    }

    private static void append(LogEntry entry, Buffer buffer) {
        if (buffer != null) {
            byte[] bytes = buffer.getBytes();
            String chars = new String(bytes, StandardCharsets.UTF_8); // not efficient, but ok for now
            append(entry, chars, true);
        }
    }

    private static void append(LogEntry entry, String chars, boolean escape) {
        if (chars == null) {
            return;
        }

        if (!escape) {
            entry.append(chars);
            return;
        }

        int i;
        int j;

        for (i = 0, j = 0; i < chars.length(); i++) {
            final char c = chars.charAt(i);
            final char e = escape(c);

            if (e != 0) {
                entry.append(chars, j, i);
                entry.append('\\');
                entry.append(e);
                j = i + 1;
            }
        }

        entry.append(chars, j, i);
    }

    private static char escape(char c) {
        return switch (c) {
            case '\b' -> 'b';
            case '\f' -> 'f';
            case '\n' -> 'n';
            case '\r' -> 'r';
            case '\t' -> 't';
            case '"', '\\', '/' -> c;
            default -> 0;
        };
    }

    private static String formatTimestamp(long timestamp) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.of("UTC"))
                .format(DateTimeFormatter.ISO_DATE_TIME);
    }

    /**
     * Assembles streaming response into a single one.
     * The assembling process merges chunks of the streaming response one by one using separator: <code>\n*data: *</code>
     *
     * @param response byte array response to be assembled.
     * @return assembled streaming response
     */
    static String assembleStreamingResponse(Buffer response) {
        try (Scanner scanner = new Scanner(new ByteBufInputStream(response.getByteBuf()))) {
            ObjectNode last = null;
            JsonNode usage = null;
            JsonNode statistics = null;
            JsonNode systemFingerprint = null;
            JsonNode model = null;
            JsonNode choices = null;
            // each chunk is separated by one or multiple new lines with the prefix: 'data:'
            scanner.useDelimiter("\n*data: *");
            while (scanner.hasNext()) {
                String chunk = scanner.next();
                if (chunk.startsWith("[DONE]")) {
                    break;
                }
                ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(chunk);
                if (tree.get("usage") != null) {
                    usage = MergeChunks.merge(usage, tree.get("usage"));
                }
                if (tree.get("statistics") != null) {
                    statistics = MergeChunks.merge(statistics, tree.get("statistics"));
                }
                if (tree.get("system_fingerprint") != null) {
                    systemFingerprint = tree.get("system_fingerprint");
                }
                if (model == null && tree.get("model") != null) {
                    model = tree.get("model");
                }
                last = tree;
                if (tree.get("choices") != null) {
                    choices = MergeChunks.merge(choices, tree.get("choices"));
                }
            }

            if (last == null) {
                log.warn("no chunk is found in streaming response");
                return "{}";
            }

            ObjectNode result = ProxyUtil.MAPPER.createObjectNode();
            result.set("id", last.get("id"));
            result.put("object", "chat.completion");
            result.set("created", last.get("created"));
            result.set("model", model);

            if (usage != null) {
                MergeChunks.removeIndices(usage);
                result.set("usage", usage);
            }
            if (statistics != null) {
                MergeChunks.removeIndices(statistics);
                result.set("statistics", statistics);
            }
            if (systemFingerprint != null) {
                result.set("system_fingerprint", systemFingerprint);
            }

            if (choices == null) {
                // error
                return ProxyUtil.convertToString(result);
            }
            MergeChunks.removeIndices(choices);
            result.set("choices", choices);
            return ProxyUtil.convertToString(result);
        } catch (Throwable e) {
            log.warn("Can't assemble streaming response", e);
            return "{}";
        }
    }

    private static void mergeCustomContent(ObjectNode merged, ObjectNode cur) {
        mergeArrays(merged, cur, "attachments");
        mergeArrays(merged, cur, "controls");
        mergeArrays(merged, cur, "stages");
        mergeObjects(merged, cur, "state");
    }

    private static void mergeObjects(ObjectNode merged, ObjectNode cur, String propName) {
        ObjectNode mergedObject = (ObjectNode) merged.get(propName);
        ObjectNode curObject = (ObjectNode) cur.get(propName);
        if (curObject != null && !curObject.isEmpty()) {
            if (mergedObject == null) {
                merged.set(propName, curObject);
            } else {
                mergedObject.setAll(curObject);
            }
        }
    }

    private static void mergeArrays(ObjectNode merged, ObjectNode cur, String propName) {
        ArrayNode curArray = (ArrayNode) cur.get(propName);
        if (curArray != null && !curArray.isEmpty()) {
            ArrayNode mergedArray = (ArrayNode) merged.get(propName);
            if (mergedArray == null) {
                merged.set(propName, curArray);
            } else {
                int index = nextIndex(mergedArray);
                for (int i = 0; i < curArray.size(); i++) {
                    ObjectNode objectNode = (ObjectNode) curArray.get(i);
                    objectNode.put("index", index++);
                    mergedArray.add(objectNode);
                }
            }
        }
    }

    private static int nextIndex(ArrayNode array) {
        int max = array.get(0).get("index").asInt();
        for (int i = 1; i < array.size(); i++) {
            int index = array.get(i).get("index").asInt();
            if (index >  max) {
                max = index;
            }
        }
        return max + 1;
    }

    /**
     * Determines if the given response is streaming.
     * <p>
     *     Streaming response is spitted into chunks. Each chunk starts with a new line and has a prefix: 'data:'.
     *     For example<br/>
     *     <code>
     *         data: {content: "some text"}
     *         \n\ndata: {content: "some text"}
     *         \ndata: [DONE]
     *     </code>
     * </p>
     *
     * @param response byte array response.
     * @return <code>true</code> is the response is streaming.
     */
    static boolean isStreamingResponse(Buffer response) {
        int i = 0;
        for (; i < response.length(); i++) {
            byte b = response.getByte(i);
            if (!Character.isWhitespace(b)) {
                break;
            }
        }
        String dataToken = "data:";
        int j = 0;
        for (; i < response.length() && j < dataToken.length(); i++, j++) {
            if (dataToken.charAt(j) != response.getByte(i)) {
                break;
            }
        }
        return j == dataToken.length();
    }
}
