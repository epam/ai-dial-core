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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dependency discovery through the real server, from a stored Application to the registry: an Application whose application type
 * schema carries {@code dial:resource} markers refers to resources by its fixed fields (the schema ids and {@code dependencies}),
 * and those are found, resolved against the real configuration and storage, and reported. The properties of the Application are
 * scanned for the markers of its schema as {@code catalog_properties} are: the values that the markers hold in
 * {@code application_properties} are references like any other, and the body is exported as stored.
 *
 * <p>The unit tests of {@code DeploymentEdgeDiscoveryTest} and {@code ExportClosureWalkerTest} cover each path with mocks;
 * this class covers them together once, under the production redactor. What the redactor does with every other field is pinned by
 * {@code ExportBodyRedactorApiTest}.
 */
public class ExportSchemaMarkerDiscoveryApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
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
     * Uses the schema, a catalog schema, a dependency that refers back to it, a dependency that names nothing, and five marked
     * properties of every kind. The values that name nothing, the link and its credentials must reach neither the registry nor a log.
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

    private record Reply(int status, String contentType, byte[] body) {
    }

    @BeforeEach
    void storeTheScenario() {
        dial.getProxy().setExportBodyRedactor(new ExportBodyRedactor());
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
        dial.getStorage().store("platform/applications/" + name, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    private Reply export(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + PATH))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", "admin")
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

    /** The resources of the registry by {@code type/path}. */
    private static Map<String, JsonNode> resourcesOf(JsonNode registry) {
        Map<String, JsonNode> resources = new LinkedHashMap<>();
        for (JsonNode resource : registry.get("resources")) {
            resources.put(resource.get("type").asText() + "/" + resource.get("path").asText(), resource);
        }
        return resources;
    }

    /** The references that lead to a resource, as {@code source-path pointer}. */
    private static List<String> referencePointers(JsonNode registry, JsonNode resource) {
        String key = registry.get("resources").properties().stream().filter(entry -> entry.getValue() == resource)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        List<String> pointers = new ArrayList<>();
        ExportRegistryJson.referencesTo(registry, key).forEach(reference -> pointers.add(
                ExportRegistryJson.resource(registry, reference.get("source").asText()).get("path").asText() + " " + reference.get("pointer").asText()));
        return pointers;
    }

    /** The unresolved edges of the registry by pointer. */
    private static Map<String, JsonNode> edgesOf(JsonNode registry) {
        Map<String, JsonNode> edges = new LinkedHashMap<>();
        ExportRegistryJson.unresolved(registry).forEach(edge -> edges.put(edge.get("pointer").asText(), edge));
        return edges;
    }

    /** Collects every log event of the process while a test runs; the server runs in the same JVM. */
    private static final class AllLogs extends ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> {
        private final java.util.concurrent.ConcurrentLinkedQueue<String> lines = new java.util.concurrent.ConcurrentLinkedQueue<>();

        @Override
        protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
            StringBuilder line = new StringBuilder(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                line.append(' ').append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            lines.add(line.toString());
        }

        String text() {
            return String.join("\n", lines);
        }
    }

    @Test
    void testEverythingThatTheApplicationRefersToIsFoundResolvedAndReported() throws Exception {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        AllLogs logs = new AllLogs();
        logs.setContext(root.getLoggerContext());
        logs.start();
        root.addAppender(logs);
        Reply reply;
        try {
            reply = export("{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/main-app\"}]}");
        } finally {
            root.detachAppender(logs);
            logs.stop();
        }

        assertEquals(200, reply.status());
        assertEquals("application/zip", reply.contentType());
        Map<String, byte[]> entries = entriesOf(reply);
        JsonNode registry = registryOf(entries);

        // the closure: the root, its dependency, the schema, the catalog schema, and the ToolSet and the Model that the marked
        // properties name
        Map<String, JsonNode> resources = resourcesOf(registry);
        assertEquals(Set.of("application/main-app", "application/partner", "application-type-schema/quick-schema",
                "catalog-schema/quick-catalog", "toolset/tools", "model/model-m"), resources.keySet());
        assertEquals("application/main-app", resources.keySet().iterator().next(), "the selected root comes first");
        JsonNode exported = ProxyUtil.MAPPER.readTree(entries.get(resources.get("application/main-app").get("entryName").asText()));
        assertEquals("toolsets/platform/tools", exported.get("application_properties").get("tool").asText(), "the properties are exported as stored");
        assertEquals(SCHEMA_ID, exported.get("application_type_schema_id").asText());
        assertEquals("selected", resources.get("application/main-app").get("role").asText());
        assertEquals(1, registry.get("roots").size());
        assertEquals("dependency", resources.get("application/partner").get("role").asText());

        // each included resource is the target of the references that led to it, by JSON pointer into the body that holds them
        assertEquals(List.of("main-app /dependencies/0"), referencePointers(registry, resources.get("application/partner")));
        assertEquals(List.of("main-app /application_type_schema_id"), referencePointers(registry, resources.get("application-type-schema/quick-schema")));
        assertEquals(List.of("main-app /catalog_schema_id"), referencePointers(registry, resources.get("catalog-schema/quick-catalog")));
        assertEquals(List.of("main-app /application_properties/tool"), referencePointers(registry, resources.get("toolset/tools")));
        assertEquals(List.of("main-app /application_properties/model"), referencePointers(registry, resources.get("model/model-m")));

        // the schema is identified by its $id
        assertEquals(SCHEMA_ID, resources.get("application-type-schema/quick-schema").get("schemaId").asText());
        assertEquals(CATALOG_ID, resources.get("catalog-schema/quick-catalog").get("schemaId").asText());

        // the cycle: the partner refers back to the root, which stays one selected node and is the target of that reference
        assertEquals(List.of("partner /dependencies/0"), referencePointers(registry, resources.get("application/main-app")));

        // every included resource is an entry of the archive with its sidecar, and nothing else is except the registry, the registry and
        // the verification
        assertEquals(2 + 2 * resources.size() + 1, entries.size());
        resources.values().forEach(resource -> assertTrue(entries.containsKey(resource.get("entryName").asText())));

        // the references that do not end in an included resource are listed by their pointers: a name that nothing holds is
        // unresolved, a link is external, and a conversation is not a supported type
        Map<String, JsonNode> edges = edgesOf(registry);
        assertEquals(Set.of("/dependencies/1", "/application_properties/missing_model", "/application_properties/link", "/application_properties/prompt"),
                edges.keySet());
        assertEquals("unresolved", edges.get("/dependencies/1").get("status").asText());
        assertEquals("unresolved", edges.get("/application_properties/missing_model").get("status").asText());
        assertEquals("external", edges.get("/application_properties/link").get("status").asText());
        assertEquals("unsupported", edges.get("/application_properties/prompt").get("status").asText());
        String text = new String(entries.get(ExportArchive.REGISTRY_ENTRY), StandardCharsets.UTF_8);
        // no raw reference value is in the registry or in a log; the identity of a URL-form target is written by design, so only the
        // values that are not identities are checked
        for (String value : List.of("SECRET-PASS", "SECRET-TOKEN", "SECRET-NAME", "SECRET-DEP", "user:", "example.com")) {
            assertTrue(!text.contains(value), "the registry holds " + value);
            assertTrue(!logs.text().contains(value), "a log holds " + value);
        }

        // the discovery is complete, and the summary and the warnings follow from the edges
        assertTrue(registry.get("discovery").get("complete").asBoolean());
        assertEquals(0, registry.get("discovery").get("issues").size());
        JsonNode counts = registry.get("summary").get("edges");
        assertEquals(List.of("unsupported", "external", "unresolved"), List.copyOf(namesOf(counts)));
        assertEquals(2, counts.get("unresolved").asInt());
        assertEquals(1, counts.get("external").asInt());
        assertEquals(1, counts.get("unsupported").asInt());
        assertEquals(6, registry.get("summary").get("resources").asInt());
        List<String> warnings = new ArrayList<>();
        registry.get("warnings").forEach(warning -> warnings.add(warning.asText()));
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(warning -> warning.startsWith("A Model or Interceptor is exported as its definition only")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.startsWith("Only fixed fields and marked fields were examined")));
    }

    private static List<String> namesOf(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
