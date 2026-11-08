package com.epam.aidial.core.server;

import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.ExportBodyRedactor;
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
 * Authorization of {@code public/} resources through the real server and the real {@code AccessService}:
 * a public root is authorized by the access rules of the configuration, a public Application dependency is authorized
 * separately and followed, every other dependency in a public or a private location is {@code unsupported} and is not read,
 * and a caller without explicit admin access gets 403.
 *
 * <p>The rules are those of the test settings ({@code access.admin.rules}: role {@code admin}), which give the explicit admin
 * ALL access on {@code public/}. A denial of a public dependency to a caller that passed the gate cannot be reached with them,
 * so it is covered by the unit tests of {@code ExportClosureWalkerTest}, not here. The archive is written under the production
 * redactor; what it does with every field is pinned by {@code ExportBodyRedactorApiTest}.
 */
public class ExportPublicAuthorizationApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final String ROOT = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/public/root__1.0.0\"}]}";

    private record Reply(int status, String contentType, byte[] body) {
    }

    @BeforeEach
    void storeTheScenario() {
        dial.getProxy().setExportBodyRedactor(new ExportBodyRedactor());
        String userBucket = ProxyUtil.convertToObject(send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user").body(), Map.class)
                .get("bucket").toString();
        store("public/applications/root__1.0.0", """
                {"endpoint":"http://root.invalid/chat","dependencies":[
                  "applications/public/dep__1.0.0",
                  "toolsets/public/ts",
                  "applications/%s/private-app"]}
                """.formatted(userBucket));
        store("public/applications/dep__1.0.0", "{\"endpoint\":\"http://dep.invalid/chat\"}");
        store("public/toolsets/ts", "{\"endpoint\":\"http://ts.invalid\",\"transport\":\"HTTP\",\"auth_settings\":{"
                + "\"authentication_type\":\"OAUTH\",\"client_id\":\"c\",\"client_secret\":\"PUBLIC-TOOLSET-SECRET\","
                + "\"code_verifier\":\"PUBLIC-TOOLSET-VERIFIER\",\"token_endpoint\":\"https://a.invalid/t\"}}");
    }

    private void store(String blobPath, String json) {
        dial.getStorage().store(blobPath, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    private Reply export(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + PATH))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse("-"), response.body());
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

    private static JsonNode registryOf(Map<String, byte[]> entries) throws Exception {
        return ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY));
    }

    private static Set<String> resourcesOf(JsonNode registry) {
        Set<String> resources = new java.util.HashSet<>();
        for (JsonNode resource : registry.get("resources")) {
            resources.add(resource.get("type").asText() + "/" + resource.get("path").asText());
        }
        return resources;
    }

    @Test
    void testPublicRootPublicApplicationAndPublicToolSetAreFollowedAndTheUserBucketOneIsUnsupported() throws Exception {
        Reply reply = export("admin", ROOT);

        assertEquals(200, reply.status());
        assertEquals("application/zip", reply.contentType());
        Map<String, byte[]> entries = entriesOf(reply);
        JsonNode registry = registryOf(entries);

        // the root, its public Application dependency and its public ToolSet dependency are in the archive
        assertEquals(Set.of("application/root__1.0.0", "application/dep__1.0.0", "toolset/ts"), resourcesOf(registry));
        assertEquals(9, entries.size(), "the manifest, the registry, the two Applications and the ToolSet with their sidecars, and the verification entry");
        assertEquals("selected", ExportRegistryJson.resources(registry).get(0).get("role").asText());
        JsonNode dependency = ExportRegistryJson.resources(registry).get(1);
        assertEquals("dependency", dependency.get("role").asText());
        assertEquals("/dependencies/0", ExportRegistryJson.referencesTo(registry, ExportRegistryJson.keyAt(registry, 1)).get(0).get("pointer").asText());

        // a user-bucket Application is listed as unsupported, by pointer, with no body in the archive
        List<JsonNode> edges = ExportRegistryJson.unresolved(registry);
        assertEquals(1, edges.size());
        assertEquals("/dependencies/2", edges.get(0).get("pointer").asText());
        assertEquals("unsupported", edges.get(0).get("status").asText());
        assertEquals(1, registry.get("summary").get("edges").get("unsupported").asInt());
        assertTrue(entries.values().stream().anyMatch(body -> new String(body, StandardCharsets.UTF_8).contains("ts.invalid")),
                "the definition of the public ToolSet is exported");
        assertTrue(registry.get("discovery").get("complete").asBoolean());

        // the public ToolSet has the origin public, is required by the root, and its secrets are in no entry
        JsonNode toolset = ExportRegistryJson.resources(registry).stream().filter(resource -> "toolset".equals(resource.get("type").asText()))
                .findFirst().orElseThrow();
        assertEquals("public", toolset.get("origin").asText());
        assertEquals("dependency", toolset.get("role").asText());
        assertEquals(1, toolset.get("requiredByRoots").size());
        entries.forEach((name, body) -> {
            String text = new String(body, StandardCharsets.UTF_8);
            assertFalse(text.contains("PUBLIC-TOOLSET-SECRET") || text.contains("PUBLIC-TOOLSET-VERIFIER"), name);
        });
    }
}
