package com.epam.aidial.core.server;

import com.epam.aidial.core.server.config.JarEchoPluginProvider;
import com.epam.aidial.core.server.config.TestSchemaPluginProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
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
 * config declares {@code file-plugin}, {@code valid-plugin} and {@code bad/plugin}, all of plugin id {@code test-echo}
 * (on the test classpath, needs a string {@code settings.greeting}); the plugin directory {@code custom/echo} holds a
 * jar registering {@code jar-echo} (accepts any settings). {@link SkipMode} runs the soft write and the {@code skip}
 * rebuild, {@link ExcludedPlugin} a plugin directory {@code plugins.exclude} names.
 */
public class ConfigPluginApiTest extends ResourceBaseTest {

    // Written once and kept: the plugin class loader holds the jar open as long as the JVM runs
    private static final Path PLUGINS_DIR = pluginsDirWithJarEcho("custom/echo");

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
        Response put = adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"jar-echo\"}");
        verify(put, 200);
        assertTrue(put.body().contains("\"name\":\"p1\""), put::body);
        assertNotNull(put.headers().get("etag"));

        Response get = adminGet(this, "/v1/plugins/platform/p1");
        verify(get, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("jar-echo", body.get("pluginId").asText());
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
        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"test-echo\", \"kind\": \"origin\"}"), 400);
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    @Test
    @SneakyThrows
    void testStrictWrite() {
        Response put = adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"missing\"}");
        verify(put, 422);
        JsonNode warnings = ProxyUtil.MAPPER.readTree(put.body()).get("validationWarnings");
        assertEquals(1, warnings.size(), put::body);
        assertEquals("pluginId", warnings.get(0).get("field").asText());
        assertTrue(warnings.get(0).get("message").asText().contains("missing"), put::body);
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    @Test
    @SneakyThrows
    void testRebuildUnderAbortKeepsTheEntity() {
        putBlob(this, "p1", "{\"pluginId\": \"missing\"}");
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
        Response put = adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"test-echo\", \"settings\": {}}");
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
        assertEquals("test-echo", body.get("pluginId").asText());
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
        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"jar-echo\"}"), 200);

        verify(send(HttpMethod.DELETE, "/v1/plugins/platform/p1", null, "", "authorization", "admin"), 204);
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    @Test
    @SneakyThrows
    void testWrittenWithTheAlias() {
        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"type\": \"jar-echo\"}"), 200);

        JsonNode blob = ProxyUtil.MAPPER.readTree(readBlob(this, "p1"));
        assertEquals("jar-echo", blob.get("pluginId").asText(), blob::toString);
        assertFalse(blob.has("type"), blob::toString);
        JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p1").body());
        assertEquals("jar-echo", body.get("pluginId").asText(), body::toString);
        assertFalse(body.has("type"), body::toString);
    }

    @Test
    void testBothNames() {
        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"jar-echo\", \"type\": \"jar-echo\"}"), 400);
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    @Test
    @SneakyThrows
    void testUnknownIdWrittenWithTheAlias() {
        Response put = adminPut(this, "/v1/plugins/platform/p1", "{\"type\": \"missing\"}");
        verify(put, 422);
        JsonNode warnings = ProxyUtil.MAPPER.readTree(put.body()).get("validationWarnings");
        assertEquals(1, warnings.size(), put::body);
        assertEquals("pluginId", warnings.get(0).get("field").asText());
    }

    @Test
    @SneakyThrows
    void testStoredBlobWithTheAlias() {
        putBlob(this, "p1", "{\"type\": \"test-echo\", \"settings\": {\"greeting\": \"hi\"}}");
        reload(this);

        JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p1").body());
        assertEquals("test-echo", body.get("pluginId").asText(), body::toString);
        assertEquals("valid", body.get("status").asText(), body::toString);
    }

    @Test
    @SneakyThrows
    void testFileEntryWithTheAlias() {
        Response get = adminGet(this, "/v1/admin/config/file/plugins/legacy-plugin");
        verify(get, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("test-echo", body.get("pluginId").asText(), get::body);
        assertEquals("valid", body.get("status").asText(), get::body);
    }

    @Test
    @SneakyThrows
    void testWrongValueTypeRejectedBySchema() {
        TestSchemaPluginProvider.VALIDATED.clear();

        assertOneSettingsWarning(adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"test-schema\", \"settings\": {\"greeting\": 1}}"),
                "settings.greeting");
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
        assertTrue(TestSchemaPluginProvider.VALIDATED.isEmpty(), TestSchemaPluginProvider.VALIDATED::toString);
    }

    @Test
    @SneakyThrows
    void testMissingRequiredPropertyRejectedBySchema() {
        assertOneSettingsWarning(adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"test-schema\", \"settings\": {}}"),
                "settings.greeting");
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    @Test
    @SneakyThrows
    void testPropertyNotAllowedBySchema() {
        assertOneSettingsWarning(adminPut(this, "/v1/plugins/platform/p1",
                "{\"pluginId\": \"test-schema\", \"settings\": {\"greeting\": \"hi\", \"greting\": \"hi\"}}"), "settings.greting");
        verify(adminGet(this, "/v1/plugins/platform/p1"), 404);
    }

    @Test
    void testAcceptedBySchema() {
        TestSchemaPluginProvider.VALIDATED.clear();

        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"test-schema\", \"settings\": {\"greeting\": \"hi\"}}"), 200);

        assertTrue(TestSchemaPluginProvider.VALIDATED.contains(ProxyUtil.MAPPER.createObjectNode().put("greeting", "hi")),
                TestSchemaPluginProvider.VALIDATED::toString);
    }

    @Test
    @SneakyThrows
    void testProviderWithItsMetadataListed() {
        JsonNode item = listedProvider(this, JarEchoPluginProvider.ID);

        assertEquals(ProxyUtil.MAPPER.readTree("""
                {"id": "jar-echo", "directory": "custom/echo", "version": "1.2.0", "description": "Echoes the greeting",
                 "targets": ["model", "application"], "settingsSchema": {"type": "object"}}
                """), item);
    }

    @Test
    @SneakyThrows
    void testProviderWithoutMetadataListed() {
        JsonNode item = listedProvider(this, "test-echo");

        assertEquals(ProxyUtil.MAPPER.readTree("{\"id\": \"test-echo\", \"targets\": [\"model\", \"application\", \"router\", \"translator\"]}"), item);
    }

    @Test
    void testProvidersListedWhateverTheConfigHolds() {
        String before = listProviders(this).body();
        assertTrue(before.contains("\"id\":\"jar-echo\""), before);

        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"jar-echo\"}"), 200);
        assertEquals(before, listProviders(this).body());

        verify(send(HttpMethod.DELETE, "/v1/plugins/platform/p1", null, "", "authorization", "admin"), 204);
        assertEquals(before, listProviders(this).body());
    }

    @Test
    void testProvidersNotListedForUser() {
        verify(send(HttpMethod.GET, "/v1/admin/plugin-providers", null, "", "authorization", "user"), 403);
    }

    @Test
    @SneakyThrows
    void testPluginMetadata() {
        verify(adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"jar-echo\"}"), 200);

        Response list = adminGet(this, "/v1/metadata/plugins/platform/");
        verify(list, 200);
        assertTrue(names(ProxyUtil.MAPPER.readTree(list.body()).get("items")).contains("p1"), list::body);
    }

    /**
     * The soft write and the {@code skip} rebuild: a dangling plugin id and rejected settings both drop the entity and
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
            verify(adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"missing\"}"), 200);

            JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p1").body());
            assertEquals("invalid", body.get("status").asText(), body::toString);
            assertEquals("pluginId", body.get("validationWarnings").get(0).get("field").asText(), body::toString);
        }

        @Test
        @SneakyThrows
        void testRebuildUnderSkipDropsTheEntity() {
            putBlob(this, "p1", "{\"pluginId\": \"missing\"}");
            reload(this);

            JsonNode health = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/admin/health/config").body());
            assertEquals("degraded", health.get("status").asText(), health::toString);
            assertTrue(skippedIds(health).contains("plugins/platform/p1"), health::toString);
            JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p1").body());
            assertEquals("invalid", body.get("status").asText(), body::toString);
            assertEquals("pluginId", body.get("validationWarnings").get(0).get("field").asText(), body::toString);
        }

        @Test
        @SneakyThrows
        void testInvalidBlobWithTheAlias() {
            putBlob(this, "p1", "{\"type\": \"missing\"}");
            reload(this);

            JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p1").body());
            assertEquals("missing", body.get("pluginId").asText(), body::toString);
            assertFalse(body.has("type"), body::toString);
            assertEquals("invalid", body.get("status").asText(), body::toString);
        }

        @Test
        @SneakyThrows
        void testRejectedBySchemaOnRebuildUnderSkip() {
            putBlob(this, "p3", "{\"pluginId\": \"test-schema\", \"settings\": {\"greeting\": 1}}");
            reload(this);

            JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p3").body());
            assertEquals("invalid", body.get("status").asText(), body::toString);
            assertEquals("settings.greeting", body.get("validationWarnings").get(0).get("field").asText(), body::toString);
        }

        @Test
        @SneakyThrows
        void testRejectedSettingsOnRebuildUnderSkip() {
            putBlob(this, "p2", "{\"pluginId\": \"test-echo\", \"settings\": {}}");
            reload(this);

            JsonNode body = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/plugins/platform/p2").body());
            assertEquals("invalid", body.get("status").asText(), body::toString);
            assertTrue(body.get("validationWarnings").get(0).get("field").asText().startsWith("settings"), body::toString);
            JsonNode health = ProxyUtil.MAPPER.readTree(adminGet(this, "/v1/admin/health/config").body());
            assertTrue(skippedIds(health).contains("plugins/platform/p2"), health::toString);
        }
    }

    /**
     * The plugin directory {@code builtin/echo} provides {@code jar-echo} and {@code plugins.exclude} names it.
     */
    public static class ExcludedPlugin extends ResourceBaseTest {

        private static final Path PLUGINS_DIR = pluginsDirWithJarEcho("builtin/echo");

        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject().put("plugins", new JsonObject()
                    .put("dir", PLUGINS_DIR.toString())
                    .put("exclude", new JsonArray().add("builtin/echo")));
        }

        @Test
        @SneakyThrows
        void testExcludedPluginDirectoryNotListed() {
            Response list = listProviders(this);
            for (JsonNode item : ProxyUtil.MAPPER.readTree(list.body())) {
                assertFalse(item.get("id").asText().equals("jar-echo"), list::body);
            }
        }

        @Test
        @SneakyThrows
        void testExcludedPluginIdIsUnknown() {
            Response put = adminPut(this, "/v1/plugins/platform/p1", "{\"pluginId\": \"jar-echo\"}");
            verify(put, 422);
            JsonNode warnings = ProxyUtil.MAPPER.readTree(put.body()).get("validationWarnings");
            assertEquals(1, warnings.size(), put::body);
            assertEquals("pluginId", warnings.get(0).get("field").asText());
        }
    }

    private static Path pluginsDirWithJarEcho(String pluginDir) {
        try {
            Path dir = Files.createTempDirectory("dial-plugins");
            Path jar = dir.resolve(pluginDir).resolve("jar-echo.jar");
            Files.createDirectories(jar.getParent());
            JarEchoPluginProvider.writeJar(jar, JarEchoPluginProvider.class.getName());
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Response listProviders(ResourceBaseTest test) {
        Response list = adminGet(test, "/v1/admin/plugin-providers");
        verify(list, 200);
        return list;
    }

    @SneakyThrows
    private static JsonNode listedProvider(ResourceBaseTest test, String id) {
        Response list = listProviders(test);
        for (JsonNode item : ProxyUtil.MAPPER.readTree(list.body())) {
            if (item.get("id").asText().equals(id)) {
                return item;
            }
        }
        throw new AssertionError("No provider " + id + " in " + list.body());
    }

    @SneakyThrows
    private static void assertOneSettingsWarning(Response put, String field) {
        verify(put, 422);
        JsonNode warnings = ProxyUtil.MAPPER.readTree(put.body()).get("validationWarnings");
        assertEquals(1, warnings.size(), put::body);
        assertEquals(field, warnings.get(0).get("field").asText(), put::body);
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
        test.dial.getProxy().getResourceService().putResource(pluginBlob(name), body, EtagHeader.ANY, null, false);
    }

    private static String readBlob(ResourceBaseTest test, String name) {
        return test.dial.getProxy().getResourceService().getResource(pluginBlob(name));
    }

    private static ResourceDescriptor pluginBlob(String name) {
        return fromDecoded(ResourceTypes.PLUGIN, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, name);
    }
}
