package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import com.epam.aidial.core.server.ExportApiSupport.Logs;
import com.epam.aidial.core.server.ExportApiSupport.Reply;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dependency discovery through the real server, from a stored Application to the archive: the references of its fixed fields and of the
 * values that {@code dial:resource} markers hold in {@code application_properties} and {@code catalog_properties} are found, resolved
 * against the real configuration and storage, and exported. A reference that is not followed is one log record.
 * {@code DeploymentEdgeDiscoveryTest} and {@code ExportStreamerTest} cover each path with mocks; this class covers them together once.
 */
public class ExportSchemaMarkerDiscoveryApiTest extends ResourceBaseTest {

    private static final String SCHEMA_ID = "https://dial.example/schemas/quick";
    private static final String CATALOG_ID = "https://dial.example/catalog/quick";

    private static final String APPLICATION_TYPE_SCHEMA = """
            {
              "$schema": "https://dial.epam.com/application_type_schemas/schema#",
              "$id": "%s",
              "dial:applicationTypeEditorUrl": "https://dial.example/editor",
              "dial:applicationTypeViewerUrl": "https://dial.example/viewer",
              "dial:applicationTypeDisplayName": "Quick",
              "dial:applicationTypeCompletionEndpoint": "http://localhost:4848/chat/completions",
              "properties": {
                "tool": {"type": "string", "dial:resource": true, "dial:meta": {"dial:propertyKind": "server", "dial:propertyOrder": 1}},
                "model": {"type": "string", "dial:resource": true, "dial:meta": {"dial:propertyKind": "server", "dial:propertyOrder": 2}},
                "missing_model": {"type": "string", "dial:resource": true, "dial:meta": {"dial:propertyKind": "server", "dial:propertyOrder": 3}},
                "link": {"type": "string", "dial:resource": true, "dial:meta": {"dial:propertyKind": "server", "dial:propertyOrder": 4}},
                "prompt": {"type": "string", "dial:resource": true, "dial:meta": {"dial:propertyKind": "server", "dial:propertyOrder": 5}}
              }
            }
            """.formatted(SCHEMA_ID);

    private static final String CATALOG_SCHEMA = """
            {
              "$schema": "https://dial.epam.com/catalog_schemas/schema#",
              "$id": "%s",
              "dial:catalogEntityType": "application",
              "dial:catalogDisplayName": "Quick",
              "type": "object"
            }
            """.formatted(CATALOG_ID);

    private static final String TOOLSET = "{\"endpoint\":\"http://localhost:9876\",\"transport\":\"HTTP\",\"display_name\":\"Tools\"}";
    private static final String MODEL = "{\"type\":\"chat\",\"endpoint\":\"http://localhost:7001/openai/deployments/m/chat/completions\"}";

    /**
     * Uses the schema, a catalog schema, a dependency that refers back to it, one that names nothing, and five marked properties of every kind.
     * The values that name nothing, the link and its credentials must reach no log record.
     */
    private static final String MAIN_APPLICATION = """
            {
              "application_type_schema_id": "%s",
              "catalog_schema_id": "%s",
              "dependencies": ["applications/platform/partner", "sk-SECRET-DEP"],
              "application_properties": {
                "tool": "toolsets/platform/tools",
                "model": "model-m",
                "missing_model": "sk-SECRET-NAME",
                "link": "https://user:SECRET-PASS@example.com/x?token=SECRET-TOKEN",
                "prompt": "conversations/public/p1"
              }
            }
            """.formatted(SCHEMA_ID, CATALOG_ID);

    private static final String PARTNER_APPLICATION =
            "{\"endpoint\":\"http://partner.invalid/chat\",\"dependencies\":[\"applications/platform/main-app\"]}";

    @BeforeEach
    void storeTheScenario() {
        put("/v1/schemas/platform/quick-schema", APPLICATION_TYPE_SCHEMA);
        put("/v1/catalog_schemas/platform/quick-catalog", CATALOG_SCHEMA);
        put("/v1/toolsets/platform/tools", TOOLSET);
        put("/v1/models/platform/model-m", MODEL);
        store("main-app", MAIN_APPLICATION);
        store("partner", PARTNER_APPLICATION);
    }

    private void put(String path, String body) {
        verify(send(HttpMethod.PUT, path, null, body, "authorization", "admin", "If-None-Match", "*"), 200);
    }

    private void store(String name, String json) {
        ExportApiSupport.storeJson(dial, "platform/applications/" + name, json);
    }

    @Test
    void testEverythingThatTheApplicationRefersToIsFoundResolvedAndExportedOrLogged() throws Exception {
        Reply reply;
        List<String> findings;
        String exportLog;
        String wholeLog;
        try (Logs logs = new Logs()) {
            reply = ExportApiSupport.post(serverPort, ExportApiSupport.rootsRequest("applications/platform/main-app"));
            findings = logs.lines(Level.WARN, "Export: a reference is not exported");
            exportLog = String.join("\n", logs.lines(Level.WARN, "Export: ")) + "\n" + String.join("\n", logs.lines(Level.INFO, "Export: "));
            wholeLog = logs.text();
        }

        assertEquals(200, reply.status());
        assertEquals("application/zip", reply.contentType());
        Map<String, byte[]> entries = reply.entries();

        // the closure, each resource exactly once although the partner refers back to the root
        List<String> names = ExportApiSupport.resourceNames(entries);
        assertEquals("platform/applications/main-app", names.get(0), "the selected root comes first");
        assertEquals(Set.of("platform/applications/main-app", "platform/applications/partner", "platform/app_type_schemas/quick-schema",
                "platform/catalog_schemas/quick-catalog", "platform/toolsets/tools", "platform/models/model-m"), Set.copyOf(names));
        assertEquals(6, names.size());
        JsonNode exported = ProxyUtil.MAPPER.readTree(entries.get("platform/applications/main-app"));
        assertEquals("toolsets/platform/tools", exported.get("application_properties").get("tool").asText(), "the properties are exported as stored");
        assertEquals(SCHEMA_ID, exported.get("application_type_schema_id").asText());
        assertEquals(SCHEMA_ID, ProxyUtil.MAPPER.readTree(entries.get("platform/app_type_schemas/quick-schema")).get("$id").asText());
        assertEquals(CATALOG_ID, ProxyUtil.MAPPER.readTree(entries.get("platform/catalog_schemas/quick-catalog")).get("$id").asText());
        assertEquals(1 + names.size(), entries.size(), "the manifest and one entry for each resource");

        // the unfollowed references are one record each, by resource and pointer; the link is external and is at INFO
        assertEquals(3, findings.size(), findings.toString());
        assertTrue(findings.stream().anyMatch(line -> line.contains("resource=application:platform:main-app pointer=/dependencies/1 reason=missing")));
        assertTrue(findings.stream().anyMatch(line -> line.contains("pointer=/application_properties/missing_model reason=unresolved-short-name")));
        assertTrue(findings.stream().anyMatch(line -> line.contains("pointer=/application_properties/prompt reason=unsupported")));
        assertTrue(exportLog.contains("pointer=/application_properties/link"), "the external link is recorded");
        assertFalse(exportLog.contains("a reference is not exported: resource=application:platform:main-app pointer=/application_properties/link"),
                "an external link is not a finding");
        // no raw reference value is in the log of the export or in the archive
        for (String value : List.of("SECRET-PASS", "SECRET-TOKEN", "SECRET-NAME", "SECRET-DEP", "user:", "example.com")) {
            assertFalse(exportLog.contains(value), "a log record of the export holds " + value);
            assertFalse(wholeLog.contains(value), "a log holds " + value);
        }
    }
}
