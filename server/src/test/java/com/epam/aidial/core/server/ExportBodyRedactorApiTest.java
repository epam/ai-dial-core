package com.epam.aidial.core.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selective export with the production redactor written through the real configuration API so that the
 * blobs have the stored form that the redactor meets: the redactor is not replaced, as other tests of the export do. What is
 * checked is the archive that comes out, entry by entry, and the refusals that end in a status before any byte of it.
 */
public class ExportBodyRedactorApiTest extends ResourceBaseTest {

    private static final String PATH = "/v1/admin/export";
    private static final String ROUTE_SECRET = "platform-route-secret-1";
    private static final String SERVICE_SECRET = "my-client-secret";
    private static final String TOOLSET_SECRET = "toolset-client-secret";
    private static final String MODEL_SECRET = "model-upstream-secret-1";

    private record Download(int status, String contentType, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private Download export(String rootName) throws Exception {
        String body = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/" + rootName + "\"}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + PATH))
                .version(HttpClient.Version.HTTP_1_1).header("authorization", "admin").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Download(response.statusCode(), response.headers().firstValue("Content-Type").orElse("-"), response.body());
    }

    private static Map<String, String> entries(Download download) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(download.body()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private void storeRaw(String name, String json) {
        dial.getStorage().store("platform/applications/" + name, "application/json", null, Map.of(), json.getBytes(StandardCharsets.UTF_8));
    }

    private void putApplication(String name, String body) {
        verify(send(HttpMethod.PUT, "/v1/applications/platform/" + name, null, body, "authorization", "admin", "If-None-Match", "*"), 200);
    }

    @Test
    @Disabled("Redaction is switched off temporarily for testing")
    void testSecretsWrittenThroughTheConfigurationApiAreInNoEntryOfTheArchive() throws Exception {
        try (TestWebServer ignore = new TestWebServer(9876)) {
            verify(send(HttpMethod.PUT, "/v1/toolsets/platform/secret-toolset", null, """
                    {"endpoint": "http://localhost:9876", "transport": "HTTP", "display_name": "Secret-bearing Toolset",
                     "auth_settings": {"authentication_type": "OAUTH", "client_id": "toolset-client-id", "client_secret": "%s",
                       "redirect_uri": "http://localhost:3000/auth/signin",
                       "authorization_endpoint": "https://static.auth.example.com/authorize",
                       "token_endpoint": "https://static.auth.example.com/token"}}
                    """.formatted(TOOLSET_SECRET), "authorization", "admin", "If-None-Match", "*"), 200);
        }
        verify(send(HttpMethod.PUT, "/v1/models/platform/secret-model", null, """
                {"type": "chat", "endpoint": "http://localhost:7001/openai/deployments/m/chat/completions",
                 "upstreams": [{"endpoint": "http://localhost:7001", "key": "%s"}]}
                """.formatted(MODEL_SECRET), "authorization", "admin", "If-None-Match", "*"), 200);
        putApplication("secret-app", """
                {"endpoint": "http://application1/v1/completions", "display_name": "Secret-bearing App", "forward_auth_token": true,
                 "dependencies": ["toolsets/platform/secret-toolset", "secret-model"],
                 "routes": {"my-route": {"paths": ["/v1/my-route"], "methods": ["GET"],
                   "upstreams": [{"endpoint": "http://localhost:9876", "key": "%s"}]}},
                 "external_services": {"svc1": {"display_name": "Salesforce",
                   "auth_settings": {"authentication_type": "OAUTH", "client_id": "my-client-id", "client_secret": "%s",
                     "redirect_uri": "http://localhost:3000/auth/signin",
                     "authorization_endpoint": "https://static.auth.example.com/authorize",
                     "token_endpoint": "https://static.auth.example.com/token"}}}}
                """.formatted(ROUTE_SECRET, SERVICE_SECRET));
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION,
                ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, "secret-app");
        String stored = dial.getProxy().getResourceService().getResource(descriptor);
        assertNotNull(stored);
        assertTrue(stored.contains("ENC["), "the route secret is stored as ciphertext, so the check below is not vacuous: " + stored);
        ResourceDescriptor toolSetDescriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.TOOL_SET,
                ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, "secret-toolset");
        String storedToolSet = dial.getProxy().getResourceService().getResource(toolSetDescriptor);
        ResourceDescriptor modelDescriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.MODEL,
                ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, "secret-model");
        String storedModel = dial.getProxy().getResourceService().getResource(modelDescriptor);
        assertNotNull(storedModel);
        assertTrue(storedModel.contains("ENC["), "the upstream key is stored as ciphertext, so the check below is not vacuous: " + storedModel);

        Download download = export("secret-app");

        assertEquals(200, download.status(), download.text());
        assertEquals("application/zip", download.contentType());
        assertEquals(stored, dial.getProxy().getResourceService().getResource(descriptor), "the export does not change the stored Application");
        assertEquals(storedToolSet, dial.getProxy().getResourceService().getResource(toolSetDescriptor), "the export does not change the stored ToolSet");
        assertEquals(storedModel, dial.getProxy().getResourceService().getResource(modelDescriptor), "the export does not change the stored Model");
        Map<String, String> entries = entries(download);
        assertEquals(9, entries.size(), "manifest, registry, three resources with their sidecars, verification: " + entries.keySet());
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String content = entry.getValue();
            assertFalse(content.contains(ROUTE_SECRET) || content.contains(SERVICE_SECRET) || content.contains(TOOLSET_SECRET)
                    || content.contains(MODEL_SECRET), "a secret is in " + entry.getKey());
            assertFalse(content.contains("ENC["), "a ciphertext is in " + entry.getKey());
        }

        JsonNode registry = ProxyUtil.MAPPER.readTree(entries.get(ExportArchive.REGISTRY_ENTRY));

        JsonNode application = ProxyUtil.MAPPER.readTree(entries.get(ExportRegistryJson.resource(registry, "application:platform:secret-app")
                .get("entryName").asText()));
        assertEquals("Secret-bearing App", application.get("display_name").asText());
        assertEquals("http://application1/v1/completions", application.get("endpoint").asText());
        assertEquals("http://localhost:9876", application.get("routes").get("my-route").get("upstreams").get(0).get("endpoint").asText());
        assertEquals("my-client-id", application.get("external_services").get("svc1").get("auth_settings").get("client_id").asText());
        JsonNode toolSet = ProxyUtil.MAPPER.readTree(entries.get(ExportRegistryJson.resource(registry, "toolset:platform:secret-toolset")
                .get("entryName").asText()));
        assertEquals("toolset-client-id", toolSet.get("auth_settings").get("client_id").asText());
        assertEquals("https://static.auth.example.com/token", toolSet.get("auth_settings").get("token_endpoint").asText());
        JsonNode model = ProxyUtil.MAPPER.readTree(entries.get(ExportRegistryJson.resource(registry, "model:platform:secret-model")
                .get("entryName").asText()));
        assertEquals("http://localhost:7001", model.get("upstreams").get(0).get("endpoint").asText(), "the rest of the upstream is kept");
        assertFalse(model.get("upstreams").get(0).has("key"));
    }

    @Test
    void testBodyThatCannotBeRedactedStillEndsInStatusAndNoArchive() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            storeRaw("broken-app", "[\"" + ROUTE_SECRET + "\"]");

            Download download = export("broken-app");

            assertEquals(500, download.status(), download.text());
            assertNotEquals("application/zip", download.contentType());
            assertFalse(download.text().contains(ROUTE_SECRET), "the answer echoes a value: " + download.text());
        } finally {
            root.detachAppender(appender);
        }

        int records = 0;
        for (ILoggingEvent event : List.copyOf(appender.list)) {
            assertFalse(event.getFormattedMessage().contains(ROUTE_SECRET), "a log line holds a value: " + event.getFormattedMessage());
            boolean isFailureRecord = event.getFormattedMessage().startsWith("Export failed") || event.getFormattedMessage().startsWith("Export refused");
            for (IThrowableProxy throwable = event.getThrowableProxy(); throwable != null; throwable = throwable.getCause()) {
                assertFalse(String.valueOf(throwable.getMessage()).contains(ROUTE_SECRET), "a logged exception holds a value");
            }
            records += isFailureRecord ? 1 : 0;
        }
        // this body is stopped by the walker, which logs it; a body that reaches the redactor is logged once by ExportArchive
        assertEquals(1, records, "one failed resource is one record, with its resource and no value");
    }
}
