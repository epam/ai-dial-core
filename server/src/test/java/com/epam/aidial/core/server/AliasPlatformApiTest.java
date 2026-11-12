package com.epam.aidial.core.server;

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
 * Admin-managed aliases stored under {@code aliases/platform/<name>}: the management API, calling them by their
 * short name after a config reload, listings, health, and what is refused for them.
 *
 * <p>Platform aliases differ from public aliases ({@code aliases/public/<name>}) in one key way: they are merged
 * into the config at reload time and callable by their short name (e.g., {@code plt-v1}), just like a config-file
 * alias. The management API still lives at {@code /v1/aliases/platform/<name>}.
 */
class AliasPlatformApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/aliases.json";
    private static final String ALIAS_URL = "/v1/aliases/platform/";

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
        assertTrue(listing.body().contains("aliases/platform/plt-v1"), listing.body());

        // platform alias is callable by short name after a reload
        reload();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            assertEquals(200, chat("plt-v1", "default-key").status());

            assertEquals(204, admin(HttpMethod.DELETE, ALIAS_URL + "plt-v1", null).status());
            reload();
            assertEquals(404, admin(HttpMethod.GET, ALIAS_URL + "plt-v1", null).status());
            assertEquals(404, chat("plt-v1", "default-key").status());
        }
        assertFalse(ids(models("default-key")).contains("plt-v1"));
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
        assertEquals(404, send(HttpMethod.PUT, "/v1/aliases/" + bucket + "/x", null, MODEL_ALIAS).status());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void invalidBodiesAreRefusedAndNothingIsWritten() {
        assertBadRequest("{\"userRoles\":{\"role\":\"default-user\"},\"target\":{\"path\":\"models/gpt-4-internal\"}}");
        assertBadRequest("{\"endpoint\":\"http://x\",\"target\":{\"path\":\"models/gpt-4-internal\"}}");
        assertBadRequest("{\"target\":{\"path\":\"aliases/assistant\"}}");
        assertBadRequest("{\"displayName\":\"No target\"}");
        assertBadRequest("{\"name\":\"other-v1\",\"target\":{\"path\":\"models/gpt-4-internal\"}}");

        // a resource target must be public or platform; a user bucket could be changed by its owner at any time
        assertBadRequest("{\"target\":{\"path\":\"applications/" + bucket + "/res-app\"}}");
        assertBadRequest("{\"target\":{\"path\":\"toolsets/" + bucket + "/tools\"}}");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void platformResourceTargetIsAccepted() {
        verify(send(HttpMethod.PUT, "/v1/applications/platform/plt-app", null,
                "{\"endpoint\": \"http://localhost:4848/plt-app/chat/completions\"}", "authorization", "admin"), 200);
        assertEquals(200, putAlias("plt-v1",
                "{\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"applications/platform/plt-app\"}}").status());
        reload();

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/plt-app/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            assertEquals(200, chat("plt-v1", "default-key").status());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void userRolesDecideWhoMayCallPlatformAlias() {
        assertEquals(200, putAlias("plt-v1", RESTRICTED_ALIAS).status());
        reload();

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            assertEquals(200, chat("plt-v1", "default-key").status());
            // power-user may call the target directly, but has no role on the alias
            assertEquals(403, chat("plt-v1", "power-key").status());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void userRolesDecideWhereAliasIsListed() {
        assertEquals(200, putAlias("plt-v1", RESTRICTED_ALIAS).status());
        reload();

        assertTrue(ids(models("default-key")).contains("plt-v1"));
        assertFalse(ids(models("power-key")).contains("plt-v1"));
        assertEquals(200, send(HttpMethod.GET, "/openai/models/plt-v1", null, "", "api-key", "default-key").status());
        assertEquals(403, send(HttpMethod.GET, "/openai/models/plt-v1", null, "", "api-key", "power-key").status());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void platformAliasWithoutUserRolesTakesTheTargetRoles() {
        assertEquals(200, putAlias("open-v1", "{\"target\":{\"path\":\"models/gpt-4-internal\"}}").status());
        reload();

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            // gpt-4-internal is for power-user only
            assertEquals(403, chat("open-v1", "default-key").status());
            assertEquals(200, chat("open-v1", "power-key").status());
        }
        assertTrue(ids(models("power-key")).contains("open-v1"));
        assertFalse(ids(models("default-key")).contains("open-v1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void noUserRolesAliasTakesPlatformResourceTargetRoles() {
        verify(send(HttpMethod.PUT, "/v1/applications/platform/plt-app", null,
                "{\"endpoint\": \"http://localhost:4848/plt-app/chat/completions\",\"userRoles\":[\"default-user\"]}", "authorization", "admin"), 200);
        // no userRoles on the alias: the alias inherits access from the target's userRoles
        assertEquals(200, putAlias("open-plt-v1", "{\"target\":{\"path\":\"applications/platform/plt-app\"}}").status());
        reload();

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/plt-app/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            assertEquals(200, chat("open-plt-v1", "default-key").status());
            // power-user has no role on the target application, so the alias is also denied
            assertEquals(403, chat("open-plt-v1", "power-key").status());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void brokenTargetIsAcceptedAndReportedAfterReload() {
        assertEquals(200, putAlias("broken-v1", "{\"target\":{\"path\":\"models/missing\"}}").status());
        reload();

        assertEquals(503, chat("broken-v1", "default-key").status());
        Response health = admin(HttpMethod.GET, "/v1/admin/health/config", null);
        assertEquals(200, health.status(), health.body());
        // platform alias is reported by its config key (short name under aliases/)
        assertTrue(health.body().contains("aliases/broken-v1"), health.body());
        assertFalse(ids(models("default-key")).contains("broken-v1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void otherBucketsAndFoldersHoldNoPlatformAlias() {
        assertEquals(200, putAlias("plt-v1", MODEL_ALIAS).status());
        reload();
        AtomicInteger calls = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", request -> {
                calls.incrementAndGet();
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });

            assertEquals(404, chat("aliases/" + bucket + "/plt-v1", "default-key").status());
            assertEquals(404, chat("aliases/platform/folder/plt-v1", "default-key").status());
            for (String id : List.of("aliases/platform/plt-v1", "platform/plt-v1")) {
                Response response = chat(id, "default-key");
                assertNotEquals(200, response.status(), id + ": " + response.body());
            }
        }
        assertEquals(0, calls.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void aliasesCannotBeSharedCopiedOrMoved() {
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

    private void reload() {
        Response response = send(HttpMethod.POST, "/v1/ops/config/reload", null, null, "Authorization", "admin");
        assertEquals(200, response.status(), response.body());
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
