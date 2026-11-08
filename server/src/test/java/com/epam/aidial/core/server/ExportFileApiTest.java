package com.epam.aidial.core.server;

import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The export of the Files of an Application through the real server: a public File named by a fixed reference field is copied as
 * it is stored, listed in {@code files} with a sidecar, verified by its length, etag and {@code sha256}, and never parsed,
 * redacted or examined for references. A missing File, Skill or Prompt is an unresolved edge that does not fail the export.
 */
public class ExportFileApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final String ROOT_URL = "applications/platform/exported";

    private record Reply(int status, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void setRedactor() {
        // the redactor must never see a File: it fails on anything that is not a JSON object, and a File is not one
        dial.getProxy().setExportBodyRedactor((identity, body) -> {
            JsonNode node;
            try {
                node = ProxyUtil.MAPPER.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("not JSON", e);
            }
            return node.toString().getBytes(StandardCharsets.UTF_8);
        });
    }

    private Reply post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + PATH))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", "admin")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), response.body());
    }

    private static Map<String, byte[]> entries(Reply reply) throws Exception {
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

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void putFile(String path, byte[] bytes, String contentType) {
        ResourceDescriptor file = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", path);
        dial.getProxy().getResourceService().putFile(file, bytes, EtagHeader.ANY, contentType, null);
    }

    private void storeApplication(String name, String json) {
        dial.getStorage().store("platform/applications/" + name, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    private void storeRoot(String iconUrl, String dependencies) {
        storeApplication("exported", "{\"endpoint\":\"http://application.invalid/chat\","
                + (iconUrl == null ? "" : "\"icon_url\":\"" + iconUrl + "\",") + "\"dependencies\":" + dependencies + "}");
    }

    private static String request(String extra) {
        return "{\"roots\":[{\"type\":\"application\",\"url\":\"" + ROOT_URL + "\"}]" + extra + "}";
    }

    private static JsonNode dependencyAt(JsonNode registry, String pointer) {
        for (JsonNode dependency : registry.get("resources").get("application:platform:exported").get("dependencies")) {
            if (pointer.equals(dependency.get("pointer").asText())) {
                return dependency;
            }
        }
        throw new AssertionError("no dependency at " + pointer);
    }

    private static JsonNode fileOf(JsonNode registry, String key) {
        for (JsonNode file : registry.get("files")) {
            if (key.equals(file.get("key").asText())) {
                return file;
            }
        }
        throw new AssertionError("no file " + key);
    }

    private static byte[] binary(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    @Test
    void testFilesAreCopiedAsStoredAndListedWithTheirSidecarAndVerification() throws Exception {
        byte[] icon = binary(5000);
        // looks like JSON, holds an ENC[ value and names an Application: none of it may be parsed, redacted or followed
        byte[] data = "{\"key\":\"ENC[abc]\",\"dependencies\":[\"applications/platform/other\"]}".getBytes(StandardCharsets.UTF_8);
        putFile("icons/a.png", icon, "image/png");
        putFile("data/x.json", data, "application/json");
        storeApplication("other", "{\"endpoint\":\"http://other.invalid/chat\"}");
        storeRoot("files/public/icons/a.png", "[\"files/public/data/x.json\"]");

        Reply reply = post(request(""));

        assertEquals(200, reply.status(), reply.text());
        Map<String, byte[]> entries = entries(reply);
        assertArrayEquals(icon, entries.get("public/files/icons/a.png"), "the bytes are exactly the stored ones");
        assertArrayEquals(data, entries.get("public/files/data/x.json"), "a File that looks like JSON is not redacted");
        JsonNode sidecar = ProxyUtil.MAPPER.readTree(entries.get(".dial-system-export/metadata/public/files/icons/a.png"));
        assertEquals("image/png", sidecar.get("contentType").asText());

        JsonNode registry = registryOf(entries);
        assertEquals(2, registry.get("files").size());
        assertEquals("public/files/icons/a.png", fileOf(registry, "files:public:icons/a.png").get("entryName").asText());
        assertEquals("public/files/data/x.json", fileOf(registry, "files:public:data/x.json").get("entryName").asText());
        assertEquals(2, registry.get("summary").get("files").asInt());
        assertEquals(1, registry.get("resources").size(), "a File is listed in files and is not a resource");
        assertFalse(registry.get("resources").has("application:platform:other"), "the content of a File is not examined for references");
        JsonNode iconEdge = dependencyAt(registry, "/icon_url");
        assertEquals("included", iconEdge.get("status").asText());
        assertEquals("files:public:icons/a.png", iconEdge.get("key").asText());
        assertEquals("files", iconEdge.get("kind").asText());

        JsonNode verification = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.VERIFICATION_ENTRY));
        for (JsonNode listed : verification.get("entries")) {
            if (listed.get("name").asText().equals("public/files/icons/a.png")) {
                assertEquals(sha256(icon), listed.get("sha256").asText());
                assertEquals(icon.length, listed.get("length").asLong());
                assertTrue(listed.has("etag"));
            }
        }
        assertFalse(verification.has("changedDuringExport"));
        assertTrue(StreamSupport.stream(registry.get("warnings").spliterator(), false)
                .anyMatch(warning -> warning.asText().contains("copied as stored")));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the filesystem blob provider keeps the metadata read of a file that is larger than the cache open on Windows "
            + "(ResourceService.getResourceMetadata, shown by a test that only wrote the file and read its metadata), so the teardown cannot delete it; the assertions pass")
    void testFileLargerThanTheCacheIsStreamedByteForByte() throws Exception {
        byte[] large = binary(3 * 1024 * 1024 + 17);
        putFile("big/large.bin", large, "application/octet-stream");
        storeRoot("files/public/big/large.bin", "[]");

        Reply reply = post(request(""));

        assertEquals(200, reply.status());
        Map<String, byte[]> entries = entries(reply);
        assertEquals(sha256(large), sha256(entries.get("public/files/big/large.bin")));
        assertEquals(large.length, entries.get("public/files/big/large.bin").length);
    }

    @Test
    void testMissingFileSkillAndPromptAreUnresolvedEdgesAndTheExportStillSucceeds() throws Exception {
        storeRoot("files/public/icons/gone.png", "[\"skills/public/gone\",\"prompts/public/absent\"]");

        Reply reply = post(request(""));

        assertEquals(200, reply.status(), reply.text());
        JsonNode registry = registryOf(entries(reply));
        assertEquals("unresolved", dependencyAt(registry, "/icon_url").get("status").asText());
        assertEquals("unresolved", dependencyAt(registry, "/dependencies/0").get("status").asText());
        assertEquals("unresolved", dependencyAt(registry, "/dependencies/1").get("status").asText());
        assertEquals(3, ExportRegistryJson.unresolved(registry).size());
    }

    @Test
    void testTwoApplicationsNamingTheSameFileShareOneEntry() throws Exception {
        putFile("icons/a.png", binary(64), "image/png");
        storeRoot("files/public/icons/a.png", "[]");
        storeApplication("second", "{\"endpoint\":\"http://second.invalid/chat\",\"icon_url\":\"files/public/icons/a.png\"}");

        Reply reply = post("{\"roots\":[{\"type\":\"application\",\"url\":\"" + ROOT_URL + "\"},"
                + "{\"type\":\"application\",\"url\":\"applications/platform/second\"}]}");

        assertEquals(200, reply.status(), reply.text());
        JsonNode registry = registryOf(entries(reply));
        assertEquals(1, registry.get("files").size());
        assertEquals("files:public:icons/a.png",
                registry.get("resources").get("application:platform:second").get("dependencies").get(0).get("key").asText());
    }

    @Test
    void testCatalogMarkerNamesFileThatIsExportedWithItsCatalogPointer() throws Exception {
        String schema = "{\"$schema\":\"https://dial.epam.com/catalog_schemas/schema#\",\"$id\":\"https://dial.example/catalog/export\","
                + "\"dial:catalogEntityType\":\"application\",\"dial:catalogDisplayName\":\"Application\",\"dial:defaultLocale\":\"en\","
                + "\"type\":\"object\",\"properties\":{\"badge\":{\"type\":\"string\",\"format\":\"dial-file-encoded\",\"dial:file\":true}}}";
        verify(send(HttpMethod.PUT, "/v1/catalog_schemas/platform/export-catalog", null, schema, "authorization", "admin"), 200);
        putFile("badges/b.png", binary(200), "image/png");
        storeApplication("exported", "{\"endpoint\":\"http://application.invalid/chat\",\"catalog_schema_id\":\"https://dial.example/catalog/export\","
                + "\"catalog_properties\":{\"badge\":\"files/public/badges/b.png\"}}");

        Reply reply = post(request(""));

        assertEquals(200, reply.status(), reply.text());
        Map<String, byte[]> entries = entries(reply);
        assertTrue(entries.containsKey("public/files/badges/b.png"));
        JsonNode registry = registryOf(entries);
        assertEquals("included", dependencyAt(registry, "/catalog_properties/badge").get("status").asText());
        assertEquals("files:public:badges/b.png", dependencyAt(registry, "/catalog_properties/badge").get("key").asText());
    }
}
