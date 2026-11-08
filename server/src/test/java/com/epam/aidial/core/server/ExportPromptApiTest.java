package com.epam.aidial.core.server;

import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.ExportBodyRedactor;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The export of the Prompts of an Application through the real server and the real {@link ExportBodyRedactor}: a public Prompt
 * named by a stored {@code dependencies} entry is exported exactly as stored and is never examined for references.
 */
public class ExportPromptApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final String SECRET = "s3cr3t-prompt-value";
    /** Pretty-printed, with an escape, an unknown property and every kind of content that another type would lose or change. */
    private static final String PROMPT = "{\n  \"id\" : \"p\",\n  \"folderId\" : \"\",\n  \"name\" : \"P \\u00e9\",\n  \"content\" : \"Use key=value and ENC[abc] and "
            + "https://user:" + SECRET + "@a.invalid/x?token=" + SECRET + "#f as they are\",\n  \"default_headers\" : { \"X-Key\" : \"" + SECRET + "\" },\n"
            + "  \"application_properties\" : { \"a\" : 1.50 },\n  \"extra\" : { \"nested\" : [ 1, 2 ] }\n}";

    private record Reply(int status, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    void setRedactor() {
        dial.getProxy().setExportBodyRedactor(new ExportBodyRedactor());
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

    private static ResourceDescriptor prompt(String path) {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.PROMPT, "public", "public/", path);
    }

    private void putPrompt(String path, String body) {
        dial.getProxy().getResourceService().putResource(prompt(path), body, EtagHeader.ANY, null, false);
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
    void testPublicPromptIsExportedExactlyAsStoredAndNotExaminedForReferences() throws Exception {
        putPrompt("p", PROMPT);
        storeRoot("[\"prompts/public/p\"]");

        Reply reply = post(request(""));

        assertEquals(200, reply.status(), reply.text());
        Map<String, byte[]> entries = entries(reply);
        assertEquals(PROMPT, new String(entries.get("public/prompts/p"), StandardCharsets.UTF_8), "the stored bytes: nothing removed, emptied or normalized");
        assertTrue(entries.containsKey(".dial-system-export/metadata/public/prompts/p"), "a sidecar for the Prompt");

        JsonNode registry = registryOf(entries);
        JsonNode dependency = dependencyAt(registry, "/dependencies/0");
        assertEquals("included", dependency.get("status").asText());
        assertEquals(2, registry.get("resources").size(), "the Prompt is a resource of the registry");
        assertTrue(StreamSupport.stream(registry.get("warnings").spliterator(), false)
                .anyMatch(warning -> warning.asText().contains("body of an exported Prompt is exported as stored")), registry.get("warnings").toString());
    }
}
