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
import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogEntry;
import com.epam.deltix.gflog.api.LogFactory;
import com.epam.deltix.gflog.api.LogLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;

@Slf4j
public class GfLogStore implements LogStore {

    private static final Log LOGGER = LogFactory.getLog("aidial.log");
    // Max allowed size is 4 mb for request/response body
    private static final int MAX_BODY_SIZE_BYTES = 4 * 1024 * 1024;
    // Max allowed size for a single collected request header value
    private static final int MAX_HEADER_VALUE_LENGTH = 4 * 1024;

    private static final String[] CONTROL_SYMBOLS = new String[0x1F + 1];

    static {
        for (int i = 0; i < CONTROL_SYMBOLS.length; i++) {
            String s = String.format("%04x", i);
            CONTROL_SYMBOLS[i] = s.toUpperCase();
        }
    }

    private final ExecutorService executor;
    private final boolean collectClaims;
    private final boolean collectHeaders;
    private final Set<String> headersBlacklist;

    public GfLogStore(boolean collectClaims, boolean collectHeaders, Set<String> headersBlacklist) {
        this.collectClaims = collectClaims;
        this.collectHeaders = collectHeaders;
        this.headersBlacklist = headersBlacklist == null ? Set.of() : headersBlacklist;
        BasicThreadFactory factory = BasicThreadFactory.builder()
                .namingPattern("gflog-store-%d")
                .daemon(true)
                .build();
        executor = Executors.newSingleThreadExecutor(factory);
    }

    @Override
    public void save(ProxyContext context) {
        if (!LOGGER.isInfoEnabled() || !context.getRequest().method().equals(HttpMethod.POST)) {
            return;
        }
        // run the process of saving analytics logs in a single thread in order to reduce memory footprint.
        // Gflog allocates a buffer per thread: the more threads the more buffers need to be allocated.
        executor.submit(() -> doSave(context));
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

        LogEntry entry = LOGGER.log(LogLevel.INFO);
        try {
            append(context, entry, assembledStreamingResponse);
            entry.commit();
        } catch (Throwable e) {
            entry.abort();
            log.warn("Can't save log due to the error", e);
        }
        return null;
    }

    private void append(ProxyContext context, LogEntry entry, String assembledStreamingResponse) throws JsonProcessingException {
        HttpServerRequest request = context.getRequest();
        HttpServerResponse response = context.getResponse();

        append(entry, "{\"apiType\":\"DialOpenAI\",\"chat\":{\"id\":\"", false);
        append(entry, context.getRequestHeader(Proxy.HEADER_CONVERSATION_ID), true);

        append(entry, "\"},\"project\":{\"id\":\"", false);
        append(entry, context.getProject(), true);

        append(entry, "\"},\"user\":{\"id\":\"", false);
        append(entry, context.getUserHash(), true);

        append(entry, "\",\"title\":\"", false);
        append(entry, context.getRequestHeader(Proxy.HEADER_JOB_TITLE), true);
        append(entry, "\"}", false);

        if (collectClaims) {
            appendClaims(context, entry);
        }

        if (collectHeaders) {
            appendHeaders(context, entry);
        }

        TokenUsage tokenUsage = context.getTokenUsage();
        if (tokenUsage != null) {
            append(entry, ",\"token_usage\":{", false);
            append(entry, "\"completion_tokens\":", false);
            append(entry, Long.toString(tokenUsage.getCompletionTokens()), true);
            append(entry, ",\"prompt_tokens\":", false);
            append(entry, Long.toString(tokenUsage.getPromptTokens()), true);
            append(entry, ",\"total_tokens\":", false);
            append(entry, Long.toString(tokenUsage.getTotalTokens()), true);
            if (tokenUsage.getPromptTokensDetails() != null) {
                PromptTokensDetails details = tokenUsage.getPromptTokensDetails();
                append(entry, ",\"prompt_tokens_details\":{\"cached_tokens\":", false);
                append(entry, Long.toString(details.getCachedTokens()), true);
                append(entry, "}", false);
            }
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

        Deployment deployment = context.getDeployment();
        if (deployment != null) {
            append(entry, ",\"deployment\":\"", false);
            append(entry, deployment.getName(), true);
            append(entry, "\"", false);
        }

        String parentDeployment = getParentDeployment(context);
        if (parentDeployment != null) {
            append(entry, ",\"parent_deployment\":\"", false);
            append(entry, parentDeployment, true);
            append(entry, "\"", false);
        }

        List<String> executionPath = context.getExecutionPath();
        if (executionPath != null) {
            append(entry, ",\"execution_path\":", false);
            append(entry, ProxyUtil.MAPPER.writeValueAsString(executionPath), false);
        }

        if (!context.isSecuredApiKey()) {
            append(entry, ",\"assembled_response\":\"", false);
            if (assembledStreamingResponse != null) {
                append(entry, assembledStreamingResponse, true);
            } else {
                append(entry, context.getResponseBody());
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

        Optional<String> upstreamEndpoint = Optional.ofNullable(context.getUpstreamRoute())
                .map(UpstreamRoute::get).map(Upstream::getEndpoint);
        if (upstreamEndpoint.isPresent()) {
            append(entry, "\",\"upstream_uri\":\"", false);
            append(entry, upstreamEndpoint.get(), true);
        }

        if (!context.isSecuredApiKey()) {
            append(entry, "\",\"body\":\"", false);
            append(entry, context.getResponseBody());
        }

        append(entry, "\"}}", false);
    }

    private static void append(LogEntry entry, Buffer buffer) {
        if (buffer == null) {
            return;
        }
        boolean largeBuffer = exceedLimit(buffer);
        if (largeBuffer) {
            buffer = buffer.slice(0, MAX_BODY_SIZE_BYTES);
        }
        byte[] bytes = buffer.getBytes();
        String chars = new String(bytes, StandardCharsets.UTF_8); // not efficient, but ok for now
        append(entry, chars, true);
        if (largeBuffer) {
            // append a special marker that entry is cut off due to its large size
            append(entry, ">>", false);
        }
    }

    @VisibleForTesting
    static void append(LogEntry entry, String chars, boolean escape) {
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
                if (e == 'u') {
                    entry.append(CONTROL_SYMBOLS[c]);
                }
                j = i + 1;
            }
        }

        entry.append(chars, j, i);
    }

    @VisibleForTesting
    void appendClaims(ProxyContext context, LogEntry entry) throws JsonProcessingException {
        append(entry, ",\"claims\":{", false);
        boolean[] first = {true};
        appendStringMember(entry, "user_id", context.getUserId(), first);
        List<String> roles = context.getUserRoles();
        if (roles != null) {
            appendSeparator(entry, first);
            append(entry, "\"roles\":", false);
            append(entry, ProxyUtil.MAPPER.writeValueAsString(roles), false);
        }
        appendStringMember(entry, "project", context.getProject(), first);
        appendStringMember(entry, "user_hash", context.getUserHash(), first);
        appendStringMember(entry, "user_display_name", context.getUserDisplayName(), first);
        append(entry, "}", false);
    }

    @VisibleForTesting
    void appendHeaders(ProxyContext context, LogEntry entry) {
        append(entry, ",\"headers\":{", false);
        MultiMap headers = context.getRequest().headers();
        boolean[] first = {true};
        for (String name : headers.names()) {
            if (headersBlacklist.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            appendSeparator(entry, first);
            append(entry, "\"", false);
            append(entry, name, true);
            append(entry, "\":\"", false);
            String value = String.join(", ", headers.getAll(name));
            boolean truncated = value.length() > MAX_HEADER_VALUE_LENGTH;
            if (truncated) {
                value = value.substring(0, MAX_HEADER_VALUE_LENGTH);
            }
            append(entry, value, true);
            if (truncated) {
                // append a special marker that the value is cut off due to its large size
                append(entry, ">>", false);
            }
            append(entry, "\"", false);
        }
        append(entry, "}", false);
    }

    private static void appendStringMember(LogEntry entry, String name, String value, boolean[] first) {
        if (value == null) {
            return;
        }
        appendSeparator(entry, first);
        append(entry, "\"", false);
        append(entry, name, false);
        append(entry, "\":\"", false);
        append(entry, value, true);
        append(entry, "\"", false);
    }

    private static void appendSeparator(LogEntry entry, boolean[] first) {
        if (first[0]) {
            first[0] = false;
        } else {
            append(entry, ",", false);
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
            default -> c <= 0x1F ? 'u' : 0;
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
