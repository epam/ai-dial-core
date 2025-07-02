package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.MergeChunks;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

@Slf4j
public class LogbackLogStore implements LogStore {

    private static final Logger PROMPT_LOGGER = LoggerFactory.getLogger("aidial.log");
    // Max allowed size is 4 mb for request/response body
    private static final int MAX_BODY_SIZE_BYTES = 4 * 1024 * 1024;

    private final Vertx vertx;

    public LogbackLogStore(Vertx vertx) {
        this.vertx = vertx;
    }

    @Override
    public void save(ProxyContext context) {
        if (!PROMPT_LOGGER.isInfoEnabled() || !context.getRequest().method().equals(HttpMethod.POST)) {
            return;
        }

        vertx.executeBlocking(() -> doSave(context));
    }

    private Void doSave(ProxyContext context) {
        // Note. Any logs must be written by slf4j logger:
        // 1. before the prompt logger starts writing any message OR
        // 2. after the prompt logger ends writing messages

        // Any new items must be added to the section below
        // prepare items to be written by the prompt logger
        Buffer responseBody = context.getResponseBody();
        String assembledStreamingResponse = null;
        if (isStreamingResponse(responseBody) && !exceedLimit(responseBody)) {
            assembledStreamingResponse = assembleStreamingResponse(responseBody);
        }
        // end

        try {
            String logMessage = buildLogMessage(context, assembledStreamingResponse);
            PROMPT_LOGGER.info(logMessage);
        } catch (Throwable e) {
            log.warn("Can't save log due to the error", e);
        }
        return null;
    }

    private String buildLogMessage(ProxyContext context, String assembledStreamingResponse) throws JsonProcessingException {
        StringBuilder message = new StringBuilder();
        HttpServerRequest request = context.getRequest();
        HttpServerResponse response = context.getResponse();

        message.append("{\"apiType\":\"DialOpenAI\",\"chat\":{\"id\":\"");
        appendEscaped(message, context.getRequestHeader(Proxy.HEADER_CONVERSATION_ID));

        message.append("\"},\"project\":{\"id\":\"");
        appendEscaped(message, context.getProject());

        message.append("\"},\"user\":{\"id\":\"");
        appendEscaped(message, context.getUserHash());

        message.append("\",\"title\":\"");
        appendEscaped(message, context.getRequestHeader(Proxy.HEADER_JOB_TITLE));
        message.append("\"}");

        TokenUsage tokenUsage = context.getTokenUsage();
        if (tokenUsage != null) {
            message.append(",\"token_usage\":{");
            message.append("\"completion_tokens\":");
            message.append(tokenUsage.getCompletionTokens());
            message.append(",\"prompt_tokens\":");
            message.append(tokenUsage.getPromptTokens());
            message.append(",\"total_tokens\":");
            message.append(tokenUsage.getTotalTokens());
            if (tokenUsage.getPromptTokensDetails() != null) {
                PromptTokensDetails details = tokenUsage.getPromptTokensDetails();
                message.append(",\"prompt_token_details\":{\"cached_tokens\":");
                message.append(details.getCachedTokens());
                message.append("}");
            }
            if (tokenUsage.getCost() != null) {
                message.append(",\"deployment_price\":");
                message.append(tokenUsage.getCost());
            }
            if (tokenUsage.getAggCost() != null) {
                message.append(",\"price\":");
                message.append(tokenUsage.getAggCost());
            }
            message.append("}");
        }

        Deployment deployment = context.getDeployment();
        if (deployment != null) {
            message.append(",\"deployment\":\"");
            appendEscaped(message, deployment.getName());
            message.append("\"");
        }

        String parentDeployment = getParentDeployment(context);
        if (parentDeployment != null) {
            message.append(",\"parent_deployment\":\"");
            appendEscaped(message, parentDeployment);
            message.append("\"");
        }

        List<String> executionPath = context.getExecutionPath();
        if (executionPath != null) {
            message.append(",\"execution_path\":");
            message.append(ProxyUtil.MAPPER.writeValueAsString(executionPath));
        }

        if (!context.isSecuredApiKey()) {
            message.append(",\"assembled_response\":\"");
            if (assembledStreamingResponse != null) {
                appendEscaped(message, assembledStreamingResponse);
            } else {
                appendBuffer(message, context.getResponseBody());
            }
            message.append("\"");
        }

        message.append(",\"trace\":{\"trace_id\":\"");
        appendEscaped(message, context.getTraceId());

        message.append("\",\"core_span_id\":\"");
        appendEscaped(message, context.getSpanId());

        String parentSpanId = context.getParentSpanId();
        if (parentSpanId != null) {
            message.append("\",\"core_parent_span_id\":\"");
            appendEscaped(message, context.getParentSpanId());
        }

        message.append("\"},\"request\":{\"protocol\":\"");
        appendEscaped(message, request.version().alpnName().toUpperCase());

        message.append("\",\"method\":\"");
        appendEscaped(message, request.method().name());

        message.append("\",\"uri\":\"");
        appendEscaped(message, request.uri());

        message.append("\",\"time\":\"");
        appendEscaped(message, formatTimestamp(context.getRequestTimestamp()));

        if (!context.isSecuredApiKey()) {
            message.append("\",\"body\":\"");
            appendBuffer(message, context.getRequestBody());
        }

        message.append("\"},\"response\":{\"status\":\"");
        message.append(response.getStatusCode());

        Optional<String> upstreamEndpoint = Optional.ofNullable(context.getUpstreamRoute())
                .map(UpstreamRoute::get).map(Upstream::getEndpoint);
        if (upstreamEndpoint.isPresent()) {
            message.append("\",\"upstream_uri\":\"");
            appendEscaped(message, upstreamEndpoint.get());
        }

        if (!context.isSecuredApiKey()) {
            message.append("\",\"body\":\"");
            appendBuffer(message, context.getResponseBody());
        }

        message.append("\"}");
        message.append("}");

        return message.toString();
    }

    private void appendBuffer(StringBuilder message, Buffer buffer) {
        if (buffer == null) {
            return;
        }
        boolean largeBuffer = exceedLimit(buffer);
        if (largeBuffer) {
            buffer = buffer.slice(0, MAX_BODY_SIZE_BYTES);
        }
        byte[] bytes = buffer.getBytes();
        String chars = new String(bytes, StandardCharsets.UTF_8);
        appendEscaped(message, chars);
        if (largeBuffer) {
            // append a special marker that entry is cut off due to its large size
            message.append(">>");
        }
    }

    private void appendEscaped(StringBuilder message, String chars) {
        if (chars == null) {
            return;
        }

        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            char e = escape(c);

            if (e != 0) {
                message.append('\\');
                message.append(e);
            } else {
                message.append(c);
            }
        }
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

    private static boolean exceedLimit(Buffer body) {
        return body.length() > MAX_BODY_SIZE_BYTES;
    }

    /**
     * Assembles streaming response into a single one.
     * The assembling process merges chunks of the streaming response one by one using separator: <code>\n*data: *</code>
     *
     * @param response byte array response to be assembled.
     * @return assembled streaming response
     */
    @Nullable
    static String assembleStreamingResponse(@Nullable Buffer response) {
        if (response == null) {
            return null;
        }
        try (Scanner scanner = new Scanner(new ByteBufInputStream(response.getByteBuf()))) {
            ObjectNode last = null;
            JsonNode usage = null;
            JsonNode statistics = null;
            JsonNode systemFingerprint = null;
            JsonNode model = null;
            JsonNode choices = null;
            // each chunk is separated by one or multiple new lines with the prefix: 'data:' (except the first chunk)
            // chunks may contain `data:` inside chunk data, which may lead to incorrect parsing
            scanner.useDelimiter("(^data: *|\n+data: *)");
            while (scanner.hasNext()) {
                String chunk = scanner.next();
                if (chunk.startsWith("[DONE]")) {
                    break;
                }
                ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(chunk);
                usage = MergeChunks.merge(usage, tree.get("usage"));
                statistics = MergeChunks.merge(statistics, tree.get("statistics"));
                if (tree.get("system_fingerprint") != null) {
                    systemFingerprint = tree.get("system_fingerprint");
                }
                if (model == null && tree.get("model") != null) {
                    model = tree.get("model");
                }
                last = tree;
                choices = MergeChunks.merge(choices, tree.get("choices"));
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

            if (choices != null) {
                if (choices.isArray()) {
                    for (JsonNode choice : choices) {
                        MergeChunks.removeIndices(choice);
                        if (choice.isObject()) {
                            ObjectNode choiceObj = (ObjectNode) choice;
                            JsonNode delta = choiceObj.get("delta");
                            if (delta != null) {
                                choiceObj.set("message", delta);
                                choiceObj.remove("delta");
                            }
                        }
                    }
                }

                result.set("choices", choices);
            }
            return ProxyUtil.convertToString(result);
        } catch (Throwable e) {
            log.warn("Can't assemble streaming response", e);
            return "{}";
        }
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
    static boolean isStreamingResponse(@Nullable Buffer response) {
        if (response == null) {
            return false;
        }
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

    @VisibleForTesting
    static String getParentDeployment(ProxyContext context) {
        List<String> interceptors = context.getInterceptors();
        if (interceptors == null) {
            return context.getSourceDeployment();
        }
        // skip interceptors and return the deployment which called the current one
        List<String> executionPath = context.getExecutionPath();
        if (executionPath == null) {
            return null;
        }
        int i = executionPath.size() - 2;
        for (int j = interceptors.size() - 1; i >= 0 && j >= 0; i--, j--) {
            String deployment = executionPath.get(i);
            String interceptor = interceptors.get(j);
            if (!deployment.equals(interceptor)) {
                log.warn("Can't find parent deployment because interceptor path doesn't match: expected - {}, actual - {}", interceptor, deployment);
                return null;
            }
        }
        return i < 0 ? null : executionPath.get(i);
    }
}