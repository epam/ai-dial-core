package com.epam.aidial.core.server;

import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authorization of {@code platform/} resources through the real server, one representative of each kind that
 * a platform Application can depend on: an Application, a ToolSet, an interceptor and an application type schema.
 *
 * <p>The real {@code AccessService} grants no one any permission on {@code platform/} (its admin rule covers {@code public/},
 * review buckets and public source folders only), so an export that reads the platform root and its dependencies shows that the
 * platform resources are authorized by the explicit-admin gate alone and not by {@code AccessService}. The bucket binding of
 * the configuration API ({@code EntityBucketBinding}) is checked before every read: a type that is not allowed in its bucket is
 * {@code unsupported} and is not read, which this class shows with a Model in {@code public/}. The archive is written under a
 * pass-through redactor; the production redactor is pinned by {@code ExportBodyRedactorApiTest}.
 */
public class ExportPlatformAuthorizationApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final String SCHEMA_ID = "https://dial.example/schemas/platform-auth";
    private static final BodyRedactor PASS_THROUGH = (identity, body) -> body.getBytes(StandardCharsets.UTF_8);
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

    private record Reply(int status, byte[] body) {
    }

    @BeforeEach
    void storeTheScenario() {
        dial.getProxy().setExportBodyRedactor(PASS_THROUGH);
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
        dial.getStorage().store(blobPath, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    private Reply export(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + PATH))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), response.body());
    }

    private static Map<String, byte[]> entriesOf(Reply reply) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(reply.body()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static Map<String, String> originsOf(JsonNode registry) {
        Map<String, String> origins = new HashMap<>();
        for (JsonNode resource : registry.get("resources")) {
            origins.put(resource.get("type").asText() + "/" + resource.get("path").asText(), resource.get("origin").asText());
        }
        return origins;
    }

    @Test
    void testPlatformRootAndPlatformDependenciesAreReadThroughTheAdminGateAlone() throws Exception {
        Reply reply = export("admin", ROOT);

        assertEquals(200, reply.status());
        Map<String, byte[]> entries = entriesOf(reply);
        JsonNode registry = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY));

        Map<String, String> origins = originsOf(registry);
        assertEquals(Set.of("application/root", "application/dep", "toolset/tools", "interceptor/icpt", "application-type-schema/auth-schema"),
                origins.keySet());
        assertEquals(Set.of("platform"), new HashSet<>(origins.values()));
        assertEquals("selected", ExportRegistryJson.resources(registry).get(0).get("role").asText());
        assertEquals(13, entries.size(), "the manifest, the registry, five resources with their sidecars and the verification entry");

        // a type that is not bound to its bucket (a Model in public/) is unsupported and is not read
        List<JsonNode> edges = ExportRegistryJson.unresolved(registry);
        assertEquals(1, edges.size());
        assertEquals("/dependencies/2", edges.get(0).get("pointer").asText());
        assertEquals("unsupported", edges.get(0).get("status").asText());
        entries.values().forEach(body -> assertFalse(new String(body, StandardCharsets.UTF_8).contains("public-model.invalid"),
                "the Model in public/ is not read"));
        assertTrue(registry.get("discovery").get("complete").asBoolean());
    }
}
