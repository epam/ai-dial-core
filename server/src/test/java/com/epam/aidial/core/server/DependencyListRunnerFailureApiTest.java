package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import com.epam.aidial.core.server.DependencyListApiSupport.Logs;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failure of the Runner of an application type schema, whatever its cause, is a finding in the log and not a request failure: the answer
 * is {@code 200} with what could be resolved, and neither the answer nor the log holds the cause, the endpoint or the Runner's content.
 */
public class DependencyListRunnerFailureApiTest extends ResourceBaseTest {

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
        DependencyListApiSupport.storeJson(dial, blobPath, json);
    }

    private void assertAnswerWithoutTheSchema(String what) throws Exception {
        Response response;
        List<String> findings;
        String log;
        try (Logs logs = new Logs()) {
            response = send(HttpMethod.POST, DependencyListApiSupport.PATH, null, DependencyListApiSupport.rootsRequest("applications/platform/root"),
                    "authorization", "admin");
            findings = logs.lines(Level.WARN, "Dependency list: ");
            log = String.join("\n", findings);
        }

        assertEquals(200, response.status(), what);
        JsonNode root = ProxyUtil.MAPPER.readTree(response.body()).get("roots").get(0);
        assertEquals("root", root.get("name").asText(), what);
        assertEquals(0, root.get("dependencies").size(), what + ": the elements that do not depend on the schema are listed");
        assertEquals(1, findings.size(), what + ": " + findings);
        assertTrue(findings.get(0).contains("resource=application:platform:root pointer=/application_type_schema_id reason=schema-unavailable"),
                findings.get(0));
        assertFalse(log.contains("RUNNER-BODY-MARKER") || log.contains("ENDPOINT-MARKER") || log.contains("127.0.0.1"),
                "the log holds no cause: " + what);
        assertFalse(response.body().contains("RUNNER-BODY-MARKER") || response.body().contains("ENDPOINT-MARKER"), what);
    }

    @Test
    void testRunnerFailureOfAnyStatusOrNoRunnerAtAllIsLoggedAndTheAnswerIsStill200() throws Exception {
        for (int status : new int[] {503, 404, 302}) {
            runnerStatus.set(status);

            assertAnswerWithoutTheSchema("Runner status " + status);
        }

        runner.stop(0);

        assertAnswerWithoutTheSchema("Runner stopped");
    }
}
