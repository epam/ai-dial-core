package com.epam.aidial.core.server;

import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the export does with a dependency written in URL form at the level of the export. A URL
 * names a type, a bucket and a name, so it is read as the stored blob, whatever the merged configuration holds under the short name.
 * This class pins the outcomes that matter
 * for the export: a blob that a short name does not reach is exported under its own identity, and a stored body that cannot be
 * bound to an Application is exported and reported as incomplete. The archive is written under a pass-through redactor.
 */
public class ExportUrlFormDependencyApiTest extends ResourceBaseTest {

    private static final BodyRedactor PASS_THROUGH = (identity, body) -> body.getBytes(StandardCharsets.UTF_8);
    private static final String ROOT = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/root\"}]}";

    @BeforeEach
    void setUp() {
        dial.getProxy().setExportBodyRedactor(PASS_THROUGH);
    }

    private void store(String blobPath, String json) {
        dial.getStorage().store(blobPath, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    private void storeRoot(String dependenciesJson) {
        store("platform/applications/root", "{\"endpoint\":\"http://root.invalid\",\"dependencies\":" + dependenciesJson + "}");
    }

    private HttpResponse<byte[]> export() throws Exception {
        return export(ROOT);
    }

    private HttpResponse<byte[]> export(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/v1/admin/export"))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", "admin")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String rootOf(String name) {
        return "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/" + name + "\"}]}";
    }

    private static JsonNode registryOf(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY));
    }

    private static List<String> resourceOrder(JsonNode registry) {
        List<String> order = new ArrayList<>();
        for (JsonNode resource : registry.get("resources")) {
            order.add(resource.get("type").asText() + "/" + resource.get("path").asText());
        }
        return order;
    }

    private static JsonNode resource(JsonNode registry, String type, String path) {
        for (JsonNode resource : registry.get("resources")) {
            if (type.equals(resource.get("type").asText()) && path.equals(resource.get("path").asText())) {
                return resource;
            }
        }
        throw new AssertionError("not in the registry: " + type + "/" + path);
    }

    private static Set<String> entryNames(byte[] zip) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    // ---- an Application whose name a Model holds ---------------------------------------------------------------------

    private void givenModelAndApplicationNamedShared() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/shared", null,
                "{\"type\":\"chat\",\"endpoint\":\"http://localhost:7001/openai/deployments/m/chat/completions\"}",
                "authorization", "admin", "If-None-Match", "*"), 200);
        store("platform/applications/shared", "{\"endpoint\":\"http://shared-app.invalid\"}");
        storeRoot("[\"applications/platform/shared\",\"shared\"]");
    }

    @Test
    void testUrlFormNamesTheApplicationAndTheShortNameTheModelSoBothAreExportedUnderTheirOwnIdentity() throws Exception {
        givenModelAndApplicationNamedShared();

        HttpResponse<byte[]> response = export();

        assertEquals(200, response.statusCode());
        JsonNode registry = registryOf(response.body());
        assertEquals(Set.of("application/root", "application/shared", "model/shared"), new HashSet<>(resourceOrder(registry)));
        JsonNode application = resource(registry, "application", "shared");
        JsonNode model = resource(registry, "model", "shared");
        assertEquals("platform", application.get("origin").asText());
        assertEquals("platform", model.get("origin").asText());
        assertEquals("/dependencies/0", ExportRegistryJson.referencesTo(registry, "application:platform:shared").get(0).get("pointer").asText(),
                "the URL reached the application");
        assertEquals("/dependencies/1", ExportRegistryJson.referencesTo(registry, "model:platform:shared").get(0).get("pointer").asText(),
                "the short name reached the model");
        assertEquals(0, ExportRegistryJson.unresolved(registry).size(), "nothing is unresolved");
        assertEquals(resourceOrder(registry), resourceOrder(registryOf(export().body())), "the same order on every run");
        Set<String> entries = entryNames(response.body());
        assertTrue(entries.contains(application.get("entryName").asText()) && entries.contains(model.get("entryName").asText()));
        assertNotEquals(application.get("entryName").asText(), model.get("entryName").asText());
    }

    // ---- dependencies of dependencies: ToolSet, Model and Interceptor are followed ---------------------------------------------

    private void put(String path, String body) {
        verify(send(HttpMethod.PUT, path, null, body, "authorization", "admin", "If-None-Match", "*"), 200);
    }

    @Test
    void testToolSetAndModelAreFollowedToTheirInterceptorsAndIcon() throws Exception {
        byte[] icon = {1, 2, 3, 4};
        ResourceDescriptor file = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", "icons/t.png");
        dial.getProxy().getResourceService().putFile(file, icon, EtagHeader.ANY, "image/png", null);
        put("/v1/interceptors/platform/tool-int", "{\"endpoint\":\"http://localhost:4088/api/v1/interceptor/handle\"}");
        put("/v1/interceptors/platform/model-int", "{\"endpoint\":\"http://localhost:4088/api/v1/interceptor/handle\"}");
        put("/v1/models/platform/chain-model", "{\"type\":\"chat\",\"endpoint\":\"http://localhost:7001/chat\",\"interceptors\":[\"model-int\"]}");
        try (TestWebServer ignore = new TestWebServer(9876)) {
            put("/v1/toolsets/platform/chain-tools", "{\"endpoint\":\"http://localhost:9876\",\"transport\":\"HTTP\","
                    + "\"interceptors\":[\"tool-int\"],\"icon_url\":\"files/public/icons/t.png\"}");
        }
        storeRoot("[\"toolsets/platform/chain-tools\",\"chain-model\"]");

        HttpResponse<byte[]> response = export();

        assertEquals(200, response.statusCode());
        JsonNode registry = registryOf(response.body());
        assertEquals(Set.of("application/root", "toolset/chain-tools", "model/chain-model", "interceptor/tool-int", "interceptor/model-int"),
                new HashSet<>(resourceOrder(registry)));
        assertEquals("/interceptors/0", ExportRegistryJson.referencesTo(registry, "interceptor:platform:tool-int").get(0).get("pointer").asText());
        assertEquals("toolset:platform:chain-tools", ExportRegistryJson.referencesTo(registry, "interceptor:platform:tool-int").get(0).get("source").asText());
        assertEquals("model:platform:chain-model", ExportRegistryJson.referencesTo(registry, "interceptor:platform:model-int").get(0).get("source").asText());
        JsonNode iconReference = ExportRegistryJson.referencesTo(registry, "files:public:icons/t.png").get(0);
        assertEquals("/icon_url", iconReference.get("pointer").asText());
        assertEquals("toolset:platform:chain-tools", iconReference.get("source").asText());
        assertEquals(0, ExportRegistryJson.unresolved(registry).size());

        Set<String> entries = entryNames(response.body());
        for (String type : List.of("toolset/chain-tools", "model/chain-model", "interceptor/tool-int", "interceptor/model-int")) {
            String[] parts = type.split("/");
            assertTrue(entries.contains(resource(registry, parts[0], parts[1]).get("entryName").asText()), type);
        }
        assertTrue(entries.contains("public/files/icons/t.png"), entries.toString());
    }

    @Test
    void testUrlFormToValidJsonThatCannotBeBoundToAnApplicationIsExportedAndReportedAsIncomplete() throws Exception {
        store("platform/applications/mistyped", "{\"endpoint\":{\"nested\":\"SECRET-IN-BODY\"}}");
        storeRoot("[\"applications/platform/mistyped\"]");

        HttpResponse<byte[]> response = export(rootOf("root"));

        assertEquals(200, response.statusCode(), "a dependency that cannot be bound does not fail the export");
        JsonNode registry = registryOf(response.body());
        assertTrue(registry.get("resources").has("application:platform:mistyped"), "the dependency is exported");
        assertFalse(registry.get("discovery").get("complete").asBoolean(), "its references could not be read");
        JsonNode issue = registry.get("discovery").get("issues").get(0);
        assertEquals("application:platform:mistyped", issue.get("resource").asText());
        assertEquals("instance-invalid", issue.get("kind").asText());
        assertEquals("", issue.get("pointer").asText());
        assertFalse(registry.toString().contains("SECRET-IN-BODY"), "the registry holds no value of the body");
    }
}
