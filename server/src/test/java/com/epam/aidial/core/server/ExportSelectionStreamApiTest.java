package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import com.epam.aidial.core.server.ExportApiSupport.Logs;
import com.epam.aidial.core.server.ExportApiSupport.Reply;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response of the selective export on {@code POST /v1/admin/export}, end to end through the real server: the stream, the entry of each
 * resource, the absence of secrets and the logged references.
 */
public class ExportSelectionStreamApiTest extends ResourceBaseTest {

    /** The live stand of a developer may hold 9876, which the other tests of the toolset use. */
    private static final int TOOLSET_PORT = 9877;
    private static final String SECRET = "the-client-secret-8f3a";
    private static final String ROUTE_KEY = "ROUTE-UPSTREAM-KEY-VALUE";
    private static final String TOOLSET_SECRET = "the-toolset-secret-77aa";
    private static final String MODEL_KEY = "MODEL-UPSTREAM-KEY-VALUE";
    private static final String ROOT_REQUEST = ExportApiSupport.rootsRequest("applications/platform/exported");

    private Reply post(String body) throws Exception {
        return ExportApiSupport.post(serverPort, body);
    }

    private void store(String name, String json) {
        ExportApiSupport.storeJson(dial, "platform/applications/" + name, json);
    }

    private void put(String path, String body) {
        verify(send(HttpMethod.PUT, path, null, body, "authorization", "admin", "If-None-Match", "*"), 200);
    }

    private JsonNode adminGet(String path) throws IOException {
        Response get = send(HttpMethod.GET, path, null, "", "authorization", "admin");
        verify(get, 200);
        return ProxyUtil.MAPPER.readTree(get.body());
    }

    /**
     * What the admin GET adds to the entity and the entry does not have: the name and status of the API projection and the computed sign-in
     * statuses.
     */
    private static JsonNode withoutApiFields(JsonNode tree) {
        ObjectNode copy = tree.deepCopy();
        copy.remove("name");
        copy.remove("status");
        List<JsonNode> authSettings = new ArrayList<>();
        authSettings.add(copy.get("auth_settings"));
        if (copy.has("external_services")) {
            copy.get("external_services").forEach(service -> authSettings.add(service.get("auth_settings")));
        }
        authSettings.stream().filter(auth -> auth instanceof ObjectNode).forEach(auth -> ((ObjectNode) auth)
                .remove(List.of("user_level_auth_status", "app_level_auth_status", "global_auth_status")));
        return copy;
    }

    /** Stores, through the admin API (so that secrets are encrypted at rest), a graph with every kind of secret. */
    private void storeGraphWithSecrets() throws Exception {
        put("/v1/interceptors/platform/icpt", "{\"endpoint\":\"http://localhost:4088/api/v1/interceptor/handle\"}");
        put("/v1/models/platform/mdl", "{\"type\":\"chat\",\"endpoint\":\"http://localhost:7001/chat\",\"interceptors\":[\"icpt\"],"
                + "\"upstreams\":[{\"endpoint\":\"http://localhost:7001/u\",\"key\":\"" + MODEL_KEY + "\"}]}");
        try (TestWebServer ignore = new TestWebServer(TOOLSET_PORT)) {
            put("/v1/toolsets/platform/tools", "{\"endpoint\":\"http://localhost:" + TOOLSET_PORT + "\",\"transport\":\"HTTP\",\"display_name\":\"Tools\","
                    + "\"auth_settings\":{\"authentication_type\":\"OAUTH\",\"client_id\":\"tcid\",\"client_secret\":\"" + TOOLSET_SECRET + "\","
                    + "\"redirect_uri\":\"http://localhost:3000/auth/signin\",\"authorization_endpoint\":\"https://static.auth.example.com/authorize\","
                    + "\"token_endpoint\":\"https://static.auth.example.com/token\"}}");
        }
        put("/v1/applications/platform/exported", """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Secret-bearing App",
                  "description": "d",
                  "dependencies": ["mdl", "toolsets/platform/tools"],
                  "routes": {"r": {"paths": ["/x"], "upstreams": [{"endpoint": "http://localhost:7001/r", "key": "%s"}]}},
                  "external_services": {
                    "svc1": {
                      "display_name": "Salesforce",
                      "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "my-client-id",
                        "client_secret": "%s",
                        "redirect_uri": "http://localhost:3000/auth/signin",
                        "authorization_endpoint": "https://static.auth.example.com/authorize",
                        "token_endpoint": "https://static.auth.example.com/token"
                      }
                    }
                  }
                }
                """.formatted(ROUTE_KEY, SECRET));
    }

    @Test
    void testSelectedApplicationAndItsDependencyAreStreamedAsZipInBreadthFirstOrder() throws Exception {
        store("exported", "{\"endpoint\":\"http://application.invalid/chat\",\"dependencies\":[\"applications/platform/dependency\"]}");
        store("dependency", "{\"endpoint\":\"http://dependency.invalid/chat\"}");

        Reply reply = post(ROOT_REQUEST);

        assertEquals(200, reply.status());
        assertEquals("application/zip", reply.contentType());
        assertTrue(reply.disposition().startsWith("attachment; filename=\"dial-export-"), reply.disposition());
        List<String> names = new ArrayList<>(reply.entries().keySet());
        assertEquals(List.of(ExportArchive.MANIFEST_ENTRY, "platform/applications/exported", "platform/applications/dependency"), names,
                "the manifest first, then one entry per resource at its storage path, and nothing else");
    }

    @Test
    void testManifestIsTheFirstEntryAndHoldsFormatModeVersionAndTime() throws Exception {
        store("exported", "{\"endpoint\":\"http://application.invalid/chat\"}");

        Reply reply = post(ROOT_REQUEST);

        assertEquals(200, reply.status());
        assertEquals(".dial-export/manifest.json", reply.entries().keySet().iterator().next());
        JsonNode manifest = ProxyUtil.MAPPER.readTree(reply.entries().get(ExportArchive.MANIFEST_ENTRY));
        assertEquals(2, manifest.get("formatVersion").asInt());
        assertEquals("selective", manifest.get("mode").asText());
        assertFalse(manifest.get("coreVersion").asText().isBlank());
        assertTrue(Instant.parse(manifest.get("createdAt").asText()).isBefore(Instant.now().plusSeconds(60)));
        assertEquals(4, manifest.size());
    }

    @Test
    void testSuccessfulExportIsRecordedAtInfoAsStartedAndCompletedWithCountsAndNoRoot() throws Exception {
        store("exported", "{\"endpoint\":\"http://application.invalid/chat\",\"dependencies\":[\"applications/platform/dependency\"]}");
        store("dependency", "{\"endpoint\":\"http://dependency.invalid/chat\"}");
        Reply reply;
        List<String> started;
        List<String> completed;
        String logText;
        try (Logs logs = new Logs()) {
            reply = post(ROOT_REQUEST);
            started = logs.lines(Level.INFO, "Export started");
            completed = logs.lines(Level.INFO, "Export completed");
            logText = logs.text();
        }

        assertEquals(200, reply.status());
        assertEquals(List.of("INFO Export started: roots=1"), started);
        assertEquals(1, completed.size(), completed.toString());
        assertTrue(completed.get(0).matches("INFO Export completed: duration=\\d+ms, resources=2"), completed.get(0));
        assertEquals(3, reply.entries().size(), "the manifest and the two resources");
        assertFalse(started.get(0).contains("exported") || completed.get(0).contains("exported"), "a record names no root");
        assertFalse(logText.contains("Admin export rejected"), logText);
    }

    @Test
    void testEveryEntryIsTheAdminGetProjectionOfItsEntityAndNoSecretIsInTheArchive() throws Exception {
        storeGraphWithSecrets();
        JsonNode applicationGet = adminGet("/v1/applications/platform/exported");
        JsonNode modelGet = adminGet("/v1/models/platform/mdl");
        JsonNode interceptorGet = adminGet("/v1/interceptors/platform/icpt");
        JsonNode toolSetGet = adminGet("/v1/toolsets/platform/tools");

        Reply reply = post(ROOT_REQUEST);

        assertEquals(200, reply.status());
        Map<String, byte[]> entries = reply.entries();
        assertEquals(List.of("platform/applications/exported", "platform/models/mdl", "platform/toolsets/tools", "platform/interceptors/icpt"),
                ExportApiSupport.resourceNames(entries), "the root, then its references in the order of the body, then theirs");
        assertEquals(withoutApiFields(applicationGet), withoutApiFields(ProxyUtil.MAPPER.readTree(entries.get("platform/applications/exported"))));
        assertEquals(withoutApiFields(modelGet), withoutApiFields(ProxyUtil.MAPPER.readTree(entries.get("platform/models/mdl"))));
        assertEquals(withoutApiFields(interceptorGet), withoutApiFields(ProxyUtil.MAPPER.readTree(entries.get("platform/interceptors/icpt"))));
        assertEquals(withoutApiFields(toolSetGet), withoutApiFields(ProxyUtil.MAPPER.readTree(entries.get("platform/toolsets/tools"))));
        JsonNode application = ProxyUtil.MAPPER.readTree(entries.get("platform/applications/exported"));
        assertEquals("8f3a", application.at("/external_services/svc1/auth_settings/client_secret_hint").asText(),
                "the hint comes from the decrypted secret, not from its ciphertext");
        assertEquals("77aa", ProxyUtil.MAPPER.readTree(entries.get("platform/toolsets/tools")).at("/auth_settings/client_secret_hint").asText());

        entries.forEach((name, content) -> {
            String text = new String(content, StandardCharsets.UTF_8);
            assertFalse(text.contains("ENC["), "ciphertext in " + name);
            for (String secret : List.of(SECRET, ROUTE_KEY, TOOLSET_SECRET, MODEL_KEY)) {
                assertFalse(text.contains(secret), secret + " in " + name);
            }
        });
    }

    @Test
    void testBodyThatCannotBeBoundCutsTheStreamLogsTheResourceAndReleasesTheBucketLocks() throws Exception {
        store("exported", "{\"endpoint\":\"http://application.invalid/chat\",\"dependencies\":[\"applications/platform/mistyped\"]}");
        store("mistyped", "{\"endpoint\":{\"nested\":\"SECRET-IN-BODY\"}}");
        String logText;
        List<String> aborts;
        try (Logs logs = new Logs()) {
            ExportApiSupport.assertExportFails(serverPort, ROOT_REQUEST);
            logText = logs.text();
            aborts = logs.lines(Level.WARN, "Export aborted after the response started");
        }

        assertEquals(1, aborts.size(), logText);
        assertTrue(aborts.get(0).contains("resource=application:platform:mistyped,"), "the abort record holds the key: " + aborts.get(0));
        assertTrue(aborts.get(0).contains("(resource=application:platform:mistyped)"), "the message holds the key: " + aborts.get(0));
        assertFalse(aborts.get(0).contains("applications/platform/mistyped"), "the abort record holds the URL: " + aborts.get(0));
        assertFalse(logText.contains("SECRET-IN-BODY"), "the log holds no value of the body");
        ExportApiSupport.awaitLocksReleased(dial.getProxy().getLockService());
    }
}
