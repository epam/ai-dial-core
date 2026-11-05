package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Routers on the Configuration API: {@code /v1/routers/platform/{name}}, its metadata listing and the file config
 * inspection. Runs with {@code config.onInvalidEntity: skip} so a broken blob router is recorded, not fatal.
 */
class ConfigRouterTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/routers.json";
    private static final String ROUTER_URL = "/v1/routers/platform/";
    private static final String VALID = "{\"baseUrl\": \"http://localhost:4848\", \"userRoles\": [\"analysts\"],"
            + " \"interfaces\": {\"openaiChatCompletions\": {}}}";
    private static final String TYPED = "dial-config/routers-typed.json";
    static final String TYPED_ROUTER = "{\"baseUrl\": \"http://localhost:4848\", \"applicationTypeSchemaId\": \"https://dial.test/router-type\","
                + " \"applicationProperties\": %s, \"interfaces\": {\"openaiChatCompletions\": {}}}";
    private static final String COMPLETION = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":10,\"total_tokens\":15}}";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("config", new JsonObject().put("onInvalidEntity", "skip"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void blobRouterReadBack() {
        Response put = admin(HttpMethod.PUT, ROUTER_URL + "r-blob", VALID);
        verify(put, 200);
        assertEquals("r-blob", ProxyUtil.MAPPER.readTree(put.body()).get("name").asText());
        assertNotNull(put.headers().get("etag"));

        Response get = admin(HttpMethod.GET, ROUTER_URL + "r-blob", null);
        verify(get, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("r-blob", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertEquals("http://localhost:4848", body.get("baseUrl").asText());
        assertEquals("analysts", body.get("userRoles").get(0).asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void fileRouterOnlyOnTheFileConfigSurface() {
        verify(admin(HttpMethod.GET, ROUTER_URL + "r1", null), 404);

        Response file = admin(HttpMethod.GET, "/v1/admin/config/file/routers/r1", null);
        verify(file, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(file.body());
        assertEquals("r1", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());

        Response list = admin(HttpMethod.GET, "/v1/admin/config/file/routers", null);
        verify(list, 200);
        assertTrue(list.body().contains("\"r1\""), list.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void metadataListsTheBlobRouterOnly() {
        verify(admin(HttpMethod.PUT, ROUTER_URL + "r-blob", VALID), 200);

        Response response = admin(HttpMethod.GET, "/v1/metadata/routers/platform/", null);
        verify(response, 200);
        JsonNode items = ProxyUtil.MAPPER.readTree(response.body()).get("items");
        List<String> names = new ArrayList<>();
        items.forEach(item -> names.add(item.get("name").asText()));
        assertEquals(List.of("r-blob"), names, response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void nonAdminGetsForbiddenAndPostIsNotAllowed() {
        verify(send(HttpMethod.GET, ROUTER_URL + "r1", null, "", "authorization", "user"), 403);
        verify(send(HttpMethod.PUT, ROUTER_URL + "r1", null, VALID, "authorization", "user"), 403);
        Response post = admin(HttpMethod.POST, ROUTER_URL + "r1", VALID);
        verify(post, 405);
        assertTrue(post.headers().get("Allow").contains("PUT"), post.headers().toString());
        verify(admin(HttpMethod.GET, "/v1/routers/public/r1", null), 404);
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void routerWithInterceptorsRejectedOnWrite() {
        Response response = admin(HttpMethod.PUT, ROUTER_URL + "r-bad", "{\"baseUrl\": \"http://localhost:4848\","
                + " \"interfaces\": {\"openaiChatCompletions\": {}}, \"interceptors\": [\"i1\"]}");

        verify(response, 422);
        JsonNode warnings = ProxyUtil.MAPPER.readTree(response.body()).get("validationWarnings");
        assertEquals(1, warnings.size(), response.body());
        assertEquals("interceptors", warnings.get(0).get("field").asText());
        assertTrue(warnings.get(0).get("message").asText().contains("A router declares no interceptors"), response.body());
        verify(admin(HttpMethod.GET, ROUTER_URL + "r-bad", null), 404);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerWithTranslatorMismatchRejectedOnWrite() {
        Response response = admin(HttpMethod.PUT, ROUTER_URL + "r-bad", "{\"baseUrl\": \"http://localhost:4848\","
                + " \"interfaces\": {\"openaiChatCompletions\": {}, \"anthropicMessages\": {\"translator\":"
                + " {\"out\": \"openaiChatCompletions\", \"baseUrl\": \"http://t\"}}}}");

        verify(response, 422);
        assertTrue(response.body().contains("requires mode 'translator'"), response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerWithUpstreamsRejectedOnWrite() {
        verify(admin(HttpMethod.PUT, ROUTER_URL + "r-bad",
                "{\"baseUrl\": \"http://router\", \"upstreams\": [{\"endpoint\": \"http://x\"}]}"), 400);
        verify(admin(HttpMethod.GET, ROUTER_URL + "r-bad", null), 404);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void rejectedWriteLeavesNothingBehind() {
        verify(admin(HttpMethod.PUT, ROUTER_URL + "r-bad",
                "{\"endpoint\": \"http://router/chat\", \"interfaces\": {\"openaiChatCompletions\": {}}}"), 422);

        verify(send(HttpMethod.PUT, ROUTER_URL + "r-bad", null, VALID, "authorization", "admin", "If-None-Match", "*"), 200);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerNameTakenByModel() {
        Response response = admin(HttpMethod.PUT, ROUTER_URL + "gpt-4", VALID);
        verify(response, 409);
        assertTrue(response.body().contains("Deployment ID 'gpt-4' is already used by a different entity"), response.body());
        verify(admin(HttpMethod.GET, ROUTER_URL + "gpt-4", null), 404);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void modelNameTakenByRouter() {
        Response response = admin(HttpMethod.PUT, "/v1/models/platform/r1", "{\"type\": \"chat\", \"endpoint\": \"http://localhost:4848/chat\"}");
        verify(response, 409);
        assertTrue(response.body().contains("Deployment ID 'r1' is already used by a different entity"), response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void blobRouterServesAtOnce() {
        verify(admin(HttpMethod.PUT, ROUTER_URL + "r-blob", VALID), 200);

        assertTrue(listedRouters("proxyKey1").contains("r-blob"));
        List<String> paths = Collections.synchronizedList(new ArrayList<>());
        try (TestWebServer server = new TestWebServer(4848, request -> {
            paths.add(request.getPath());
            return TestWebServer.createResponse(200, COMPLETION, "content-type", "application/json");
        })) {
            Response response = send(HttpMethod.POST, "/openai/deployments/r-blob/chat/completions", null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "api-key", "proxyKey1", "content-type", "application/json");
            verify(response, 200);
        }
        assertEquals(List.of("/openai/deployments/r-blob/chat/completions"), paths);
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void deletedRouterGoneAtOnce() {
        verify(admin(HttpMethod.PUT, ROUTER_URL + "r-blob", VALID), 200);
        verify(send(HttpMethod.GET, "/v1/routers/r-blob", null, null, "api-key", "proxyKey1"), 200);

        verify(admin(HttpMethod.DELETE, ROUTER_URL + "r-blob", null), 204);

        verify(send(HttpMethod.GET, "/v1/routers/r-blob", null, null, "api-key", "proxyKey1"), 404);
        verify(admin(HttpMethod.DELETE, ROUTER_URL + "r-blob", null), 404);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void blobRouterSurvivesReload() {
        verify(admin(HttpMethod.PUT, ROUTER_URL + "r-blob", VALID), 200);

        reload();

        verify(admin(HttpMethod.GET, ROUTER_URL + "r-blob", null), 200);
        verify(send(HttpMethod.GET, "/v1/routers/r-blob", null, null, "api-key", "proxyKey1"), 200);
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void blobRouterShadowsTheFileRouter() {
        verify(admin(HttpMethod.PUT, ROUTER_URL + "r1", "{\"baseUrl\": \"http://localhost:4848\","
                + " \"userRoles\": [\"default\"], \"interfaces\": {\"openaiChatCompletions\": {}}}"), 200);

        reload();

        List<String> forDefault = listedRouters("proxyKey2");
        assertEquals(1, forDefault.stream().filter("r1"::equals).count(), forDefault.toString());
        assertFalse(listedRouters("proxyKey1").contains("r1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void invalidRouterRecordedUnderSkip() {
        // written straight to storage, as an older pod or a hand-made blob could, bypassing the PUT checks
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.ROUTER,
                ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, "r-bad");
        dial.getProxy().getResourceService().putResource(descriptor, "{\"baseUrl\": \"http://localhost:4848\","
                + " \"interceptors\": [\"i1\"], \"interfaces\": {\"openaiChatCompletions\": {}}}", EtagHeader.ANY, null, false);

        reload();

        verify(send(HttpMethod.GET, "/v1/routers/r1", null, null, "api-key", "proxyKey1"), 200);
        verify(send(HttpMethod.GET, "/v1/routers/r-bad", null, null, "api-key", "proxyKey1"), 404);
        Response health = admin(HttpMethod.GET, "/v1/admin/health/config", null);
        verify(health, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(health.body());
        assertEquals("degraded", body.get("status").asText());
        List<String> ids = new ArrayList<>();
        body.get("skipped").forEach(entry -> ids.add(entry.get("id").asText()));
        assertTrue(ids.contains("routers/platform/r-bad"), health.body());
        Response get = admin(HttpMethod.GET, ROUTER_URL + "r-bad", null);
        verify(get, 200);
        assertEquals("invalid", ProxyUtil.MAPPER.readTree(get.body()).get("status").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void routerPropertiesWithoutSchemaRejected() {
        Response response = admin(HttpMethod.PUT, ROUTER_URL + "r-bad", "{\"baseUrl\": \"http://localhost:4848\","
                + " \"applicationProperties\": {\"tier\": \"medium\"}, \"interfaces\": {\"openaiChatCompletions\": {}}}");

        verify(response, 422);
        assertTrue(response.body().contains("A router declares applicationTypeSchemaId and applicationProperties together"), response.body());
        verify(admin(HttpMethod.GET, ROUTER_URL + "r-bad", null), 404);
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void untypedRouterStoredWithoutTheFields() {
        verify(admin(HttpMethod.PUT, ROUTER_URL + "r-plain", VALID), 200);

        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.ROUTER,
                ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, "r-plain");
        JsonNode blob = ProxyUtil.MAPPER.readTree(dial.getProxy().getResourceService().getResource(descriptor));
        assertFalse(blob.has("applicationTypeSchemaId"), blob.toString());
        assertFalse(blob.has("applicationProperties"), blob.toString());
    }

    @Test
    @DialConfigLocation(TYPED)
    @SneakyThrows
    void typedRouterWithUnknownSchemaRejected() {
        Response response = admin(HttpMethod.PUT, ROUTER_URL + "r-bad", "{\"baseUrl\": \"http://localhost:4848\","
                + " \"applicationTypeSchemaId\": \"https://dial.test/none\", \"applicationProperties\": {\"tier\": \"medium\"},"
                + " \"interfaces\": {\"openaiChatCompletions\": {}}}");

        verify(response, 422);
        JsonNode warning = ProxyUtil.MAPPER.readTree(response.body()).get("validationWarnings").get(0);
        assertEquals("applicationTypeSchemaId", warning.get("field").asText(), response.body());
        verify(admin(HttpMethod.GET, ROUTER_URL + "r-bad", null), 404);
    }

    @Test
    @DialConfigLocation(TYPED)
    @SneakyThrows
    void typedRouterWhosePropertiesBreakTheSchemaRejected() {
        Response response = admin(HttpMethod.PUT, ROUTER_URL + "r-bad", TYPED_ROUTER.formatted("{\"tier\": 5}"));

        verify(response, 422);
        JsonNode warning = ProxyUtil.MAPPER.readTree(response.body()).get("validationWarnings").get(0);
        assertEquals("applicationProperties", warning.get("field").asText(), response.body());
        verify(admin(HttpMethod.GET, ROUTER_URL + "r-bad", null), 404);
    }

    @Test
    @DialConfigLocation(TYPED)
    @SneakyThrows
    void typedRouterWrittenAndReadBack() {
        verify(admin(HttpMethod.PUT, ROUTER_URL + "r-typed", TYPED_ROUTER.formatted("{\"tier\": \"medium\"}")), 200);

        Response get = admin(HttpMethod.GET, ROUTER_URL + "r-typed", null);
        verify(get, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("https://dial.test/router-type", body.get("applicationTypeSchemaId").asText(), get.body());
        assertEquals("medium", body.get("applicationProperties").get("tier").asText(), get.body());
    }

    private Response admin(HttpMethod method, String path, String body) {
        return send(method, path, null, body == null ? "" : body, "authorization", "admin");
    }

    private void reload() {
        verify(operationRequest("/v1/ops/config/reload", null, "Authorization", "admin"), 200);
    }

    @SneakyThrows
    private List<String> listedRouters(String key) {
        Response response = send(HttpMethod.GET, "/v1/routers", null, null, "api-key", key);
        verify(response, 200);
        List<String> ids = new ArrayList<>();
        ProxyUtil.MAPPER.readTree(response.body()).get("data").forEach(router -> ids.add(router.get("id").asText()));
        return ids;
    }
}
