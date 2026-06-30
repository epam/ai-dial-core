package com.epam.aidial.core.server.log;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.deltix.gflog.api.LogEntry;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GfLogStoreTest {

    @Test
    public void testGetParentDeployment_NoInterceptors() {
        String result = LogContext.getParentDeployment("app", null, null);

        assertEquals("app", result);
    }

    @Test
    public void testGetParentDeployment_DeploymentWithInterceptors1() {
        // app calls model with interceptors
        List<String> interceptors = List.of("interceptor1", "interceptor2");
        List<String> executionPath = List.of("app", "interceptor1", "interceptor2", "model");

        String result = LogContext.getParentDeployment(null, interceptors, executionPath);

        assertEquals("app", result);
    }

    @Test
    public void testGetParentDeployment_DeploymentWithInterceptors2() {
        // chat calls model with interceptors
        List<String> interceptors = List.of("interceptor1", "interceptor2");
        List<String> executionPath = List.of("interceptor1", "interceptor2", "model");

        String result = LogContext.getParentDeployment(null, interceptors, executionPath);

        assertNull(result);
    }

    @Test
    public void testGetParentDeployment_InterceptorPathMismatch() {
        // app calls model with interceptors but interceptor1 calls some dep1 in the middle using the same per request key
        List<String> interceptors = List.of("interceptor1", "interceptor2");
        List<String> executionPath = List.of("app", "interceptor1", "dep1", "interceptor2", "model");

        String result = LogContext.getParentDeployment(null, interceptors, executionPath);

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
        ProxyContext context = mock(ProxyContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(List.of("admin", "reader"));
        when(context.getUserDisplayName()).thenReturn("Jane \"Doe\"");

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(true, false, List.of(), null).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertEquals("admin", claims.get("roles").get(0).asText());
        assertEquals("reader", claims.get("roles").get(1).asText());
        assertEquals("Jane \"Doe\"", claims.get("user_display_name").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendClaimsSkipsNullFields() {
        ProxyContext context = mock(ProxyContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(null);
        when(context.getUserDisplayName()).thenReturn(null);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(true, false, List.of(), null).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("user_id").asText());
        assertFalse(claims.has("roles"));
        assertFalse(claims.has("user_display_name"));
    }

    @SneakyThrows
    @Test
    public void testAppendHeadersAppliesBlacklistAndJoinsMultiValues() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Authorization", "Bearer secret");
        headers.add("API-KEY", "key-secret");
        headers.add("X-Conversation-Id", "conv-1");
        headers.add("Accept", "text/plain");
        headers.add("Accept", "application/json");

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(headers);
        ProxyContext context = mock(ProxyContext.class);
        when(context.getRequest()).thenReturn(request);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(false, true, patterns("authorization", "api-key"), null).appendHeaders(context, entry);

        JsonNode headerNode = parseWrapped(buffer.toString());
        assertFalse(headerNode.has("Authorization"));
        assertFalse(headerNode.has("API-KEY"));
        assertEquals("conv-1", headerNode.get("X-Conversation-Id").asText());
        assertEquals("text/plain, application/json", headerNode.get("Accept").asText());
    }

    @SneakyThrows
    @Test
    public void testClaimsAndHeadersComposeIntoValidLogJson() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Authorization", "Bearer secret");
        headers.add("X-Conversation-Id", "conv-1");

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(headers);
        ProxyContext context = mock(ProxyContext.class);
        when(context.getRequest()).thenReturn(request);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(List.of("admin"));

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        GfLogStore store = new GfLogStore(true, true, patterns("authorization"), null);
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
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("X-Stainless-Lang", "python");
        headers.add("X-Stainless-OS", "Linux");
        headers.add("traceparent", "00-abc-def-01");
        headers.add("User-Agent", "AsyncAzureOpenAI/Python");

        ProxyContext context = mockRequest(headers);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(false, true, patterns("x-stainless-.*"), null).appendHeaders(context, entry);

        JsonNode headerNode = parseWrapped(buffer.toString());
        assertFalse(headerNode.has("X-Stainless-Lang"));
        assertFalse(headerNode.has("X-Stainless-OS"));
        assertEquals("00-abc-def-01", headerNode.get("traceparent").asText());
        assertEquals("AsyncAzureOpenAI/Python", headerNode.get("User-Agent").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendHeadersAllowlistCollectsOnlyMatching() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("traceparent", "00-abc-def-01");
        headers.add("User-Agent", "AsyncAzureOpenAI/Python");
        headers.add("X-Stainless-Lang", "python");
        headers.add("Accept", "application/json");

        ProxyContext context = mockRequest(headers);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(false, true, List.of(), patterns("traceparent", "user-agent")).appendHeaders(context, entry);

        JsonNode headerNode = parseWrapped(buffer.toString());
        assertEquals("00-abc-def-01", headerNode.get("traceparent").asText());
        assertEquals("AsyncAzureOpenAI/Python", headerNode.get("User-Agent").asText());
        assertFalse(headerNode.has("X-Stainless-Lang"));
        assertFalse(headerNode.has("Accept"));
    }

    @SneakyThrows
    @Test
    public void testAppendHeadersBlacklistWinsOverAllowlist() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Authorization", "Bearer secret");
        headers.add("traceparent", "00-abc-def-01");

        ProxyContext context = mockRequest(headers);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        // "authorization" matches both lists; the blacklist must win
        new GfLogStore(false, true, patterns("authorization"), patterns(".*")).appendHeaders(context, entry);

        JsonNode headerNode = parseWrapped(buffer.toString());
        assertFalse(headerNode.has("Authorization"));
        assertEquals("00-abc-def-01", headerNode.get("traceparent").asText());
    }

    private static ProxyContext mockRequest(MultiMap headers) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(headers);
        ProxyContext context = mock(ProxyContext.class);
        when(context.getRequest()).thenReturn(request);
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
