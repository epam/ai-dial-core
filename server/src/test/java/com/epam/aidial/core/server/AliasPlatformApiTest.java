package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin-managed aliases stored under {@code aliases/platform/<name>}: the management API, calling them by their url
 * under their {@code userRoles}, listings, health, and what is refused for them.
 */
class AliasPlatformApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/aliases.json";
    private static final String ALIAS_URL = "/v1/aliases/platform/";
    private static final String PLT = "aliases/platform/plt-v1";

    private static final String CHAT_REQUEST = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String CHAT_RESPONSE = "{\"id\":\"cmpl_1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    private static final String RESTRICTED_ALIAS = "{\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"models/gpt-4-internal\"}}";
    private static final String MODEL_ALIAS = "{\"displayName\":\"Platform assistant\",\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"models/gpt-4-internal\"}}";

    @Test
    @DialConfigLocation(CONFIG)
    void adminCreatesReadsUpdatesAndDeletesAnAlias() {
        Response created = putAlias("plt-v1", MODEL_ALIAS, "If-None-Match", "*");
        assertEquals(200, created.status(), created.body());
        String etag = created.headers().get("etag");
        assertNotNull(etag);
        assertEquals(412, putAlias("plt-v1", MODEL_ALIAS, "If-None-Match", "*").status());

        Response read = admin(HttpMethod.GET, ALIAS_URL + "plt-v1", null);
        assertEquals(200, read.status(), read.body());
        JsonObject body = new JsonObject(read.body());
        assertEquals("models/gpt-4-internal", body.getJsonObject("target").getString("path"));
        assertEquals("Platform assistant", body.getString("displayName"));
        assertEquals("plt-v1", body.getString("name"));
        assertFalse(body.containsKey("forwardAuthToken"), read.body());

        Response updated = putAlias("plt-v1", "{\"displayName\":\"Renamed\",\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"models/gpt-4-internal\"}}",
                "If-Match", etag);
        assertEquals(200, updated.status(), updated.body());
        assertEquals("Renamed", new JsonObject(admin(HttpMethod.GET, ALIAS_URL + "plt-v1", null).body()).getString("displayName"));

        Response listing = admin(HttpMethod.GET, "/v1/metadata/aliases/platform/", null);
        assertEquals(200, listing.status(), listing.body());
        assertTrue(listing.body().contains(PLT), listing.body());

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            assertEquals(200, chat(PLT, "default-key").status());

            assertEquals(204, admin(HttpMethod.DELETE, ALIAS_URL + "plt-v1", null).status());
            assertEquals(404, admin(HttpMethod.GET, ALIAS_URL + "plt-v1", null).status());
            assertEquals(404, chat(PLT, "default-key").status());
        }
        assertFalse(ids(models("default-key")).contains(PLT));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void onlyAdminsManageAliases() {
        assertEquals(200, putAlias("plt-v1", MODEL_ALIAS).status());

        for (String[] caller : List.of(new String[] {"authorization", "user"}, new String[] {"api-key", "default-key"})) {
            assertEquals(403, send(HttpMethod.PUT, ALIAS_URL + "other-v1", null, MODEL_ALIAS, caller).status());
            assertEquals(403, send(HttpMethod.GET, ALIAS_URL + "plt-v1", null, null, caller).status());
            assertEquals(403, send(HttpMethod.DELETE, ALIAS_URL + "plt-v1", null, null, caller).status());
            assertEquals(403, send(HttpMethod.GET, "/v1/metadata/aliases/platform/", null, null, caller).status());
        }
        assertEquals(200, admin(HttpMethod.GET, ALIAS_URL + "plt-v1", null).status());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void aliasesLiveDirectlyInThePlatformBucket() {
        assertEquals(404, admin(HttpMethod.PUT, "/v1/aliases/" + bucket + "/plt-v1", MODEL_ALIAS).status());
        assertEquals(400, putAlias("folder/plt-v1", MODEL_ALIAS).status());
        assertEquals(400, putAlias("bad%20name", MODEL_ALIAS).status());
        // the generic resource API does not serve aliases
        assertEquals(404, send(HttpMethod.PUT, "/v1/aliases/" + bucket + "/x", null, MODEL_ALIAS).status());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void invalidBodiesAreRefusedAndNothingIsWritten() {
        assertBadRequest("{\"userRoles\":{\"role\":\"default-user\"},\"target\":{\"path\":\"models/gpt-4-internal\"}}");
        assertBadRequest("{\"endpoint\":\"http://x\",\"target\":{\"path\":\"models/gpt-4-internal\"}}");
        assertBadRequest("{\"target\":{\"path\":\"aliases/assistant\"}}");
        assertBadRequest("{\"presentAs\":\"toolset\",\"target\":{\"path\":\"models/gpt-4-internal\"}}");
        assertBadRequest("{\"displayName\":\"No target\"}");
        assertBadRequest("{\"name\":\"other-v1\",\"target\":{\"path\":\"models/gpt-4-internal\"}}");

        // a resource target must be public or platform; a user bucket could be changed by its owner at any time
        assertBadRequest("{\"target\":{\"path\":\"applications/" + bucket + "/res-app\"}}");
        assertBadRequest("{\"target\":{\"path\":\"toolsets/" + bucket + "/tools\"}}");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void platformResourceTargetIsAccepted() {
        // a platform application can be the target of a platform alias
        verify(send(HttpMethod.PUT, "/v1/applications/platform/plt-app", null,
                "{\"endpoint\": \"http://localhost:4848/plt-app/chat/completions\"}", "authorization", "admin"), 200);
        assertEquals(200, putAlias("plt-v1",
                "{\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"applications/platform/plt-app\"}}").status());

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/plt-app/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            assertEquals(200, chat(PLT, "default-key").status());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void listedIdIsTheIdThatIsCalled() {
        assertEquals(200, putAlias("Plt.v1_2-x", MODEL_ALIAS).status());
        String id = "aliases/platform/Plt.v1_2-x";
        assertTrue(ids(models("default-key")).contains(id), models("default-key").body());
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            assertEquals(200, chat(id, "default-key").status());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void storedBlobThatCallsIgnoreIsShownInvalid() {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.ALIAS,
                ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, "hand-v1");
        dial.getProxy().getResourceService().putResource(descriptor, "{\"displayName\":\"no target\"}", EtagHeader.ANY, "test", false);

        Response read = admin(HttpMethod.GET, ALIAS_URL + "hand-v1", null);
        assertEquals(200, read.status(), read.body());
        JsonObject body = new JsonObject(read.body());
        assertEquals("invalid", body.getString("status"));
        assertEquals("no target", body.getString("displayName"));
        assertTrue(body.getJsonArray("validationWarnings").encode().contains("target.path"), read.body());

        assertEquals(404, chat("aliases/platform/hand-v1", "default-key").status());
        assertFalse(ids(models("default-key")).contains("aliases/platform/hand-v1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void userRolesDecideWhoMayCallStoredAlias() {
        assertEquals(200, putAlias("plt-v1", RESTRICTED_ALIAS).status());

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            assertEquals(200, chat(PLT, "default-key").status());
            // power-user may call the target directly, but has no role on the alias
            assertEquals(403, chat(PLT, "power-key").status());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void userRolesDecideWhereStoredAliasIsListed() {
        assertEquals(200, putAlias("plt-v1", RESTRICTED_ALIAS).status());

        assertTrue(ids(models("default-key")).contains(PLT));
        assertFalse(ids(models("power-key")).contains(PLT));
        assertEquals(200, send(HttpMethod.GET, "/openai/models/" + PLT, null, "", "api-key", "default-key").status());
        assertEquals(403, send(HttpMethod.GET, "/openai/models/" + PLT, null, "", "api-key", "power-key").status());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void storedAliasWithoutUserRolesTakesTheTargetRoles() {
        assertEquals(200, putAlias("open-v1", "{\"target\":{\"path\":\"models/gpt-4-internal\"}}").status());

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            // gpt-4-internal is for power-user only
            assertEquals(403, chat("aliases/platform/open-v1", "default-key").status());
            assertEquals(200, chat("aliases/platform/open-v1", "power-key").status());
        }
        assertTrue(ids(models("power-key")).contains("aliases/platform/open-v1"));
        assertFalse(ids(models("default-key")).contains("aliases/platform/open-v1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void brokenTargetIsAcceptedAndReported() {
        assertEquals(200, putAlias("broken-v1", "{\"target\":{\"path\":\"models/missing\"}}").status());

        assertEquals(503, chat("aliases/platform/broken-v1", "default-key").status());
        Response health = admin(HttpMethod.GET, "/v1/admin/health/config", null);
        assertEquals(200, health.status(), health.body());
        assertTrue(health.body().contains("\"aliases/platform/broken-v1\""), health.body());
        assertFalse(ids(models("default-key")).contains("aliases/platform/broken-v1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void otherBucketsAndFoldersHoldNoAlias() {
        assertEquals(200, putAlias("plt-v1", MODEL_ALIAS).status());
        AtomicInteger calls = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", request -> {
                calls.incrementAndGet();
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });

            assertEquals(404, chat("aliases/" + bucket + "/plt-v1", "default-key").status());
            assertEquals(404, chat("aliases/platform/folder/plt-v1", "default-key").status());
            for (String id : List.of("platform/plt-v1", "models/platform/plt-v1")) {
                Response response = chat(id, "default-key");
                assertNotEquals(200, response.status(), id + ": " + response.body());
            }
        }
        assertEquals(0, calls.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void aliasesCannotBeSharedPublishedCopiedOrMoved() {
        assertEquals(200, putAlias("plt-v1", MODEL_ALIAS).status());

        Response share = operationRequest("/v1/ops/resource/share/create",
                "{\"invitationType\": \"link\", \"resources\": [{\"url\": \"aliases/platform/plt-v1\"}]}", "authorization", "admin");
        assertEquals(400, share.status(), share.body());

        Response copy = operationRequest("/v1/ops/resource/copy",
                "{\"sourceUrl\": \"aliases/platform/plt-v1\", \"destinationUrl\": \"aliases/platform/plt-v2\"}", "authorization", "admin");
        assertEquals(400, copy.status(), copy.body());
        Response move = operationRequest("/v1/ops/resource/move",
                "{\"sourceUrl\": \"aliases/platform/plt-v1\", \"destinationUrl\": \"aliases/platform/plt-v2\"}", "authorization", "admin");
        assertEquals(400, move.status(), move.body());

        assertEquals(404, admin(HttpMethod.GET, ALIAS_URL + "plt-v2", null).status());
        assertEquals(200, admin(HttpMethod.GET, ALIAS_URL + "plt-v1", null).status());
    }

    private void assertBadRequest(String body) {
        Response response = putAlias("bad-v1", body);
        assertEquals(400, response.status(), body + " -> " + response.body());
        assertEquals(404, admin(HttpMethod.GET, ALIAS_URL + "bad-v1", null).status());
    }

    private Response putAlias(String name, String body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "authorization";
        all[1] = "admin";
        System.arraycopy(headers, 0, all, 2, headers.length);
        return send(HttpMethod.PUT, ALIAS_URL + name, null, body, all);
    }

    private Response admin(HttpMethod method, String path, String body) {
        return send(method, path, null, body, "authorization", "admin");
    }

    private Response chat(String deployment, String apiKey) {
        return send(HttpMethod.POST, "/openai/deployments/" + deployment + "/chat/completions", null, CHAT_REQUEST,
                "api-key", apiKey, "Content-Type", "application/json");
    }

    private Response models(String apiKey) {
        Response response = send(HttpMethod.GET, "/openai/models", null, "", "api-key", apiKey);
        assertEquals(200, response.status(), response.body());
        return response;
    }

    private static List<String> ids(Response response) {
        JsonArray data = new JsonObject(response.body()).getJsonArray("data");
        return data.stream().map(item -> ((JsonObject) item).getString("id")).toList();
    }
}
