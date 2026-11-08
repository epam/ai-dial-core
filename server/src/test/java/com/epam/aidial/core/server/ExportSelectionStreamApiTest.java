package com.epam.aidial.core.server;

import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The response of the selective Application export on {@code POST /v1/admin/export}, end to end: the closure, the redaction,
 * the archive and the stream, through the real server. The tests swap the production redactor for a {@link BodyRedactor} that
 * removes the {@code description} field, so a body is shown to be redacted before it is exported; it is a stand-in, not a policy.
 */
public class ExportSelectionStreamApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final String SECRET = "SECRET-FIELD-VALUE-MARKER";
    private static final String ROOT_REQUEST = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/exported\"}]}";

    private static final BodyRedactor REMOVES_THE_DESCRIPTION = (identity, body) -> {
        try {
            ObjectNode node = (ObjectNode) ProxyUtil.MAPPER.readTree(body);
            node.remove("description");
            return ProxyUtil.MAPPER.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("not JSON", e);
        }
    };

    private record Reply(int status, String contentType, String disposition, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void setRedactor() {
        dial.getProxy().setExportBodyRedactor(REMOVES_THE_DESCRIPTION);
    }

    private Reply post(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + PATH))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse("-"),
                response.headers().firstValue("Content-Disposition").orElse("-"), response.body());
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

    private void store(String name, String json) {
        dial.getStorage().store("platform/applications/" + name, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    private void storeRootWithDependency() {
        store("exported", "{\"endpoint\":\"http://application.invalid/chat\",\"description\":\"" + SECRET
                + "\",\"dependencies\":[\"applications/platform/dependency\"]}");
        store("dependency", "{\"endpoint\":\"http://dependency.invalid/chat\"}");
    }

    private static JsonNode registryOf(Map<String, byte[]> entries) throws Exception {
        return ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY));
    }

    @Test
    void testSidecarOfResourceWrittenThroughTheResourceServiceHoldsItsTimes() throws Exception {
        ResourceDescriptor written = ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, "platform", "platform/", "written");
        dial.getProxy().getResourceService().putResource(written, "{\"endpoint\":\"http://written.invalid/chat\"}", EtagHeader.ANY, null, false);

        Map<String, byte[]> writtenEntries = entries(post("admin", "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/written\"}]}"));

        JsonNode sidecar = ProxyUtil.MAPPER.readTree(writtenEntries.get(".dial-system-export/metadata/platform/applications/written"));
        assertEquals("application/json", sidecar.get("contentType").asText());
        assertFalse(sidecar.has("contentEncoding") || sidecar.has("userMetadata"));
        assertTrue(Instant.parse(sidecar.get("creationDate").asText()).isBefore(Instant.now().plusSeconds(60)));
        assertTrue(Instant.parse(sidecar.get("lastModified").asText()).isBefore(Instant.now().plusSeconds(60)));
    }

    @Test
    void testSelectedApplicationAndItsDependencyAreStreamedAsZipWithTheBodiesRedacted() throws Exception {
        storeRootWithDependency();

        Reply reply = post("admin", ROOT_REQUEST);

        assertEquals(200, reply.status());
        assertEquals("application/zip", reply.contentType());
        assertTrue(reply.disposition().startsWith("attachment; filename=\"dial-export-"), reply.disposition());
        Map<String, byte[]> entries = entries(reply);
        List<String> names = new ArrayList<>(entries.keySet());
        assertEquals(ExportArchive.MANIFEST_ENTRY, names.get(0), "the manifest is the first entry");
        assertEquals(ExportArchive.VERIFICATION_ENTRY, names.get(names.size() - 1), "the verification entry is the last");
        assertEquals(7, names.size());

        JsonNode registry = registryOf(entries);
        assertEquals("dial-export-dependency-registry", registry.get("kind").asText());
        assertEquals(1, registry.get("roots").size());
        assertEquals(2, registry.get("resources").size());
        JsonNode root = ExportRegistryJson.resources(registry).get(0);
        assertEquals("selected", root.get("role").asText());
        assertEquals("dependency", ExportRegistryJson.resources(registry).get(1).get("role").asText());

        assertFalse(reply.text().contains(SECRET), "the removed field is not in the response");
        for (JsonNode resource : registry.get("resources")) {
            assertTrue(entries.containsKey(resource.get("entryName").asText()));
        }
        assertFalse(new String(entries.get(root.get("entryName").asText()), StandardCharsets.UTF_8).contains("description"));
    }

    @Test
    void testRedactorFailureIsServerErrorBeforeTheArchiveAndEchoesNothing() throws Exception {
        storeRootWithDependency();
        dial.getProxy().setExportBodyRedactor((identity, body) -> {
            throw new IllegalStateException("cannot redact " + SECRET);
        });

        Reply reply = post("admin", ROOT_REQUEST);

        assertEquals(500, reply.status());
        assertNotEquals("application/zip", reply.contentType());
        assertFalse(reply.disposition().contains("dial-export"));
        assertFalse(reply.text().contains(SECRET), "the answer echoes the cause: " + reply.text());
    }
}
