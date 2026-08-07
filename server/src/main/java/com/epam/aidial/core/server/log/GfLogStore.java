package com.epam.aidial.core.server.log;

import com.epam.aidial.core.server.token.CompletionTokensDetails;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.token.UsagePerModel;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.deltix.gflog.api.Log;
import com.epam.deltix.gflog.api.LogEntry;
import com.epam.deltix.gflog.api.LogFactory;
import com.epam.deltix.gflog.api.LogLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

@Slf4j
public class GfLogStore implements LogStore {

    private static final Log LOGGER = LogFactory.getLog("aidial.log");
    // Max allowed size is 4 mb for request/response body
    private static final int MAX_BODY_SIZE_BYTES = 4 * 1024 * 1024;
    // Max allowed size for a single collected request header value
    private static final int MAX_HEADER_VALUE_LENGTH = 4 * 1024;
    // Max allowed size for a single collected claim value, matching the header limit
    private static final int MAX_CLAIM_VALUE_LENGTH = 4 * 1024;

    private static final String[] CONTROL_SYMBOLS = new String[0x1F + 1];

    static {
        for (int i = 0; i < CONTROL_SYMBOLS.length; i++) {
            String s = String.format("%04x", i);
            CONTROL_SYMBOLS[i] = s.toUpperCase();
        }
    }

    private final ExecutorService executor;
    private final AnalyticsSettings settings;

    public GfLogStore(AnalyticsSettings settings) {
        this.settings = settings;
        BasicThreadFactory factory = BasicThreadFactory.builder()
                .namingPattern("gflog-store-%d")
                .daemon(true)
                .build();
        executor = Executors.newSingleThreadExecutor(factory);
    }

    @Override
    public void save(AnalyticsLogContext logContext) {
        if (!LOGGER.isInfoEnabled() || !"POST".equals(logContext.getRequestMethod())) {
            return;
        }
        // run the process of saving analytics logs in a single thread in order to reduce memory footprint.
        // Gflog allocates a buffer per thread: the more threads the more buffers need to be allocated.
        executor.submit(() -> doSave(logContext));
    }

    private Void doSave(AnalyticsLogContext logContext) {
        // Note. Any logs must be written by slf4j logger:
        // 1. before the prompt logger starts writing any message OR
        // 2. after the prompt logger ends writing messages

        LogEntry entry = LOGGER.log(LogLevel.INFO);
        try {
            append(logContext, entry);
            entry.commit();
        } catch (Throwable e) {
            entry.abort();
            log.warn("Can't save log due to the error", e);
        }
        return null;
    }

    @VisibleForTesting
    void append(AnalyticsLogContext logContext, LogEntry entry) throws JsonProcessingException {
        append(entry, "{\"apiType\":\"DialOpenAI\",\"chat\":{\"id\":\"", false);
        append(entry, logContext.getConversationId(), true);

        append(entry, "\"},\"project\":{\"id\":\"", false);
        append(entry, logContext.getProject(), true);

        append(entry, "\"},\"user\":{\"id\":\"", false);
        append(entry, logContext.getUserHash(), true);

        append(entry, "\",\"title\":\"", false);
        append(entry, logContext.getJobTitle(), true);
        append(entry, "\"}", false);

        if (settings.claimsEnabled()) {
            appendClaims(logContext, entry);
        }

        if (settings.collectHeaders()) {
            appendHeaders(logContext, entry);
        }

        TokenUsage tokenUsage = logContext.getTokenUsage();
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
                append(entry, ",\"cache_write_tokens\":", false);
                append(entry, Long.toString(details.getCacheWriteTokens()), true);
                append(entry, "}", false);
            }
            if (tokenUsage.getCompletionTokensDetails() != null) {
                CompletionTokensDetails details = tokenUsage.getCompletionTokensDetails();
                append(entry, ",\"completion_tokens_details\":{\"reasoning_tokens\":", false);
                append(entry, Long.toString(details.getReasoningTokens()), true);
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

        List<UsagePerModel> usagePerModel = logContext.getUsagePerModel();
        if (usagePerModel != null && !usagePerModel.isEmpty()) {
            append(entry, ",\"usage_per_model\":", false);
            append(entry, ProxyUtil.MAPPER.writeValueAsString(usagePerModel), false);
        }

        if (logContext.getDeploymentName() != null) {
            append(entry, ",\"deployment\":\"", false);
            append(entry, logContext.getDeploymentName(), true);
            append(entry, "\"", false);
        }

        if (logContext.getParentDeployment() != null) {
            append(entry, ",\"parent_deployment\":\"", false);
            append(entry, logContext.getParentDeployment(), true);
            append(entry, "\"", false);
        }

        List<String> executionPath = logContext.getExecutionPath();
        if (executionPath != null) {
            append(entry, ",\"execution_path\":", false);
            append(entry, ProxyUtil.MAPPER.writeValueAsString(executionPath), false);
        }

        append(entry, ",\"operation_duration_ms\":", false);
        append(entry, Long.toString(logContext.getOperationDurationMs()), true);

        if (!logContext.isSecuredApiKey()) {
            append(entry, ",\"assembled_response\":\"", false);
            if (logContext.getAssembledStreamingResponse() != null) {
                appendBody(entry, Buffer.buffer(logContext.getAssembledStreamingResponse()));
            } else {
                appendBody(entry, logContext.getResponseBody());
            }
            append(entry, "\"", false);
        }

        append(entry, ",\"trace\":{\"trace_id\":\"", false);
        append(entry, logContext.getTraceId(), true);

        append(entry, "\",\"core_span_id\":\"", false);
        append(entry, logContext.getSpanId(), true);

        String parentSpanId = logContext.getParentSpanId();
        if (parentSpanId != null) {
            append(entry, "\",\"core_parent_span_id\":\"", false);
            append(entry, logContext.getParentSpanId(), true);
        }

        append(entry, "\"},\"request\":{\"protocol\":\"", false);
        append(entry, logContext.getRequestProtocol(), true);

        append(entry, "\",\"method\":\"", false);
        append(entry, logContext.getRequestMethod(), true);

        append(entry, "\",\"uri\":\"", false);
        append(entry, logContext.getRequestUri(), true);

        append(entry, "\",\"time\":\"", false);
        append(entry, formatTimestamp(logContext.getRequestTimestamp()), true);

        if (!logContext.isSecuredApiKey()) {
            append(entry, "\",\"body\":\"", false);
            appendBody(entry, logContext.getRequestBody());
        }

        append(entry, "\"},\"response\":{\"status\":\"", false);
        append(entry, Integer.toString(logContext.getResponseStatusCode()), true);

        if (logContext.getUpstreamEndpoint() != null) {
            append(entry, "\",\"upstream_uri\":\"", false);
            append(entry, logContext.getUpstreamEndpoint(), true);
        }

        if (!logContext.isSecuredApiKey()) {
            append(entry, "\",\"body\":\"", false);
            appendBody(entry, logContext.getResponseBody());
        }

        append(entry, "\"}}", false);
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
    void appendClaims(AnalyticsLogContext context, LogEntry entry) throws JsonProcessingException {
        append(entry, ",\"claims\":{", false);
        MutableBoolean firstMember = new MutableBoolean(true);
        Set<String> written = mayRepeatNames() ? new HashSet<>() : null;
        if (settings.collectClaims()) {
            if (appendStringMember(entry, "user_id", context.getUserId(), firstMember)) {
                reserveName(written, "user_id");
            }
            List<String> roles = context.getUserRoles();
            if (roles != null) {
                appendSeparator(entry, firstMember);
                append(entry, "\"roles\":", false);
                append(entry, ProxyUtil.MAPPER.writeValueAsString(roles), false);
                reserveName(written, "roles");
            }
            if (appendStringMember(entry, "user_display_name", context.getUserDisplayName(), firstMember)) {
                reserveName(written, "user_display_name");
            }
        }
        appendAllowedClaims(context.getUserClaims(), entry, firstMember, written);
        append(entry, "}", false);
    }

    /**
     * Whether two sources may contribute the same member name, which would produce a duplicate JSON key. Within a
     * single source names are unique already: the allowlist is deduplicated when parsed, and so are the claim
     * payload's own field names.
     */
    private boolean mayRepeatNames() {
        boolean allowlisted = !settings.claimsAllowlist().isEmpty();
        return settings.collectClaims() && (allowlisted || settings.collectAllClaims())
                || settings.collectAllClaims() && allowlisted;
    }

    /**
     * Claims the member name, returning false when it was already written and must not be repeated. A null set means
     * no two sources are active, so nothing can collide.
     */
    private static boolean reserveName(@Nullable Set<String> written, String name) {
        return written == null || written.add(name);
    }

    private void appendAllowedClaims(ObjectNode claims, LogEntry entry, MutableBoolean firstMember,
                                     @Nullable Set<String> written) throws JsonProcessingException {
        if (claims == null) {
            return;
        }
        if (settings.collectAllClaims()) {
            Iterator<String> names = claims.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                appendClaimMember(entry, name, claims.get(name), firstMember, written);
            }
        }
        for (AnalyticsSettings.ClaimPath allowed : settings.claimsAllowlist()) {
            appendClaimMember(entry, allowed.name(), resolveClaim(claims, allowed.segments()), firstMember, written);
        }
    }

    private static JsonNode resolveClaim(ObjectNode claims, List<String> path) {
        JsonNode node = claims;
        for (String segment : path) {
            node = node.get(segment);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    private static void appendClaimMember(LogEntry entry, String name, JsonNode value, MutableBoolean firstMember,
                                          @Nullable Set<String> written) throws JsonProcessingException {
        if (value == null || value.isNull() || !reserveName(written, name)) {
            return;
        }
        appendSeparator(entry, firstMember);
        append(entry, "\"", false);
        append(entry, name, true);
        append(entry, "\":", false);
        String json = ProxyUtil.MAPPER.writeValueAsString(value);
        if (json.length() <= MAX_CLAIM_VALUE_LENGTH) {
            // already valid JSON, so it must not be escaped again
            append(entry, json, false);
        } else {
            // cutting JSON in half would not parse, so an oversized value is reported as a truncated string instead
            append(entry, "\"", false);
            append(entry, json.substring(0, MAX_CLAIM_VALUE_LENGTH), true);
            append(entry, ">>\"", false);
        }
    }

    @VisibleForTesting
    void appendHeaders(AnalyticsLogContext context, LogEntry entry) {
        append(entry, ",\"headers\":{", false);
        Map<String, List<String>> headers = context.getRequestHeaders();
        MutableBoolean firstMember = new MutableBoolean(true);
        if (headers != null) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                String name = header.getKey();
                if (!isHeaderCollectable(name)) {
                    continue;
                }
                appendSeparator(entry, firstMember);
                append(entry, "\"", false);
                append(entry, name, true);
                append(entry, "\":\"", false);
                String value = String.join(", ", header.getValue());
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
        }
        append(entry, "}", false);
    }

    private boolean isHeaderCollectable(String name) {
        List<Pattern> allowlist = settings.headersAllowlist();
        if (allowlist != null && !matchesAny(allowlist, name)) {
            return false;
        }
        return !matchesAny(settings.headersBlacklist(), name);
    }

    private static boolean matchesAny(List<Pattern> patterns, String name) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true when the member was written, false when there was no value to write.
     */
    private static boolean appendStringMember(LogEntry entry, String name, String value, MutableBoolean firstMember) {
        if (value == null) {
            return false;
        }
        appendSeparator(entry, firstMember);
        append(entry, "\"", false);
        append(entry, name, true);
        append(entry, "\":\"", false);
        append(entry, value, true);
        append(entry, "\"", false);
        return true;
    }

    private static void appendSeparator(LogEntry entry, MutableBoolean firstMember) {
        if (firstMember.isTrue()) {
            firstMember.setFalse();
        } else {
            append(entry, ",", false);
        }
    }

    private static void appendBody(LogEntry entry, Buffer buffer) {
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
}
