package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.service.ResponsesApiClient;
import com.epam.aidial.core.server.token.CompletionTokensDetails;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.deltix.gflog.api.LogEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

        new GfLogStore(settings(true)).appendClaims(context, entry);

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

        new GfLogStore(settings(true)).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertFalse(claims.has("roles"));
        assertFalse(claims.has("user_display_name"));
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaims() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(List.of("admin"));
        when(context.getUserDisplayName()).thenReturn("Jane Doe");
        when(context.getUserClaims()).thenReturn(userClaims(
                "{\"email\":\"jane.doe@example.com\",\"resource_access\":{\"roles\":[\"a\",\"b\"]},\"sub\":\"s-1\"}"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(settings(true, "email", "resource_access.roles")).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertEquals("admin", claims.get("roles").get(0).asText());
        assertEquals("Jane Doe", claims.get("user_display_name").asText());
        assertEquals("jane.doe@example.com", claims.get("email").asText());
        assertEquals("[\"a\",\"b\"]", claims.get("resource_access.roles").toString());
        assertFalse(claims.has("sub"));
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaimsWildcardCollectsEverything() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserClaims()).thenReturn(userClaims(
                "{\"email\":\"jane.doe@example.com\",\"exp\":1712345678,\"groups\":[\"g1\"]}"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(settings(false, "*")).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("jane.doe@example.com", claims.get("email").asText());
        assertEquals(1712345678L, claims.get("exp").asLong());
        assertEquals("g1", claims.get("groups").get(0).asText());
        assertFalse(claims.has("user_id"));
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaimsOmitsMissingOnes() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserClaims()).thenReturn(userClaims("{\"email\":null,\"sub\":\"s-1\"}"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        // upn is absent, email is explicitly null, resource_access has no such parent object at all
        new GfLogStore(settings(true, "email", "upn", "resource_access.roles")).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertFalse(claims.has("email"));
        assertFalse(claims.has("upn"));
        assertFalse(claims.has("resource_access.roles"));
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaimsIsIndependentOfCollectClaims() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(List.of("admin"));
        when(context.getUserDisplayName()).thenReturn("Jane Doe");
        when(context.getUserClaims()).thenReturn(userClaims("{\"email\":\"jane.doe@example.com\"}"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(settings(false, "email")).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("jane.doe@example.com", claims.get("email").asText());
        assertFalse(claims.has("user_id"));
        assertFalse(claims.has("roles"));
        assertFalse(claims.has("user_display_name"));
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaimsSkipsFixedMemberCollision() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserClaims()).thenReturn(userClaims("{\"user_id\":\"claim-value\"}"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(settings(true, "user_id")).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaimsFallsBackToClaimWhenFixedMemberIsAbsent() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn(null);
        when(context.getUserClaims()).thenReturn(userClaims("{\"user_id\":\"claim-value\"}"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        // nothing was written for the fixed user_id member, so the allowed claim must not be suppressed
        new GfLogStore(settings(true, "user_id")).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("claim-value", claims.get("user_id").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaimsTruncatesOversizedValue() {
        String longValue = "x".repeat(8 * 1024);
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserClaims()).thenReturn(userClaims(
                ProxyUtil.MAPPER.createObjectNode().put("groups", longValue).toString()));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(settings(false, "groups")).appendClaims(context, entry);

        // the line stays parseable and the value is reported as a truncated string
        JsonNode claims = parseWrapped(buffer.toString());
        String written = claims.get("groups").asText();
        assertEquals(4 * 1024 + ">>".length(), written.length());
        assertTrue(written.endsWith(">>"), "Missing truncation marker: " + written);
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaimsTruncatesWithoutSplittingSurrogatePair() {
        // the serialized value opens with a quote, so the emoji straddles the 4 KB cut
        String value = "x".repeat(4 * 1024 - 2) + "😀" + "x".repeat(64);
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserClaims()).thenReturn(userClaims(
                ProxyUtil.MAPPER.createObjectNode().put("groups", value).toString()));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(settings(false, "groups")).appendClaims(context, entry);

        assertCutBeforeSurrogatePair(parseWrapped(buffer.toString()).get("groups").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendHeadersTruncatesWithoutSplittingSurrogatePair() {
        String value = "x".repeat(4 * 1024 - 1) + "😀" + "x".repeat(64);
        AnalyticsLogContext context = mockHeaders(Map.of("X-Custom", List.of(value)));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(headerSettings(List.of(), null)).appendHeaders(context, entry);

        assertCutBeforeSurrogatePair(parseWrapped(buffer.toString()).get("X-Custom").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaimsIgnoresMalformedPath() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserClaims()).thenReturn(userClaims("{\"email\":\"jane.doe@example.com\"}"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        // "." resolves to the root of the payload, which would dump every claim without the explicit "*" opt-in
        new GfLogStore(settings(false, ".")).appendClaims(context, entry);

        assertEquals("{}", parseWrapped(buffer.toString()).toString());
    }

    @SneakyThrows
    @Test
    public void testClaimsAreOmittedWhenNothingIsCollected() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserClaims()).thenReturn(userClaims("{\"email\":\"jane.doe@example.com\"}"));

        StringBuilder buffer = new StringBuilder();
        new GfLogStore(settings(false)).append(context, capturingEntry(buffer));

        assertFalse(ProxyUtil.MAPPER.readTree(buffer.toString()).has("claims"));
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaimsEscapesNamesAndValues() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserClaims()).thenReturn(userClaims("{\"na\\\"me\":\"va\\\"lue\"}"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(settings(false, "*")).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("va\"lue", claims.get("na\"me").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendAllowedClaimsWithoutClaimsNode() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserClaims()).thenReturn(null);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(settings(true, "email")).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertFalse(claims.has("email"));
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

        new GfLogStore(headerSettings(List.of("authorization", "api-key"), null)).appendHeaders(context, entry);

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
        when(context.getUserClaims()).thenReturn(userClaims("{\"email\":\"jane.doe@example.com\"}"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        GfLogStore store = new GfLogStore(AnalyticsSettings.from(new JsonObject()
                .put("collectClaims", true)
                .put("claimsAllowlist", new JsonArray(List.of("email")))
                .put("collectHeaders", true)
                .put("headersBlacklist", new JsonArray(List.of("authorization")))));
        // mirror the order/position used by the real log line: both sections follow a preceding object member
        store.appendClaims(context, entry);
        store.appendHeaders(context, entry);

        JsonNode root = ProxyUtil.MAPPER.readTree("{\"user\":{}" + buffer + "}");
        assertEquals("user-1", root.get("claims").get("user_id").asText());
        assertEquals("admin", root.get("claims").get("roles").get(0).asText());
        assertEquals("jane.doe@example.com", root.get("claims").get("email").asText());
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

        new GfLogStore(headerSettings(List.of("x-stainless-.*"), null)).appendHeaders(context, entry);

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

        new GfLogStore(headerSettings(List.of(), List.of("traceparent", "user-agent"))).appendHeaders(context, entry);

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
        new GfLogStore(headerSettings(List.of("authorization"), List.of(".*"))).appendHeaders(context, entry);

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
        new GfLogStore(settings(false)).append(context, capturingEntry(buffer));

        JsonNode duration = ProxyUtil.MAPPER.readTree(buffer.toString()).get("operation_duration_ms");
        assertTrue(duration.isNumber());
        assertEquals(1234L, duration.asLong());
    }

    @Test
    public void testOperationDurationFromBackgroundJobRecord() {
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .requestTimestamp(System.currentTimeMillis() - 60_000)
                .requestBody("{}")
                .build();

        assertTrue(AnalyticsLogContext.from(record, null, null).getOperationDurationMs() >= 60_000);
    }

    @Test
    public void testBackgroundJobEntriesAreInResponsesFormat() {
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .requestTimestamp(System.currentTimeMillis())
                .requestBody("{}")
                .build();

        assertEquals(InterfaceType.OPENAI_RESPONSES, AnalyticsLogContext.from(record, null, null).getInterfaceType());
    }

    @Test
    public void testOperationDurationOfCompletedBackgroundJob() {
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .requestTimestamp(System.currentTimeMillis() - 600_000)
                .requestBody("{}")
                .build();
        ResponsesApiClient.TerminalResult result = new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), null);

        assertTrue(AnalyticsLogContext.from(record, result, null).getOperationDurationMs() >= 600_000);
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
        new GfLogStore(settings(false)).append(context, capturingEntry(buffer));

        return ProxyUtil.MAPPER.readTree(buffer.toString()).get("token_usage").toString();
    }

    private static AnalyticsLogContext mockHeaders(Map<String, List<String>> headers) {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getRequestHeaders()).thenReturn(headers);
        return context;
    }

    /**
     * Goes through the real settings parser, so the claim paths are split exactly as they are in production.
     */
    private static AnalyticsSettings settings(boolean collectClaims, String... claimPaths) {
        return AnalyticsSettings.from(new JsonObject()
                .put("collectClaims", collectClaims)
                .put("claimsAllowlist", new JsonArray(List.of(claimPaths))));
    }

    /**
     * @param allowlist null leaves the setting out entirely, which disables the header allowlist.
     */
    private static AnalyticsSettings headerSettings(List<String> blacklist, List<String> allowlist) {
        JsonObject settings = new JsonObject()
                .put("collectHeaders", true)
                .put("headersBlacklist", new JsonArray(blacklist));
        if (allowlist != null) {
            settings.put("headersAllowlist", new JsonArray(allowlist));
        }
        return AnalyticsSettings.from(settings);
    }

    @SneakyThrows
    private static ObjectNode userClaims(String json) {
        return (ObjectNode) ProxyUtil.MAPPER.readTree(json);
    }

    /**
     * A lone surrogate has no UTF-8 encoding, so the cut must fall before the pair rather than between its halves.
     */
    private static void assertCutBeforeSurrogatePair(String written) {
        String cut = written.substring(0, written.length() - ">>".length());
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)),
                "Truncation left a lone high surrogate at the cut");
    }

    private static final String FILE_REQUEST = """
            {"messages":[{"role":"user","content":[{"type":"text","text":"hi"},\
            {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}]}""";
    private static final String FILE_RESPONSE = """
            {"choices":[{"index":0,"message":{"role":"assistant","audio":{"data":"BBBB"}}}]}""";
    private static final String STREAMED_RESPONSE = """
            data: {"choices":[{"index":0,"delta":{"audio":{"data":"BB"}}}]}

            data: {"choices":[{"index":0,"delta":{"audio":{"data":"BB"}}}]}

            data: [DONE]

            """;

    @Test
    public void testDefaultsWriteTheSameLineWithoutScanning() {
        String withInterface = logLine(new JsonObject(), chatContext().build());
        String withoutInterface = logLine(new JsonObject(), chatContext().interfaceType(null).build());

        assertEquals(withoutInterface, withInterface);
        assertFalse(parse(withInterface).has("inline_files"));
        assertEquals(FILE_RESPONSE, parse(withInterface).get("assembled_response").asText());
    }

    @Test
    public void testRemovedFilesAreListed() {
        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFiles", false), chatContext().build()));

        assertEquals(FILE_REQUEST.replace("base64,AAAA", "base64,"), line.get("request").get("body").asText());
        assertEquals(FILE_RESPONSE.replace("\"BBBB\"", "\"\""), line.get("response").get("body").asText());
        assertEquals(FILE_RESPONSE.replace("\"BBBB\"", "\"\""), line.get("assembled_response").asText());
        ArrayNode files = (ArrayNode) line.get("inline_files");
        assertEquals(2, files.size());
        assertEquals("""
                {"field":"request.body","path":"/messages/0/content/1/image_url/url","encoding":"data_url",\
                "mime":"image/png","status":"excluded","size":4}""", files.get(0).toString());
        assertEquals("""
                {"field":"response.body","path":"/choices/0/message/audio/data","encoding":"base64",\
                "status":"excluded","size":4}""", files.get(1).toString());
    }

    @Test
    public void testIndexOffsetsExtractPayloadsFromTheParsedLine() {
        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFileIndex", true), chatContext().build()));

        ArrayNode files = (ArrayNode) line.get("inline_files");
        assertEquals("AAAA", extract(line.get("request").get("body").asText(), files.get(0)));
        assertEquals("BBBB", extract(line.get("response").get("body").asText(), files.get(1)));
        assertEquals("included", files.get(1).get("status").asText());
    }

    @Test
    public void testAssembledStreamsListTheirFilesAgainstAssembledResponse() {
        AnalyticsLogContext context = chatContext()
                .responseBody(Buffer.buffer(STREAMED_RESPONSE))
                .assembledStreamingResponse(FILE_RESPONSE)
                .build();

        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFileIndex", true), context));

        ArrayNode files = (ArrayNode) line.get("inline_files");
        assertEquals(List.of("request.body", "assembled_response"), fields(files));
        assertEquals("BBBB", extract(line.get("assembled_response").asText(), files.get(1)));
    }

    @Test
    public void testFailedAssemblyListsResponseBodyFilesWithChunks() {
        AnalyticsLogContext context = chatContext()
                .responseBody(Buffer.buffer(STREAMED_RESPONSE))
                .assembledStreamingResponse("{}")
                .build();

        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFiles", false), context));

        ArrayNode files = (ArrayNode) line.get("inline_files");
        assertEquals(List.of("request.body", "response.body", "response.body"), fields(files));
        assertEquals(1, files.get(2).get("chunk").asInt());
        assertEquals(STREAMED_RESPONSE.replace("\"BB\"", "\"\""), line.get("response").get("body").asText());
        assertEquals("{}", line.get("assembled_response").asText());
    }

    @Test
    public void testResponsesStreamWithoutTerminalEventListsResponseBody() {
        String stream = """
                event: response.image_generation_call.partial_image
                data: {"type":"response.image_generation_call.partial_image","partial_image_b64":"QUJD"}

                """;
        AnalyticsLogContext context = AnalyticsLogContext.builder()
                .interfaceType(InterfaceType.OPENAI_RESPONSES)
                .requestBody(Buffer.buffer("{\"input\":\"hi\"}"))
                .responseBody(Buffer.buffer(stream))
                .build();

        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFileIndex", true), context));

        JsonNode file = line.get("inline_files").get(0);
        assertEquals("response.body", file.get("field").asText());
        assertEquals(0, file.get("chunk").asInt());
        assertEquals("QUJD", extract(line.get("response").get("body").asText(), file));
    }

    @Test
    public void testBackgroundJobResponsesAreIndexed() {
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .requestTimestamp(System.currentTimeMillis())
                .requestBody("{\"input\":\"draw\"}")
                .build();
        ResponsesApiClient.TerminalResult result = new ResponsesApiClient.TerminalResult(
                Buffer.buffer("{\"output\":[{\"type\":\"image_generation_call\",\"result\":\"iVBORw0K\"}]}"), null);

        JsonNode line = parse(logLine(new JsonObject().put("collectInlineFileIndex", true), AnalyticsLogContext.from(record, result, null)));

        JsonNode file = line.get("inline_files").get(0);
        assertEquals("response.body", file.get("field").asText());
        assertEquals("/output/0/result", file.get("path").asText());
    }

    @Test
    public void testDuplicateAssembledResponseIsOmitted() {
        JsonObject settings = new JsonObject().put("collectDuplicateAssembledResponse", false);

        assertFalse(parse(logLine(settings, chatContext().build())).has("assembled_response"));
        assertFalse(parse(logLine(settings, chatContext().responseBody(null).build())).has("assembled_response"));
        assertEquals(FILE_RESPONSE, parse(logLine(settings, chatContext().assembledStreamingResponse(FILE_RESPONSE).build()))
                .get("assembled_response").asText());
        assertEquals("{}", parse(logLine(settings, chatContext().assembledStreamingResponse("{}").build()))
                .get("assembled_response").asText());
    }

    @Test
    public void testSecuredKeyEntriesAreNotScanned() {
        JsonObject all = new JsonObject().put("collectInlineFiles", false).put("collectInlineFileIndex", true);

        JsonNode line = parse(logLine(all, chatContext().securedApiKey(true).build()));

        assertFalse(line.has("inline_files"));
        assertFalse(line.get("request").has("body"));
    }

    @Test
    public void testNullBodiesWithEverySettingOn() {
        JsonObject all = new JsonObject().put("collectInlineFiles", false).put("collectTruncatedInlineFiles", false)
                .put("collectInlineFileIndex", true).put("collectDuplicateAssembledResponse", false);

        JsonNode line = parse(logLine(all, chatContext().requestBody(null).responseBody(null).build()));

        assertFalse(line.has("inline_files"));
        assertEquals("", line.get("request").get("body").asText());
    }

    @Test
    public void testCutBeforeFileCrossingTheLimit() {
        String request = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,"
                + "A".repeat(LoggedBody.MAX_BYTES) + "\"}}]}]}";

        JsonNode line = parse(logLine(new JsonObject().put("collectTruncatedInlineFiles", false),
                chatContext().requestBody(Buffer.buffer(request)).build()));

        assertEquals(request.substring(0, request.indexOf("\"data:")) + ">>", line.get("request").get("body").asText());
        assertEquals("""
                {"field":"request.body","path":"/messages/0/content/0/image_url/url","encoding":"data_url",\
                "mime":"image/png","status":"excluded"}""", line.get("inline_files").get(0).toString());
    }

    private static AnalyticsLogContext.AnalyticsLogContextBuilder chatContext() {
        return AnalyticsLogContext.builder()
                .interfaceType(InterfaceType.OPENAI_CHAT_COMPLETIONS)
                .requestBody(Buffer.buffer(FILE_REQUEST))
                .responseBody(Buffer.buffer(FILE_RESPONSE));
    }

    @SneakyThrows
    private static String logLine(JsonObject analytics, AnalyticsLogContext context) {
        StringBuilder buffer = new StringBuilder();
        new GfLogStore(AnalyticsSettings.from(analytics)).append(context, capturingEntry(buffer));
        return buffer.toString();
    }

    @SneakyThrows
    private static JsonNode parse(String line) {
        return ProxyUtil.MAPPER.readTree(line);
    }

    private static String extract(String body, JsonNode file) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new String(bytes, file.get("offset").asInt(), file.get("length").asInt(), StandardCharsets.UTF_8);
    }

    private static List<String> fields(ArrayNode files) {
        List<String> fields = new java.util.ArrayList<>();
        files.forEach(file -> fields.add(file.get("field").asText()));
        return fields;
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
