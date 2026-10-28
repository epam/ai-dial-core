package com.epam.aidial.core.server.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.service.ResponsesApiClient;
import com.epam.aidial.core.server.token.CompletionTokensDetails;
import com.epam.aidial.core.server.token.PromptTokensDetails;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.deltix.gflog.api.LogEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GfLogStoreTest {

    private static final String PENDING_BODY_BYTES = "dial_analytics_log_pending_body_bytes";
    private static final String BODIES_SKIPPED = "dial_analytics_log_bodies_skipped_total";
    private static final long HEAP_BYTES = 10_000;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    public void addMeterRegistry() {
        Metrics.addRegistry(meters);
    }

    @AfterEach
    public void removeMeterRegistry() {
        Metrics.removeRegistry(meters);
    }

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

    @SneakyThrows
    @Test
    public void testAssembledResponseIsOmittedWithoutAssembly() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getResponseBody()).thenReturn(Buffer.buffer("{\"id\":\"chatcmpl-1\"}"));

        StringBuilder buffer = new StringBuilder();
        new GfLogStore(settings(false)).append(context, capturingEntry(buffer));

        JsonNode log = ProxyUtil.MAPPER.readTree(buffer.toString());
        assertFalse(log.has("assembled_response"));
        assertEquals("{\"id\":\"chatcmpl-1\"}", log.get("response").get("body").asText());
    }

    @SneakyThrows
    @Test
    public void testAssembledResponseIsWrittenForAssembledStream() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getResponseBody()).thenReturn(Buffer.buffer("data: {\"id\":\"chatcmpl-1\"}\n\ndata: [DONE]\n\n"));
        when(context.getAssembledStreamingResponse()).thenReturn("{\"id\":\"chatcmpl-1\"}");

        StringBuilder buffer = new StringBuilder();
        new GfLogStore(settings(false)).append(context, capturingEntry(buffer));

        JsonNode log = ProxyUtil.MAPPER.readTree(buffer.toString());
        assertEquals("{\"id\":\"chatcmpl-1\"}", log.get("assembled_response").asText());
    }

    @SneakyThrows
    @Test
    public void testRouteIsWrittenFlatBetweenExecutionPathAndDuration() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getExecutionPath()).thenReturn(List.of("route"));
        when(context.getRouteName()).thenReturn("vector-store");
        when(context.getRouteType()).thenReturn("global");

        JsonNode log = logOf(context);

        assertEquals("vector-store", log.get("route_name").asText());
        assertEquals("global", log.get("route_type").asText());
        assertFalse(log.has("route"));
        List<String> fields = new ArrayList<>();
        log.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("execution_path", "route_name", "route_type", "operation_duration_ms"),
                fields.subList(fields.indexOf("execution_path"), fields.indexOf("operation_duration_ms") + 1));
    }

    @SneakyThrows
    @Test
    public void testApplicationRouteWritesNameAndTypeNextToTheDeployment() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getDeploymentName()).thenReturn("applications/public/dial_scheduler__1.0.0");
        when(context.getRouteName()).thenReturn("index-search");
        when(context.getRouteType()).thenReturn("application");

        JsonNode log = logOf(context);

        assertEquals("index-search", log.get("route_name").asText());
        assertEquals("application", log.get("route_type").asText());
        assertEquals("applications/public/dial_scheduler__1.0.0", log.get("deployment").asText());
    }

    @SneakyThrows
    @Test
    public void testRouteIsOmittedWhenNoRouteIsSelected() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getDeploymentName()).thenReturn("model");

        JsonNode log = logOf(context);

        assertFalse(log.has("route_name"));
        assertFalse(log.has("route_type"));
        assertEquals("model", log.get("deployment").asText());
    }

    @SneakyThrows
    @Test
    public void testRouteTypeIsOmittedWhenItIsNull() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getRouteName()).thenReturn("vector-store");

        JsonNode log = logOf(context);

        assertEquals("vector-store", log.get("route_name").asText());
        assertFalse(log.has("route_type"));
    }

    @SneakyThrows
    @Test
    public void testRouteNameIsOmittedWhenItIsNull() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getRouteType()).thenReturn("global");

        JsonNode log = logOf(context);

        assertFalse(log.has("route_name"));
        assertEquals("global", log.get("route_type").asText());
    }

    @SneakyThrows
    @Test
    public void testRouteNamesAreEscaped() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getRouteName()).thenReturn("na\"me\\\n");
        when(context.getRouteType()).thenReturn("global");

        JsonNode log = logOf(context);

        assertEquals("na\"me\\\n", log.get("route_name").asText());
    }

    @Test
    public void testSessionIsWrittenRightAfterChat() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.getConversationId()).thenReturn("conversation-1");
        when(context.getSessionId()).thenReturn("se\"ss\\ion\n");

        JsonNode log = logOf(context);

        assertEquals("conversation-1", log.get("chat").get("id").asText());
        assertEquals("se\"ss\\ion\n", log.get("session").get("id").asText());
        List<String> fields = new ArrayList<>();
        log.fieldNames().forEachRemaining(fields::add);
        assertEquals(fields.indexOf("chat") + 1, fields.indexOf("session"));
    }

    @Test
    public void testSessionIsOmittedWithoutSessionId() {
        JsonNode log = logOf(mock(AnalyticsLogContext.class));

        assertFalse(log.has("session"));
    }

    @Test
    public void testSessionIsWrittenForSecuredApiKey() {
        AnalyticsLogContext context = mock(AnalyticsLogContext.class);
        when(context.isSecuredApiKey()).thenReturn(true);
        when(context.getSessionId()).thenReturn("session-1");

        JsonNode log = logOf(context);

        assertEquals("session-1", log.get("session").get("id").asText());
        assertFalse(log.get("request").has("body"));
    }

    @Test
    public void testBodiesWrittenForKeyNotSecured() {
        JsonNode log = logOf(row("model", "{\"q\":1}")
                .responseBody(Buffer.buffer("{\"a\":2}"))
                .assembledStreamingResponse("{\"assembled\":3}")
                .build());

        assertEquals("{\"q\":1}", log.get("request").get("body").asText());
        assertEquals("{\"a\":2}", log.get("response").get("body").asText());
        assertEquals("{\"assembled\":3}", log.get("assembled_response").asText());
    }

    @Test
    public void testSecuredKeyRowHasNoBodies() {
        JsonNode log = logOf(row("model", "{\"q\":1}")
                .responseBody(Buffer.buffer("{\"a\":2}"))
                .assembledStreamingResponse("{\"assembled\":3}")
                .securedApiKey(true)
                .build());

        assertFalse(log.get("request").has("body"));
        assertFalse(log.get("response").has("body"));
        assertFalse(log.has("assembled_response"));
        assertEquals("model", log.get("deployment").asText());
        assertEquals("200", log.get("response").get("status").asText());
    }

    @Test
    public void testRowsWithoutBodiesWhileWriterBehind() {
        GfLogStore store = store(percentOfHeap(10));
        AnalyticsLogContext a = row("a", "x".repeat(2000)).build();
        AnalyticsLogContext b = row("b", "y".repeat(2000))
                .responseBody(Buffer.buffer("{}"))
                .assembledStreamingResponse("{}")
                .build();
        AnalyticsLogContext c = row("c", "z".repeat(10)).build();

        // nothing waiting: the row keeps its body although it is over the limit
        assertSame(a, store.admit(a));
        assertEquals("x".repeat(2000), logOf(a).get("request").get("body").asText());
        assertEquals(2000, pendingBodyBytes());

        // the bodies waiting reach the limit: every other field is still written
        AnalyticsLogContext queuedB = store.admit(b);
        JsonNode log = logOf(queuedB);
        assertFalse(log.get("request").has("body"));
        assertFalse(log.get("response").has("body"));
        assertFalse(log.has("assembled_response"));
        assertEquals("b", log.get("deployment").asText());
        assertEquals(7, log.get("token_usage").get("total_tokens").asLong());
        assertEquals("trace-b", log.get("trace").get("trace_id").asText());
        assertEquals("200", log.get("response").get("status").asText());
        assertEquals(1, bodiesSkipped());
        assertEquals(2000, pendingBodyBytes());

        // the writer caught up
        store.release(a);
        store.release(queuedB);
        assertEquals(0, pendingBodyBytes());
        assertSame(c, store.admit(c));
        assertEquals("z".repeat(10), logOf(c).get("request").get("body").asText());
        assertEquals(1, bodiesSkipped());
    }

    @Test
    public void testOneWarningWhileWriterHoversAtLimit() {
        GfLogStore store = store(percentOfHeap(10));
        AnalyticsLogContext a = row("a", "x".repeat(700)).build();
        AnalyticsLogContext b = row("b", "x".repeat(700)).build();
        AnalyticsLogContext d = row("d", "x".repeat(700)).build();
        Logger logger = (Logger) LoggerFactory.getLogger(GfLogStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            store.admit(a);
            store.admit(b);
            // 1,400 bytes wait, over the limit of 1,000
            store.admit(row("c", "x").build());
            // the writer wrote A: 700 bytes wait, under the limit but over half of it
            store.release(a);
            assertSame(d, store.admit(d));
            assertNull(store.admit(row("e", "x").build()).getRequestBody());
            // the writer wrote the rest
            store.release(b);
            store.release(d);
            store.admit(row("f", "x").build());

            assertEquals(2, bodiesSkipped());
            assertEquals(List.of(Level.WARN, Level.INFO), appender.list.stream().map(ILoggingEvent::getLevel).toList(), appender.list::toString);
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
    }

    @Test
    public void testLimitFollowsTheHeap() {
        long largerHeap = 4 * HEAP_BYTES;
        AnalyticsLogContext a = row("a", "x".repeat(2000)).build();
        AnalyticsLogContext b = row("b", "y".repeat(2000)).build();

        // 10 percent of the larger heap is 4,000 bytes, and 2,000 are waiting
        GfLogStore store = store(percentOfHeap(10), largerHeap);
        store.admit(a);
        assertSame(b, store.admit(b));
        assertEquals(0, bodiesSkipped());

        // 2.5 percent of the larger heap is 1,000 bytes
        store = store(percentOfHeap(2.5), largerHeap);
        store.admit(a);
        assertNull(store.admit(b).getRequestBody());
        assertEquals(1, bodiesSkipped());
    }

    @Test
    public void testRowsKeepBodiesWithoutLimit() {
        // no setting reads as -1; 0 means no limit as well
        for (AnalyticsSettings settings : List.of(AnalyticsSettings.from(new JsonObject()), percentOfHeap(0))) {
            GfLogStore store = store(settings);
            AnalyticsLogContext a = row("a", "x".repeat(2000)).build();
            AnalyticsLogContext b = row("b", "y".repeat(2000)).build();

            assertSame(a, store.admit(a));
            assertSame(b, store.admit(b));

            assertEquals("y".repeat(2000), logOf(b).get("request").get("body").asText());
            assertEquals(4000, pendingBodyBytes());
            assertEquals(0, bodiesSkipped());
        }
    }

    @Test
    public void testSecuredRowQueuedWithoutBodies() {
        GfLogStore store = store(AnalyticsSettings.from(new JsonObject()));
        AnalyticsLogContext secured = row("a", "x".repeat(2000))
                .responseBody(Buffer.buffer("{}"))
                .assembledStreamingResponse("{}")
                .securedApiKey(true)
                .build();

        AnalyticsLogContext queued = store.admit(secured);

        assertNull(queued.getRequestBody());
        assertNull(queued.getResponseBody());
        assertNull(queued.getAssembledStreamingResponse());
        assertEquals("a", logOf(queued).get("deployment").asText());
        assertEquals(0, pendingBodyBytes());
        assertEquals(0, bodiesSkipped());
    }

    @Test
    public void testSessionFromBackgroundJobRecord() {
        BackgroundJobRecord record = BackgroundJobRecord.builder()
                .sessionId("session-1")
                .requestBody("{}")
                .build();

        assertEquals("session-1", AnalyticsLogContext.from(record, null, null).getSessionId());
    }

    @Test
    public void testBackgroundJobRecordStoredWithoutSessionLoadsWithNoSession() {
        BackgroundJobRecord record = ProxyUtil.convertToObject("{\"requestBody\":\"{}\"}", BackgroundJobRecord.class);

        assertNull(record.sessionId());
    }

    @SneakyThrows
    private static JsonNode logOf(AnalyticsLogContext context) {
        StringBuilder buffer = new StringBuilder();
        new GfLogStore(settings(false)).append(context, capturingEntry(buffer));
        return ProxyUtil.MAPPER.readTree(buffer.toString());
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

    private static AnalyticsLogContext.AnalyticsLogContextBuilder row(String deployment, String requestBody) {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(7);
        return AnalyticsLogContext.builder()
                .deploymentName(deployment)
                .traceId("trace-" + deployment)
                .tokenUsage(tokenUsage)
                .requestBody(Buffer.buffer(requestBody))
                .responseStatusCode(200);
    }

    private static AnalyticsSettings percentOfHeap(double maxPendingBodyHeapPercent) {
        return AnalyticsSettings.from(new JsonObject().put("maxPendingBodyHeapPercent", maxPendingBodyHeapPercent));
    }

    /**
     * On a heap of {@value #HEAP_BYTES} bytes, so that 10 percent is a limit of 1,000 bytes.
     */
    private static GfLogStore store(AnalyticsSettings settings) {
        return store(settings, HEAP_BYTES);
    }

    private static GfLogStore store(AnalyticsSettings settings, long maxHeapBytes) {
        // the global registry keeps the first gauge registered under a name, which stays bound to an earlier store
        Metrics.globalRegistry.find(PENDING_BODY_BYTES).meters().forEach(Metrics.globalRegistry::remove);
        return new GfLogStore(settings, maxHeapBytes);
    }

    private long pendingBodyBytes() {
        return (long) meters.get(PENDING_BODY_BYTES).gauge().value();
    }

    private long bodiesSkipped() {
        return (long) meters.get(BODIES_SKIPPED).counter().count();
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
