package com.epam.aidial.core.server;

import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A failure of the Runner of an application type schema, whatever its cause, is a dependency finding and not a request failure: the
 * export is a partial archive with 200, the schema edge is {@code unresolved}, the discovery is incomplete, and neither the registry
 * nor the log holds the cause, the endpoint or the Runner's content.
 */
public class ExportRunnerFailureApiTest extends ResourceBaseTest {

    private static final String SCHEMA_ID = "https://dial.example/schemas/runner";
    private static final BodyRedactor PASS_THROUGH = (identity, body) -> body.getBytes(StandardCharsets.UTF_8);

    private final AtomicInteger runnerStatus = new AtomicInteger(503);
    private HttpServer runner;

    @BeforeEach
    void startRunnerAndStoreTheScenario() throws Exception {
        dial.getProxy().setExportBodyRedactor(PASS_THROUGH);
        runner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        runner.createContext("/schema", exchange -> {
            byte[] body = "RUNNER-BODY-MARKER".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "7");
            exchange.sendResponseHeaders(runnerStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        runner.start();
        String schema = """
                {
                  "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                  "$id": "%s",
                  "dial:applicationTypeEditorUrl": "https://dial.example/editor",
                  "dial:applicationTypeViewerUrl": "https://dial.example/viewer",
                  "dial:applicationTypeDisplayName": "Runner",
                  "dial:applicationTypeCompletionEndpoint": "http://localhost:4848/chat/completions",
                  "dial:applicationTypeSchemaEndpoint": "http://127.0.0.1:%d/schema?token=ENDPOINT-MARKER"
                }
                """.formatted(SCHEMA_ID, runner.getAddress().getPort());
        store("platform/app_type_schemas/runner-schema", schema);
        ((MergedConfigStore) dial.getProxy().getConfigStore()).rebuildNow();
        store("platform/applications/root", "{\"application_type_schema_id\":\"" + SCHEMA_ID + "\"}");
    }

    @AfterEach
    void stopRunner() {
        runner.stop(0);
    }

    private void store(String blobPath, String json) {
        dial.getStorage().store(blobPath, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> export() throws IOException, InterruptedException {
        String body = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/root\"}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/v1/admin/export"))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", "admin")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private void assertPartialExport(HttpResponse<byte[]> response, String what) throws Exception {
        assertEquals(200, response.statusCode(), what);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        String registryText = new String(entries.get(ExportArchive.REGISTRY_ENTRY), StandardCharsets.UTF_8);
        JsonNode registry = ProxyUtil.MAPPER.readTree(registryText);
        List<JsonNode> unresolved = ExportRegistryJson.unresolved(registry);
        assertEquals(1, unresolved.size(), what);
        assertEquals("unresolved", unresolved.get(0).get("status").asText(), what);
        assertEquals("/application_type_schema_id", unresolved.get(0).get("pointer").asText(), what);
        assertFalse(registry.get("discovery").get("complete").asBoolean(), what);
        assertFalse(registryText.contains("RUNNER-BODY-MARKER") || registryText.contains("ENDPOINT-MARKER") || registryText.contains("127.0.0.1"),
                "the registry holds no cause: " + what);
    }

    @Test
    void testRunnerFailureOfAnyStatusOrNoRunnerAtAllIsUnresolvedSchemaEdgeInPartialExport() throws Exception {
        for (int status : new int[] {503, 500, 429, 404, 418, 302}) {
            runnerStatus.set(status);

            assertPartialExport(export(), "Runner status " + status);
        }

        runner.stop(0);

        assertPartialExport(export(), "Runner stopped");
    }
}
