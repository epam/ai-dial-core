package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry against the archive, through the real server and the production redactor: the statuses of a dependency, the
 * summary, the warnings, the origin of an entity of the file configuration that takes the place of a platform blob of the same
 * name, and the agreement of every record with the entries of the ZIP.
 */
public class ExportRegistryContractApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final String TOOLSET_SECRET = "toolset-client-secret-1";
    private static final String MODEL_SECRET = "model-upstream-secret-1";
    private static final Set<String> PUBLIC_STATUSES = Set.of("included", "unsupported", "external", "unresolved");
    private static final List<String> EDGE_KEYS = List.of("unsupported", "external", "unresolved");

    private record Download(int status, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private Download export(String rootName) throws Exception {
        String body = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/" + rootName + "\"}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + PATH))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", "admin").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Download(response.statusCode(), response.body());
    }

    private static Map<String, byte[]> entries(Download download) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(download.body()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> valuesOf(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static List<String> namesOf(JsonNode object) {
        List<String> names = new ArrayList<>();
        Iterator<String> iterator = object.fieldNames();
        iterator.forEachRemaining(names::add);
        return names;
    }

    private void putApplication(String name, String body) {
        verify(send(HttpMethod.PUT, "/v1/applications/platform/" + name, null, body, "authorization", "admin", "If-None-Match", "*"), 200);
    }

    private void putModel(String name, String body) {
        verify(send(HttpMethod.PUT, "/v1/models/platform/" + name, null, body, "authorization", "admin", "If-None-Match", "*"), 200);
    }

    @Test
    void testRegistryAgreesWithTheArchiveAndKeepsTheSecretsOutOfBoth() throws Exception {
        putModel("secret-model", "{\"type\": \"chat\", \"endpoint\": \"http://localhost:7001/chat\","
                + " \"upstreams\": [{\"endpoint\": \"http://localhost:7001\", \"key\": \"" + MODEL_SECRET + "\"}]}");
        try (TestWebServer ignore = new TestWebServer(9876)) {
            verify(send(HttpMethod.PUT, "/v1/toolsets/platform/secret-toolset", null, "{\"endpoint\": \"http://localhost:9876\", \"transport\": \"HTTP\","
                    + " \"auth_settings\": {\"authentication_type\": \"OAUTH\", \"client_id\": \"id\", \"client_secret\": \"" + TOOLSET_SECRET + "\","
                    + " \"redirect_uri\": \"http://localhost:3000/auth/signin\", \"authorization_endpoint\": \"https://static.auth.example.com/authorize\","
                    + " \"token_endpoint\": \"https://static.auth.example.com/token\"}}", "authorization", "admin", "If-None-Match", "*"), 200);
        }
        putApplication("consistent-app", "{\"endpoint\": \"http://application1/v1/completions\","
                + " \"dependencies\": [\"toolsets/platform/secret-toolset\", \"secret-model\", \"no-such-deployment\"]}");

        Download download = export("consistent-app");

        assertEquals(200, download.status(), download.text());
        Map<String, byte[]> entries = entries(download);
        List<String> names = List.copyOf(entries.keySet());
        assertEquals(ExportArchive.MANIFEST_ENTRY, names.get(0));
        assertEquals(ExportArchive.VERIFICATION_ENTRY, names.get(names.size() - 1));
        JsonNode registry = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY));

        assertRegistryAgreesWithArchive(entries, registry);
        assertEquals(1, registry.get("summary").get("edges").get("unresolved").asInt(), "the name that nothing holds");
        List<String> warnings = valuesOf(registry.get("warnings"));
        assertTrue(warnings.stream().anyMatch(warning -> warning.startsWith("A Model or Interceptor is exported as its definition only")),
                warnings.toString());

        // the secrets are in no entry, the registry included
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            assertFalse(text(entry.getValue()).contains(MODEL_SECRET) || text(entry.getValue()).contains(TOOLSET_SECRET), entry.getKey());
        }
    }

    // ---- file-origin resources

    private static final String FILE_SCHEMA_ID = "https://dial.example/custom_application_schemas/file_schema";

    /**
     * Everything that the registry says about the package must agree with the archive: the records, the keys, the entry names and their order,
     * the dependencies, the roots, the counts, the warnings and the digest of every entry.
     */
    private static void assertRegistryAgreesWithArchive(Map<String, byte[]> entries, JsonNode registry) throws Exception {
        List<String> names = List.copyOf(entries.keySet());
        JsonNode resources = registry.get("resources");
        List<String> keys = namesOf(resources);
        assertEquals(keys.stream().map(key -> resources.get(key).get("entryName").asText()).toList(), names.subList(2, names.size() - 1).stream()
                .filter(name -> !name.startsWith(".dial-system-export/metadata/")).toList(),
                "the entries of the archive are the resources of the registry, in its order");
        for (JsonNode root : registry.get("roots")) {
            assertEquals("selected", resources.get(root.asText()).get("role").asText());
        }
        Map<String, Integer> counted = new HashMap<>();
        for (String key : keys) {
            JsonNode resource = resources.get(key);
            String[] parts = key.split(":", 3);
            assertEquals(resource.get("type").asText(), parts[0], key);
            assertEquals(resource.get("origin").asText(), parts[1], key);
            for (JsonNode dependency : resource.get("dependencies")) {
                String status = dependency.get("status").asText();
                assertTrue(PUBLIC_STATUSES.contains(status), status);
                if ("included".equals(status)) {
                    assertTrue(resources.has(dependency.get("key").asText()), key + " names a resource that is not in the registry");
                } else {
                    assertNull(dependency.get("key"));
                    counted.merge(status, 1, Integer::sum);
                }
            }
            for (JsonNode root : resource.get("requiredByRoots")) {
                assertTrue(registry.get("roots").toString().contains(root.asText()), key);
            }
        }
        for (String status : EDGE_KEYS) {
            assertEquals(counted.getOrDefault(status, 0), registry.get("summary").get("edges").get(status).asInt(), status);
        }
        assertEquals(EDGE_KEYS, namesOf(registry.get("summary").get("edges")));
        assertEquals(keys.size(), registry.get("summary").get("resources").asInt());
        assertEquals(registry.get("warnings").size(), registry.get("summary").get("warnings").asInt());
        JsonNode verification = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.VERIFICATION_ENTRY));
        assertEquals(names.size() - 1, verification.get("entries").size());
        for (JsonNode checksum : verification.get("entries")) {
            String name = checksum.get("name").asText();
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entries.get(name))), checksum.get("sha256").asText(), name);
        }
    }

    private Download exportFileContractRoot() throws Exception {
        putModel("shared-model", "{\"type\": \"chat\", \"endpoint\": \"http://platform-blob.invalid/v1/chat/completions\"}");
        dial.getStorage().store("platform/applications/contract-root", "application/json", null, Map.of(),
                ("{\"application_type_schema_id\": \"" + FILE_SCHEMA_ID + "\", \"interceptors\": [\"file-interceptor\"],"
                        + " \"dependencies\": [\"file-model\", \"shared-model\", \"file-app\", \"file-toolset\"]}").getBytes(StandardCharsets.UTF_8));
        return export("contract-root");
    }

    @Test
    @DialConfigLocation("dial-config/export-file-contract.json")
    void testFileOriginResourcesAgreeWithTheArchiveAndShadowingFileEntityIsOneResource() throws Exception {
        Download download = exportFileContractRoot();

        assertEquals(200, download.status(), download.text());
        Map<String, byte[]> entries = entries(download);
        JsonNode registry = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY));
        JsonNode resources = registry.get("resources");
        String schemaKey = "application-type-schema:file:" + ExportResourceIdentity.schemaDigest(FILE_SCHEMA_ID);
        assertEquals(Set.of("application:platform:contract-root", "application-type-schema:file:" + ExportResourceIdentity.schemaDigest(FILE_SCHEMA_ID),
                "interceptor:file:file-interceptor", "model:file:file-model", "model:file:shared-model", "application:file:file-app",
                "toolset:file:file-toolset"), Set.copyOf(namesOf(resources)));

        // every file-origin resource: origin, key, entry name and no bucket location
        for (String key : namesOf(resources)) {
            if (!key.contains(":file:")) {
                continue;
            }
            JsonNode resource = resources.get(key);
            assertEquals("file", resource.get("origin").asText(), key);
            assertFalse(resource.has("bucketLocation"), key);
            assertTrue(resource.get("entryName").asText().startsWith(".dial-export/config/" + resource.get("type").asText() + "/"), key);
            assertTrue(entries.containsKey(resource.get("entryName").asText()), key);
        }
        // the dependencies of the root name the file-origin resources, never the platform blob
        Map<String, String> keyByPointer = new LinkedHashMap<>();
        for (JsonNode dependency : resources.get("application:platform:contract-root").get("dependencies")) {
            keyByPointer.put(dependency.get("pointer").asText(), dependency.get("key").asText());
        }
        assertEquals(schemaKey, keyByPointer.get("/application_type_schema_id"));
        assertEquals("interceptor:file:file-interceptor", keyByPointer.get("/interceptors/0"));
        assertEquals("model:file:file-model", keyByPointer.get("/dependencies/0"));
        assertEquals("model:file:shared-model", keyByPointer.get("/dependencies/1"));
        assertEquals("application:file:file-app", keyByPointer.get("/dependencies/2"));
        assertEquals("toolset:file:file-toolset", keyByPointer.get("/dependencies/3"));
        assertEquals("model:file:file-model", resources.get("application:file:file-app").get("dependencies").get(0).get("key").asText());

        // the shadowing file entity is one resource: the platform blob is in neither the registry nor the archive
        assertFalse(entries.containsKey("platform/models/shared-model"));
        assertTrue(text(entries.get(".dial-export/config/model/shared-model.json")).contains("file-shared.invalid"));
        entries.forEach((name, content) -> assertFalse(text(content).contains("platform-blob.invalid"), name));
        assertTrue(valuesOf(registry.get("warnings")).stream().anyMatch(warning -> warning.startsWith("A file entity shadows a platform entity")));

        // the secrets of the file entities are in no entry, the registry included
        entries.forEach((name, content) -> assertFalse(text(content).contains("FILE-MODEL-KEY") || text(content).contains("FILE-TOOLSET-SECRET")
                || text(content).contains("FILE-VERIFIER-SECRET"), name));

        // an Application that only the file configuration defines is not a root of the platform
        assertEquals(404, export("file-app").status());

        assertRegistryAgreesWithArchive(entries, registry);
    }
}
