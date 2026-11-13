package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import com.epam.aidial.core.server.ExportApiSupport.Logs;
import com.epam.aidial.core.server.ExportApiSupport.Reply;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failure of the Runner of an application type schema, whatever its cause, is a finding in the log and not a request failure: the export
 * is a partial archive with 200, and neither the archive nor the log holds the cause, the endpoint or the Runner's content.
 */
public class ExportRunnerFailureApiTest extends ResourceBaseTest {

    private static final String SCHEMA_ID = "https://dial.example/schemas/runner";

    private final AtomicInteger runnerStatus = new AtomicInteger(503);
    private HttpServer runner;

    @BeforeEach
    void startRunnerAndStoreTheScenario() throws Exception {
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
        ExportApiSupport.storeJson(dial, blobPath, json);
    }

    private void assertPartialExport(String what) throws Exception {
        Reply reply;
        List<String> findings;
        String exportLog;
        try (Logs logs = new Logs()) {
            reply = ExportApiSupport.post(serverPort, ExportApiSupport.rootsRequest("applications/platform/root"));
            findings = logs.lines(Level.WARN, "Export: ");
            exportLog = String.join("\n", findings);
        }

        assertEquals(200, reply.status(), what);
        Map<String, byte[]> entries = reply.entries();
        assertEquals(List.of("platform/applications/root"), ExportApiSupport.resourceNames(entries), what);
        assertEquals(1, findings.size(), what + ": " + findings);
        assertTrue(findings.get(0).contains("resource=application:platform:root pointer=/application_type_schema_id reason=schema-unavailable"), findings.get(0));
        assertFalse(exportLog.contains("RUNNER-BODY-MARKER") || exportLog.contains("ENDPOINT-MARKER") || exportLog.contains("127.0.0.1"),
                "the log of the export holds no cause: " + what);
        entries.forEach((name, body) -> {
            String text = new String(body, StandardCharsets.UTF_8);
            assertFalse(text.contains("RUNNER-BODY-MARKER") || text.contains("ENDPOINT-MARKER"), name);
        });
    }

    @Test
    void testRunnerFailureOfAnyStatusOrNoRunnerAtAllIsLoggedInPartialExport() throws Exception {
        for (int status : new int[] {503, 404, 302}) {
            runnerStatus.set(status);

            assertPartialExport("Runner status " + status);
        }

        runner.stop(0);

        assertPartialExport("Runner stopped");
    }
}
