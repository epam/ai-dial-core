package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import com.epam.aidial.core.server.ExportApiSupport.Logs;
import com.epam.aidial.core.server.ExportApiSupport.Reply;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authorization of {@code platform/} resources through the real server, one representative of each kind that a platform Application can
 * depend on.
 *
 * <p>The real {@code AccessService} grants no one any permission on {@code platform/}, so the explicit-admin gate alone authorizes the
 * platform resources. A type that is not allowed in its bucket ({@code EntityBucketBinding}) is not followed and not read, which a Model
 * in {@code public/} shows.
 */
public class ExportPlatformAuthorizationApiTest extends ResourceBaseTest {

    private static final String SCHEMA_ID = "https://dial.example/schemas/platform-auth";
    private static final String ROOT = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/root\"}]}";

    private static final String APPLICATION_TYPE_SCHEMA = """
            {
              "$schema": "https://dial.epam.com/application_type_schemas/schema#",
              "$id": "%s",
              "dial:applicationTypeEditorUrl": "https://dial.example/editor",
              "dial:applicationTypeViewerUrl": "https://dial.example/viewer",
              "dial:applicationTypeDisplayName": "Platform auth",
              "dial:applicationTypeCompletionEndpoint": "http://localhost:4848/chat/completions",
              "properties": {
                "prop": {"type": "string", "dial:meta": {"dial:propertyKind": "server", "dial:propertyOrder": 1}}
              }
            }
            """.formatted(SCHEMA_ID);

    @BeforeEach
    void storeTheScenario() {
        put("/v1/schemas/platform/auth-schema", APPLICATION_TYPE_SCHEMA);
        put("/v1/toolsets/platform/tools", "{\"endpoint\":\"http://localhost:9876\",\"transport\":\"HTTP\",\"display_name\":\"Tools\"}");
        put("/v1/interceptors/platform/icpt", "{\"endpoint\":\"http://localhost:7001/interceptor\"}");
        store("platform/applications/dep", "{\"endpoint\":\"http://dep.invalid/chat\"}");
        store("platform/applications/root", """
                {"application_type_schema_id":"%s","interceptors":["icpt"],
                 "dependencies":["applications/platform/dep","toolsets/platform/tools","models/public/m"]}
                """.formatted(SCHEMA_ID));
        store("public/models/m", "{\"type\":\"chat\",\"endpoint\":\"http://public-model.invalid/chat\"}");
    }

    private void put(String path, String body) {
        verify(send(HttpMethod.PUT, path, null, body, "authorization", "admin", "If-None-Match", "*"), 200);
    }

    private void store(String blobPath, String json) {
        ExportApiSupport.storeJson(dial, blobPath, json);
    }

    @Test
    void testPlatformRootAndPlatformDependenciesAreReadThroughTheAdminGateAlone() throws Exception {
        Reply reply;
        List<String> warnings;
        try (Logs logs = new Logs()) {
            reply = ExportApiSupport.post(serverPort, ROOT);
            warnings = logs.lines(Level.WARN, "Export: a reference is not exported");
        }

        assertEquals(200, reply.status());
        Map<String, byte[]> entries = reply.entries();
        assertEquals(List.of("platform/applications/root", "platform/app_type_schemas/auth-schema", "platform/interceptors/icpt",
                "platform/applications/dep", "platform/toolsets/tools"), ExportApiSupport.resourceNames(entries),
                "the root, then the references of its body in the order that the export finds them");
        assertEquals(6, entries.size(), "the manifest and the five resources");

        // a type that is not bound to its bucket (a Model in public/) is not followed and is not read
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("resource=application:platform:root pointer=/dependencies/2 reason=unsupported"), warnings.get(0));
        entries.values().forEach(body -> assertFalse(new String(body, StandardCharsets.UTF_8).contains("public-model.invalid"),
                "the Model in public/ is not read"));
    }
}
