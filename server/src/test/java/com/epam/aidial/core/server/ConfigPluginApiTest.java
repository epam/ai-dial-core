package com.epam.aidial.core.server;

import com.epam.aidial.core.server.config.JarEchoPluginProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.epam.aidial.core.server.util.ResourceDescriptorFactory.fromDecoded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code plugins} config entity over HTTP, with the default strict write and {@code abort} rebuild. The test
 * config declares {@code file-plugin}, {@code valid-plugin} and {@code bad/plugin}, all of type {@code test-echo}
 * (on the test classpath, needs a string {@code settings.greeting}); the plugins directory holds a jar registering
 * {@code jar-echo} (accepts any settings). {@link SkipMode} runs the soft write and the {@code skip} rebuild.
 */
public class ConfigPluginApiTest extends ResourceBaseTest {

    // Written once and kept: the plugin class loader holds the jar open as long as the JVM runs
    private static final Path PLUGINS_DIR = pluginsDirWithJarEcho();

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("plugins", new JsonObject().put("dir", PLUGINS_DIR.toString()));
    }

    /**
     * Also plugin-registry "Jar in the directory" and request-routing "Plugin entity".
     */
    @Test
    @SneakyThrows
    void testMinimalDeclaration() {
        Response put = adminPut(this, "/v1/plugins/platform/p1", "{\"type\": \"jar-echo\"}");
        verify(put, 200);
        assertTrue(put.body().contains("\"name\":\"p1\""), put::body);
        assertNotNull(put.headers().get("etag"));

        Response get = adminGet(this, "/v1/plugins/platform/p1");
        verify(get, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("jar-echo", body.get("type").asText());
        assertEquals(ProxyUtil.MAPPER.createObjectNode(), body.get("settings"));
        assertEquals("p1", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
    }

    @Test
    void testMissingType() {
        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"settings\": {}}"), 400);
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    @Test
    void testUnknownField() {
        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"type\": \"test-echo\", \"kind\": \"origin\"}"), 400);
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    @Test
    @SneakyThrows
    void testStrictWrite() {
        Response put = adminPut(this, "/v1/plugins/platform/p1", "{\"type\": \"missing\"}");
        verify(put, 422);
        JsonNode warnings = ProxyUtil.MAPPER.readTree(put.body()).get("validationWarnings");
        assertEquals(1, warnings.size(), put::body);
        assertEquals("type", warnings.get(0).get("field").asText());
        assertTrue(warnings.get(0).get("message").asText().contains("missing"), put::body);
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    @Test
    @SneakyThrows
    void testRebuildUnderAbortKeepsTheEntity() {
        putBlob(this, "p1", "{\"type\": \"missing\"}");
        reload(this);

        JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p1").body());
        assertEquals("valid", body.get("status").asText(), body::toString);
        JsonNode health = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/admin/health/config").body());
        assertEquals("ok", health.get("status").asText(), health::toString);
    }

    /**
     * Also config-store "Plugin with rejected settings".
     */
    @Test
    @SneakyThrows
    void testRejectedSettingsOnWrite() {
        Response put = adminPut(this, "/v1/plugins/platform/p1", "{\"type\": \"test-echo\", \"settings\": {}}");
        verify(put, 422);
        JsonNode warnings = ProxyUtil.MAPPER.readTree(put.body()).get("validationWarnings");
        assertEquals(1, warnings.size(), put::body);
        assertEquals("settings.greeting", warnings.get(0).get("field").asText());
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    /**
     * Also request-routing "File plugin entry".
     */
    @Test
    @SneakyThrows
    void testFilePluginInspected() {
        Response get = adminGet(this, "/v1/admin/config/file/plugins/file-plugin");
        verify(get, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("file-plugin", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertEquals("test-echo", body.get("type").asText());
    }

    @Test
    @SneakyThrows
    void testSlashKeyedPluginDropped() {
        Response list = adminGet(this, "/v1/admin/config/file/plugins");
        verify(list, 200);
        List<String> names = names(ProxyUtil.MAPPER.readTree(list.body()).get("items"));
        assertTrue(names.contains("valid-plugin"), list::body);
        assertFalse(names.contains("bad/plugin"), list::body);

        Response health = adminGet(this, "/v1/admin/health/config");
        assertFalse(health.body().contains("bad/plugin"), health::body);
    }

    @Test
    void testDelete() {
        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"type\": \"jar-echo\"}"), 200);

        verify(send(HttpMethod.DELETE, "/v1/plugins/platform/p1", null, "", "authorization", "admin"), 204);
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    @Test
    @SneakyThrows
    void testPluginMetadata() {
        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"type\": \"jar-echo\"}"), 200);

        Response list = adminGet(this, "/v1/metadata/plugins/platform/");
        verify(list, 200);
        assertTrue(names(ProxyUtil.MAPPER.readTree(list.body()).get("items")).contains("p1"), list::body);
    }

    /**
     * The soft write and the {@code skip} rebuild: a dangling type and rejected settings both drop the entity and
     * record it as invalid.
     */
    public static class SkipMode extends ResourceBaseTest {

        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject()
                    .put("config", new JsonObject()
                            .put("write", new JsonObject().put("softValidation", true))
                            .put("onInvalidEntity", "skip"));
        }

        @Test
        @SneakyThrows
        void testSoftWriteUnderSkip() {
            verify(adminPut(this, "/v1/plugins/platform/p1", "{\"type\": \"missing\"}"), 200);

            JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p1").body());
            assertEquals("invalid", body.get("status").asText(), body::toString);
            assertEquals("type", body.get("validationWarnings").get(0).get("field").asText(), body::toString);
        }

        @Test
        @SneakyThrows
        void testRebuildUnderSkipDropsTheEntity() {
            putBlob(this, "p1", "{\"type\": \"missing\"}");
            reload(this);

            JsonNode health = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/admin/health/config").body());
            assertEquals("degraded", health.get("status").asText(), health::toString);
            assertTrue(skippedIds(health).contains("plugins/platform/p1"), health::toString);
            JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p1").body());
            assertEquals("invalid", body.get("status").asText(), body::toString);
            assertEquals("type", body.get("validationWarnings").get(0).get("field").asText(), body::toString);
        }

        @Test
        @SneakyThrows
        void testRejectedSettingsOnRebuildUnderSkip() {
            putBlob(this, "p2", "{\"type\": \"test-echo\", \"settings\": {}}");
            reload(this);

            JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p2").body());
            assertEquals("invalid", body.get("status").asText(), body::toString);
            assertTrue(body.get("validationWarnings").get(0).get("field").asText().startsWith("settings"), body::toString);
            JsonNode health = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/admin/health/config").body());
            assertTrue(skippedIds(health).contains("plugins/platform/p2"), health::toString);
        }
    }

    private static Path pluginsDirWithJarEcho() {
        try {
            Path dir = Files.createTempDirectory("dial-plugins");
            JarEchoPluginProvider.writeJar(dir.resolve("jar-echo.jar"), JarEchoPluginProvider.class.getName());
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> names(JsonNode items) {
        List<String> names = new ArrayList<>();
        items.forEach(item -> names.add(item.get("name").asText()));
        return names;
    }

    private static List<String> skippedIds(JsonNode health) {
        List<String> ids = new ArrayList<>();
        health.get("skipped").forEach(entry -> ids.add(entry.get("id").asText()));
        return ids;
    }

    private static Response adminPut(ResourceBaseTest test, String path, String body) {
        return test.send(HttpMethod.PUT, path, null, body, "authorization", "admin");
    }

    private static Response adminGet(ResourceBaseTest test, String path) {
        return test.send(HttpMethod.GET, path, null, "", "authorization", "admin");
    }

    private static void reload(ResourceBaseTest test) {
        verify(test.operationRequest("/v1/ops/config/reload", null, "Authorization", "admin"), 200);
    }

    private static void putBlob(ResourceBaseTest test, String name, String body) {
        ResourceService resourceService = test.dial.getProxy().getResourceService();
        ResourceDescriptor descriptor = fromDecoded(ResourceTypes.PLUGIN, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, name);
        resourceService.putResource(descriptor, body, EtagHeader.ANY, null, false);
    }
}
