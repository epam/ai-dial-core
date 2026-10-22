package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.deltix.gflog.core.LogConfigurator;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that MCP calls end up in the analytics log with the execution path.
 * The {@code aidial.log} gflog logger is pointed at a temporary file for the duration of the class
 * (see {@code gflog-mcp-analytics-test.xml}) and the test reads back what was written there.
 */
public class McpAnalyticsLogApiTest extends ResourceBaseTest {

    private static final String ANALYTICS_GFLOG_CONFIG = "classpath:gflog-mcp-analytics-test.xml";
    private static final String DEFAULT_GFLOG_CONFIG = "classpath:gflog-test.xml";

    private static final String MCP_RESPONSE = """
            {"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": "ok"}]}}
            """;

    private static Path analyticsLogDir;
    private static Path analyticsLog;

    @BeforeAll
    static void routeAnalyticsLogToFile() throws Exception {
        analyticsLogDir = Files.createTempDirectory("mcp-analytics-log");
        analyticsLog = analyticsLogDir.resolve("aidial.log");
        Properties properties = new Properties();
        properties.setProperty("analyticsLogFile", analyticsLog.toString());
        LogConfigurator.configure(ANALYTICS_GFLOG_CONFIG, properties);
    }

    @AfterAll
    static void restoreDefaultAnalyticsLog() throws Exception {
        // the file is closed by reconfiguring, only then it can be deleted
        LogConfigurator.configure(DEFAULT_GFLOG_CONFIG);
        FileUtil.deleteDir(analyticsLogDir);
    }

    @ParameterizedTest
    @CsvSource({
            "/v1/toolset/tool-no-forward/mcp, tool-no-forward, false",
            "/v1/toolset/tool-forward/mcp, tool-forward, true",
            "/v1/deployments/app-no-forward/mcp, app-no-forward, false",
            "/v1/deployments/app-forward/mcp, app-forward, true"
    })
    @DialConfigLocation("dial-config/mcp-analytics-log.json")
    void testMcpCallWritesExecutionPathToAnalyticsLog(String path, String deployment, boolean perRequestKeyForwarded) throws Exception {
        String marker = UUID.randomUUID().toString();
        AtomicReference<String> upstreamApiKey = new AtomicReference<>();

        try (TestWebServer ignore = new TestWebServer(9876, upstream(upstreamApiKey))) {
            Response response = send(HttpMethod.POST, path, null, toolCall(marker), "Content-Type", "application/json");

            assertEquals(200, response.status());
        }

        JsonNode entry = awaitAnalyticsEntry(marker);
        assertEquals(List.of(deployment), toList(entry.get("execution_path")));
        assertEquals(deployment, entry.get("deployment").asText());
        assertNull(entry.get("parent_deployment"));
        assertEquals(perRequestKeyForwarded, upstreamApiKey.get() != null);
        if (perRequestKeyForwarded) {
            awaitPerRequestKeyInvalidated(upstreamApiKey.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/mcp-analytics-log.json")
    void testMcpCallWithPerRequestKeyAppendsToCallerExecutionPath() throws Exception {
        String marker = UUID.randomUUID().toString();
        ApiKeyData callerKey = createAppKey("user", Map.of());
        callerKey.setSourceDeployment("orchestrator");
        callerKey.setExecutionPath(List.of("orchestrator"));
        apiKeyStore.assignPerRequestApiKey(callerKey);

        try (TestWebServer ignore = new TestWebServer(9876, upstream(new AtomicReference<>()))) {
            Response response = send(HttpMethod.POST, "/v1/toolset/tool-no-forward/mcp", null, toolCall(marker),
                    "Content-Type", "application/json", "api-key", callerKey.getPerRequestKey());

            assertEquals(200, response.status());
        }

        JsonNode entry = awaitAnalyticsEntry(marker);
        assertEquals(List.of("orchestrator", "tool-no-forward"), toList(entry.get("execution_path")));
        assertEquals("orchestrator", entry.get("parent_deployment").asText());
    }

    @Test
    @DialConfigLocation("dial-config/mcp-analytics-log.json")
    void testMcpCallFollowingRedirectReusesSinglePerRequestKey() throws Exception {
        String marker = UUID.randomUUID().toString();
        List<String> upstreamApiKeys = new CopyOnWriteArrayList<>();
        TestWebServer.Handler handler = request -> {
            upstreamApiKeys.add(request.getHeader(Proxy.HEADER_API_KEY));
            request.getBody().readString(StandardCharsets.UTF_8);
            if (upstreamApiKeys.size() == 1) {
                return new MockResponse().setResponseCode(307).setHeader("Location", "?redirected=1");
            }
            return new MockResponse().setBody(MCP_RESPONSE).setHeader("Content-Type", "application/json");
        };

        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response response = send(HttpMethod.POST, "/v1/toolset/tool-forward/mcp", null, toolCall(marker),
                    "Content-Type", "application/json");

            assertEquals(200, response.status());
        }

        assertEquals(2, upstreamApiKeys.size());
        assertNotNull(upstreamApiKeys.getFirst());
        assertEquals(upstreamApiKeys.getFirst(), upstreamApiKeys.getLast());
        awaitPerRequestKeyInvalidated(upstreamApiKeys.getFirst());
        JsonNode entry = awaitAnalyticsEntry(marker);
        assertEquals(List.of("tool-forward"), toList(entry.get("execution_path")));
    }

    @Test
    @DialConfigLocation("dial-config/mcp-analytics-log.json")
    void testMcpCallWithStreamingResponseWritesExecutionPathToAnalyticsLog() throws Exception {
        String marker = UUID.randomUUID().toString();
        TestWebServer.Handler handler = request -> {
            request.getBody().readString(StandardCharsets.UTF_8);
            return new MockResponse().setBody("event: message\ndata: " + MCP_RESPONSE.strip() + "\n\n")
                    .setHeader("Content-Type", "text/event-stream");
        };

        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response response = send(HttpMethod.POST, "/v1/toolset/tool-no-forward/mcp", null, toolCall(marker),
                    "Content-Type", "application/json");

            assertEquals(200, response.status());
        }

        JsonNode entry = awaitAnalyticsEntry(marker);
        assertEquals(List.of("tool-no-forward"), toList(entry.get("execution_path")));
        assertEquals("tool-no-forward", entry.get("deployment").asText());
        assertEquals("200", entry.get("response").get("status").asText());
    }

    private static TestWebServer.Handler upstream(AtomicReference<String> upstreamApiKey) {
        return request -> {
            upstreamApiKey.set(request.getHeader(Proxy.HEADER_API_KEY));
            request.getBody().readString(StandardCharsets.UTF_8);
            return new MockResponse().setBody(MCP_RESPONSE).setHeader("Content-Type", "application/json");
        };
    }

    private static String toolCall(String marker) {
        return """
                {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "tool", "arguments": {"marker": "%s"}}}
                """.formatted(marker);
    }

    private static List<String> toList(JsonNode array) {
        assertNotNull(array, "execution_path is missing from the analytics entry");
        return ProxyUtil.MAPPER.convertValue(array, ProxyUtil.MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    // the key is removed from Redis asynchronously when the request is finalized
    private void awaitPerRequestKeyInvalidated(String perRequestKey) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                apiKeyStore.getApiKeyData(perRequestKey, null).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                assertInstanceOf(HttpException.class, e.getCause());
                assertEquals(HttpStatus.UNAUTHORIZED, ((HttpException) e.getCause()).getStatus());
                return;
            }
            Thread.sleep(50);
        }
        fail("Per request key is still valid after the request is completed");
    }

    // the analytics entry is written asynchronously by gflog, so poll the file until the entry shows up
    private JsonNode awaitAnalyticsEntry(String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String line = findLine(marker);
            if (line != null) {
                return ProxyUtil.MAPPER.readTree(line);
            }
            Thread.sleep(50);
        }
        return fail("No analytics entry with marker " + marker + " in " + analyticsLog);
    }

    private static String findLine(String marker) throws IOException {
        if (!Files.exists(analyticsLog)) {
            return null;
        }
        // decoded leniently: the buffer may be flushed in the middle of a multibyte character
        String written = new String(Files.readAllBytes(analyticsLog), StandardCharsets.UTF_8);
        // a line is complete only once its newline is written, the buffer may be flushed in the middle of an entry
        int completeLength = written.lastIndexOf('\n') + 1;
        return written.substring(0, completeLength).lines()
                .filter(line -> line.contains(marker))
                .findFirst()
                .orElse(null);
    }
}
