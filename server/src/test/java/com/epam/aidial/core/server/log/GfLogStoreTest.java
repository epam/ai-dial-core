package com.epam.aidial.core.server.log;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.service.ResponsesApiClient;
import com.epam.aidial.core.server.token.CompletionTokensDetails;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.deltix.gflog.api.LogEntry;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GfLogStoreTest {

    @Test
    public void testGetParentDeployment_NoInterceptors() {
        String result = AnalyticsLogContext.getParentDeployment("app", null, null);

        assertEquals("app", result);
    }

    @Test
    public void testGetParentDeployment_DeploymentWithInterceptors1() {
        // app calls model with interceptors
        List<String> interceptors = List.of("interceptor1", "interceptor2");
        List<String> executionPath = List.of("app", "interceptor1", "interceptor2", "model");

        String result = AnalyticsLogContext.getParentDeployment(null, interceptors, executionPath);

        assertEquals("app", result);
    }

    @Test
    public void testGetParentDeployment_DeploymentWithInterceptors2() {
        // chat calls model with interceptors
        List<String> interceptors = List.of("interceptor1", "interceptor2");
        List<String> executionPath = List.of("interceptor1", "interceptor2", "model");

        String result = AnalyticsLogContext.getParentDeployment(null, interceptors, executionPath);

        assertNull(result);
    }

    @Test
    public void testGetParentDeployment_InterceptorPathMismatch() {
        // app calls model with interceptors but interceptor1 calls some dep1 in the middle using the same per request key
        List<String> interceptors = List.of("interceptor1", "interceptor2");
        List<String> executionPath = List.of("app", "interceptor1", "dep1", "interceptor2", "model");

        String result = AnalyticsLogContext.getParentDeployment(null, interceptors, executionPath);

        assertNull(result);
    }

    @SneakyThrows
    @Test
    public void testAppendAndEscape() {
        final int len = 120;
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) i);
        }
        String s = sb.toString();
        LogEntry entry = mock(LogEntry.class);
        StringBuilder buffer = new StringBuilder();
        when(entry.append(anyChar())).thenAnswer(cb -> {
            buffer.append((char) cb.getArgument(0));
            return null;
        });
        when(entry.append(anyString(), anyInt(), anyInt())).thenAnswer(cb -> {
            buffer.append((String) cb.getArgument(0), cb.getArgument(1), cb.getArgument(2));
            return null;
        });
        when(entry.append(anyString())).thenAnswer(cb -> {
            buffer.append((String) cb.getArgument(0));
            return null;
        });
        GfLogStore.append(entry, s, true);
        String expected = "\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007\\b\\t\\n\\u000B\\f\\r\\u000E\\u000F"
                + "\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017\\u0018\\u0019\\u001A\\u001B\\u001C\\"
                + "u001D\\u001E\\u001F !\\\"#$%&'()*+,-.\\/0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\\\]^_`abcdefghijklmnopqrstuvw";
        String actual = buffer.toString();
        assertEquals(expected, actual);
        assertDoesNotThrow(() -> ProxyUtil.MAPPER.readValue("\"" + actual + "\"", String.class));
    }

    @SneakyThrows
    @Test
    public void testAppendClaims() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(List.of("admin", "reader"));
        when(context.getUserDisplayName()).thenReturn("Jane \"Doe\"");

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(AnalyticsSettings.builder().collectClaims(true).build()).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertEquals("admin", claims.get("roles").get(0).asText());
        assertEquals("reader", claims.get("roles").get(1).asText());
        assertEquals("Jane \"Doe\"", claims.get("user_display_name").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendClaimsSkipsNullFields() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(null);
        when(context.getUserDisplayName()).thenReturn(null);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(AnalyticsSettings.builder().collectClaims(true).build()).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertFalse(claims.has("roles"));
        assertFalse(claims.has("user_display_name"));
    }

    @SneakyThrows
    @Test
    public void testAppendClaimsWithEmail() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(List.of("admin"));
        when(context.getUserDisplayName()).thenReturn("Jane Doe");
        when(context.getUserEmail()).thenReturn("jane.doe@example.com");

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(AnalyticsSettings.builder().collectClaims(true).collectEmail(true).build()).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertEquals("admin", claims.get("roles").get(0).asText());
        assertEquals("Jane Doe", claims.get("user_display_name").asText());
        assertEquals("jane.doe@example.com", claims.get("user_email").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendClaimsOmitsEmailWhenCollectEmailDisabled() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserEmail()).thenReturn("jane.doe@example.com");

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(AnalyticsSettings.builder().collectClaims(true).build()).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertFalse(claims.has("user_email"));
    }

    @SneakyThrows
    @Test
    public void testCollectEmailIsIndependentOfCollectClaims() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(List.of("admin"));
        when(context.getUserDisplayName()).thenReturn("Jane Doe");
        when(context.getUserEmail()).thenReturn("jane.doe@example.com");

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(AnalyticsSettings.builder().collectEmail(true).build()).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("jane.doe@example.com", claims.get("user_email").asText());
        assertFalse(claims.has("user_id"));
        assertFalse(claims.has("roles"));
        assertFalse(claims.has("user_display_name"));
    }

    @SneakyThrows
    @Test
    public void testAppendClaimsSkipsNullEmail() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserEmail()).thenReturn(null);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(AnalyticsSettings.builder().collectClaims(true).collectEmail(true).build()).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertFalse(claims.has("user_email"));
    }

    @SneakyThrows
    @Test
    public void testAppendHeadersAppliesBlacklistAndJoinsMultiValues() {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put("Authorization", List.of("Bearer secret"));
        headers.put("API-KEY", List.of("key-secret"));
        headers.put("X-Conversation-Id", List.of("conv-1"));
        headers.put("Accept", List.of("text/plain", "application/json"));

        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getRequestHeaders()).thenReturn(headers);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(AnalyticsSettings.builder().collectHeaders(true)
                .headersBlacklist(patterns("authorization", "api-key")).build()).appendHeaders(context, entry);

        JsonNode headerNode = parseWrapped(buffer.toString());
        assertFalse(headerNode.has("Authorization"));
        assertFalse(headerNode.has("API-KEY"));
        assertEquals("conv-1", headerNode.get("X-Conversation-Id").asText());
        assertEquals("text/plain, application/json", headerNode.get("Accept").asText());
    }

    @SneakyThrows
    @Test
    public void testClaimsAndHeadersComposeIntoValidLogJson() {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put("Authorization", List.of("Bearer secret"));
        headers.put("X-Conversation-Id", List.of("conv-1"));

        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getRequestHeaders()).thenReturn(headers);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(List.of("admin"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        GfLogStore store = new GfLogStore(AnalyticsSettings.builder().collectClaims(true).collectHeaders(true)
                .headersBlacklist(patterns("authorization")).build());
        // mirror the order/position used by the real log line: both sections follow a preceding object member
        store.appendClaims(context, entry);
        store.appendHeaders(context, entry);

        JsonNode root = ProxyUtil.MAPPER.readTree("{\"user\":{}" + buffer + "}");
        assertEquals("user-1", root.get("claims").get("user_id").asText());
        assertEquals("admin", root.get("claims").get("roles").get(0).asText());
        assertFalse(root.get("headers").has("Authorization"));
        assertEquals("conv-1", root.get("headers").get("X-Conversation-Id").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendHeadersRegexBlacklistDropsFamily() {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put("X-Stainless-Lang", List.of("python"));
        headers.put("X-Stainless-OS", List.of("Linux"));
        headers.put("traceparent", List.of("00-abc-def-01"));
        headers.put("User-Agent", List.of("AsyncAzureOpenAI/Python"));

        AnalyticsLogContext context = mockHeaders(headers);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(AnalyticsSettings.builder().collectHeaders(true)
                .headersBlacklist(patterns("x-stainless-.*")).build()).appendHeaders(context, entry);

        JsonNode headerNode = parseWrapped(buffer.toString());
        assertFalse(headerNode.has("X-Stainless-Lang"));
        assertFalse(headerNode.has("X-Stainless-OS"));
        assertEquals("00-abc-def-01", headerNode.get("traceparent").asText());
        assertEquals("AsyncAzureOpenAI/Python", headerNode.get("User-Agent").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendHeadersAllowlistCollectsOnlyMatching() {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put("traceparent", List.of("00-abc-def-01"));
        headers.put("User-Agent", List.of("AsyncAzureOpenAI/Python"));
        headers.put("X-Stainless-Lang", List.of("python"));
        headers.put("Accept", List.of("application/json"));

        AnalyticsLogContext context = mockHeaders(headers);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(AnalyticsSettings.builder().collectHeaders(true)
                .headersAllowlist(patterns("traceparent", "user-agent")).build()).appendHeaders(context, entry);

        JsonNode headerNode = parseWrapped(buffer.toString());
        assertEquals("00-abc-def-01", headerNode.get("traceparent").asText());
        assertEquals("AsyncAzureOpenAI/Python", headerNode.get("User-Agent").asText());
        assertFalse(headerNode.has("X-Stainless-Lang"));
        assertFalse(headerNode.has("Accept"));
    }

    @SneakyThrows
    @Test
    public void testAppendHeadersBlacklistWinsOverAllowlist() {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put("Authorization", List.of("Bearer secret"));
        headers.put("traceparent", List.of("00-abc-def-01"));

        AnalyticsLogContext context = mockHeaders(headers);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        // "authorization" matches both lists; the blacklist must win
        new GfLogStore(AnalyticsSettings.builder().collectHeaders(true)
                .headersBlacklist(patterns("authorization")).headersAllowlist(patterns(".*")).build()).appendHeaders(context, entry);

        JsonNode headerNode = parseWrapped(buffer.toString());
        assertFalse(headerNode.has("Authorization"));
        assertEquals("00-abc-def-01", headerNode.get("traceparent").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendTokenUsage() {
        PromptTokensDetails promptDetails = new PromptTokensDetails();
        promptDetails.setCachedTokens(11);
        promptDetails.setCacheWriteTokens(22);

        CompletionTokensDetails completionDetails = new CompletionTokensDetails();
        completionDetails.setReasoningTokens(33);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(100);
        tokenUsage.setCompletionTokens(200);
        tokenUsage.setTotalTokens(300);
        tokenUsage.setPromptTokensDetails(promptDetails);
        tokenUsage.setCompletionTokensDetails(completionDetails);
        tokenUsage.setCost(new BigDecimal("1.5"));
        tokenUsage.setAggCost(new BigDecimal("2.5"));

        assertEquals("""
                {"completion_tokens":200,\
                "prompt_tokens":100,\
                "total_tokens":300,\
                "prompt_tokens_details":{"cached_tokens":11,"cache_write_tokens":22},\
                "completion_tokens_details":{"reasoning_tokens":33},\
                "deployment_price":1.5,"price":2.5}""",
                logTokenUsage(tokenUsage));
    }

    @SneakyThrows
    @Test
    public void testAppendTokenUsageWithoutDetails() {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(100);
        tokenUsage.setCompletionTokens(200);
        tokenUsage.setTotalTokens(300);

        assertEquals("""
                {"completion_tokens":200,"prompt_tokens":100,"total_tokens":300}""",
                logTokenUsage(tokenUsage));
    }

    @SneakyThrows
    @Test
    public void testAppendOperationDuration() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getOperationDurationMs()).thenReturn(1234L);

        StringBuilder buffer = new StringBuilder();
        new GfLogStore(AnalyticsSettings.builder().build()).append(context, capturingEntry(buffer));

        JsonNode duration = ProxyUtil.MAPPER.readTree(buffer.toString()).get("operation_duration_ms");
        assertTrue(duration.isNumber());
        assertEquals(1234L, duration.asLong());
    }

    @Test
    public void testOperationDurationFromProxyContext() {
        ProxyContext context = mockProxyContext();
        when(context.getRequestTimestamp()).thenReturn(1000L);
        when(context.getResponseBodyTimestamp()).thenReturn(2500L);

        assertEquals(1500, AnalyticsLogContext.from(context, null).getOperationDurationMs());
    }

    @Test
    public void testOperationDurationWhenResponseBodyTimestampNotSet() {
        ProxyContext context = mockProxyContext();
        when(context.getRequestTimestamp()).thenReturn(System.currentTimeMillis());
        when(context.getResponseBodyTimestamp()).thenReturn(0L);

        long duration = AnalyticsLogContext.from(context, null).getOperationDurationMs();
        assertTrue(duration >= 0 && duration < 60_000, "Unexpected duration: " + duration);
    }

    @Test
    public void testOperationDurationIsNeverNegative() {
        ProxyContext context = mockProxyContext();
        when(context.getRequestTimestamp()).thenReturn(2000L);
        // clock moved backwards between the two measurements
        when(context.getResponseBodyTimestamp()).thenReturn(1000L);

        assertEquals(0, AnalyticsLogContext.from(context, null).getOperationDurationMs());
    }

    @Test
    public void testOperationDurationFromBackgroundJobRecord() {
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .requestTimestamp(System.currentTimeMillis() - 60_000)
                .requestBody("{}")
                .build();

        assertTrue(AnalyticsLogContext.from(record, null).getOperationDurationMs() >= 60_000);
    }

    @Test
    public void testOperationDurationPrefersUpstreamCompletionOverPollTime() {
        // finalized 10 minutes after the request, but the upstream reports it finished after 5 seconds
        long requestTimestamp = System.currentTimeMillis() - 600_000;
        BackgroundJobRecord record = backgroundJobRecord(requestTimestamp);
        ResponsesApiClient.TerminalResult result =
                new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), null, requestTimestamp + 5_000);

        assertEquals(5_000, AnalyticsLogContext.from(record, result).getOperationDurationMs());
    }

    @Test
    public void testOperationDurationIgnoresUpstreamCompletionBeforeRequest() {
        long requestTimestamp = System.currentTimeMillis() - 600_000;
        BackgroundJobRecord record = backgroundJobRecord(requestTimestamp);
        // an upstream clock skewed into the past must not shorten the duration
        ResponsesApiClient.TerminalResult result =
                new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), null, requestTimestamp - 5_000);

        assertTrue(AnalyticsLogContext.from(record, result).getOperationDurationMs() >= 600_000);
    }

    @Test
    public void testOperationDurationWhenUpstreamReportsNoCompletion() {
        long requestTimestamp = System.currentTimeMillis() - 600_000;
        BackgroundJobRecord record = backgroundJobRecord(requestTimestamp);
        ResponsesApiClient.TerminalResult result = new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), null);

        assertTrue(AnalyticsLogContext.from(record, result).getOperationDurationMs() >= 600_000);
    }

    private static BackgroundJobRecord backgroundJobRecord(long requestTimestamp) {
        return BackgroundJobRecord.builder()
                .requestTimestamp(requestTimestamp)
                .requestBody("{}")
                .build();
    }

    private static ProxyContext mockProxyContext() {
        ProxyContext context = mock(ProxyContext.class, RETURNS_DEEP_STUBS);
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        when(context.getRequest()).thenReturn(request);
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.uri()).thenReturn("/test");
        when(request.headers()).thenReturn(new HeadersMultiMap());
        return context;
    }

    /**
     * Writes a whole log line for the given usage and returns its "token_usage" member, so the
     * surrounding line has to stay parseable too. Field order is preserved by the parser.
     */
    @SneakyThrows
    private static String logTokenUsage(TokenUsage tokenUsage) {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getTokenUsage()).thenReturn(tokenUsage);

        StringBuilder buffer = new StringBuilder();
        new GfLogStore(AnalyticsSettings.builder().build()).append(context, capturingEntry(buffer));

        return ProxyUtil.MAPPER.readTree(buffer.toString()).get("token_usage").toString();
    }

    private static AnalyticsLogContext mockHeaders(Map<String, List<String>> headers) {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getRequestHeaders()).thenReturn(headers);
        return context;
    }

    private static List<Pattern> patterns(String... regexes) {
        return Arrays.stream(regexes)
                .map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    private static LogEntry capturingEntry(StringBuilder buffer) {
        LogEntry entry = mock(LogEntry.class);
        when(entry.append(anyChar())).thenAnswer(cb -> {
            buffer.append((char) cb.getArgument(0));
            return entry;
        });
        when(entry.append(anyString(), anyInt(), anyInt())).thenAnswer(cb -> {
            buffer.append((String) cb.getArgument(0), cb.getArgument(1), cb.getArgument(2));
            return entry;
        });
        when(entry.append(anyString())).thenAnswer(cb -> {
            buffer.append((String) cb.getArgument(0));
            return entry;
        });
        return entry;
    }

    @SneakyThrows
    private static JsonNode parseWrapped(String member) {
        // member looks like: ,"claims":{...} ; wrap into a valid object and return the value node
        String json = "{" + member.substring(1) + "}";
        JsonNode root = ProxyUtil.MAPPER.readTree(json);
        return root.elements().next();
    }
}
