package com.epam.aidial.core.server;

import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.resource.SkillHandler;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * The export of the Skills of an Application through the real server: a public Skill named by a stored {@code dependencies} entry
 * is exported as the files of its current version, in a logical folder below the path of the Skill, without the marker and the
 * version folder, copied as stored and never examined for references.
 */
public class ExportSkillApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final String MANIFEST = "---\nname: My Skill\ndescription: Does something useful\nversion: 1.0.0\n---\n# My Skill\n\nBody.\n";

    private record Reply(int status, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void setRedactor() {
        // the redactor fails on anything that is not a JSON object, so it would fail on a file of a Skill if it ever saw one
        dial.getProxy().setExportBodyRedactor((identity, body) -> {
            try {
                return ProxyUtil.MAPPER.readTree(body).toString().getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("not JSON", e);
            }
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

    private static ResourceDescriptor skill(String path) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.SKILL, "public", "public/", path);
    }

    private void putSkill(String path, Map<String, String> files) {
        Map<String, Buffer> uploads = new LinkedHashMap<>();
        files.forEach((name, content) -> uploads.put(name, Buffer.buffer(content)));
        dial.getProxy().getComplexResourceService().put(skill(path), new SkillHandler(), uploads, EtagHeader.ANY, "author");
    }

    private void storeRoot(String dependencies) {
        dial.getStorage().store("platform/applications/exported", "application/json", null, Map.of(), ("{\"endpoint\":\"http://application.invalid/chat\","
                + "\"dependencies\":" + dependencies + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static String request(String extra) {
        return "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/exported\"}]" + extra + "}";
    }

    private static JsonNode dependencyAt(JsonNode registry, String pointer) {
        for (JsonNode dependency : registry.get("resources").get("application:platform:exported").get("dependencies")) {
            if (pointer.equals(dependency.get("pointer").asText())) {
                return dependency;
            }
        }
        throw new AssertionError("no dependency at " + pointer);
    }

    @Test
    void testSkillIsExportedAsTheFilesOfItsCurrentVersionInLogicalFolderWithTheSkillAsOwner() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", MANIFEST);
        files.put("scripts/run.py", "print('hello')\n");
        // looks like JSON and holds an ENC[ value: it must be copied as it is, neither parsed nor redacted
        files.put("data/config.json", "{\"key\":\"ENC[abc]\"}");
        // names resources, but the content of a Skill is never examined for references
        files.put("notes.md", "see applications/platform/other and toolsets/public/t\n");
        putSkill("s", files);
        dial.getStorage().store("platform/applications/other", "application/json", null, Map.of(),
                "{\"endpoint\":\"http://other.invalid/chat\"}".getBytes(StandardCharsets.UTF_8));
        storeRoot("[\"skills/public/s\"]");

        Reply reply = post(request(""));

        assertEquals(200, reply.status(), reply.text());
        Map<String, byte[]> entries = entries(reply);
        for (Map.Entry<String, String> file : files.entrySet()) {
            assertArrayEquals(file.getValue().getBytes(StandardCharsets.UTF_8), entries.get("public/skills/s/" + file.getKey()), file.getKey());
            assertTrue(entries.containsKey(".dial-system-export/metadata/public/skills/s/" + file.getKey()), "a sidecar for " + file.getKey());
        }
        assertTrue(entries.keySet().stream().noneMatch(name -> name.contains(".dial-resource") || name.contains("/v/")),
                "neither the marker nor the version folder is exported: " + entries.keySet());

        JsonNode registry = registryOf(entries);
        assertEquals(files.size(), registry.get("files").size());
        assertEquals(files.size(), registry.get("summary").get("files").asInt());
        assertEquals(1, registry.get("resources").size(), "the Skill is not a resource of the registry");
        assertFalse(registry.get("resources").has("application:platform:other"), "the content of a Skill is not examined for references");
        JsonNode dependency = dependencyAt(registry, "/dependencies/0");
        assertEquals("included", dependency.get("status").asText());
        assertEquals("skills:public:s", dependency.get("key").asText());
        assertEquals("skills", dependency.get("kind").asText());
        for (JsonNode file : registry.get("files")) {
            assertEquals("skills:public:s", file.get("owner").asText());
            assertTrue(file.get("entryName").asText().startsWith("public/skills/s/"));
        }
        assertFalse(ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.VERIFICATION_ENTRY)).has("changedDuringExport"));
        assertTrue(StreamSupport.stream(registry.get("warnings").spliterator(), false)
                .anyMatch(warning -> warning.asText().contains("copied as stored")));
    }
}
