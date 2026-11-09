package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin-managed aliases stored under {@code aliases/public/<name>}: the management API, calling them by their url
 * under their {@code userRoles}, listings, health, and what is refused for them.
 */
class AliasPublicApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/aliases.json";
    private static final String ALIAS_URL = "/v1/aliases/public/";
    private static final String PUB = "aliases/public/pub-v1";

    private static final String CHAT_REQUEST = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String CHAT_RESPONSE = "{\"id\":\"cmpl_1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    private static final String RESPONSES_BODY = "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":[],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String MESSAGES_BODY = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String RESTRICTED_ALIAS = "{\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"models/gpt-4-internal\"}}";
    private static final String MODEL_ALIAS = "{\"displayName\":\"Public assistant\",\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"models/gpt-4-internal\"}}";

    @Test
    @DialConfigLocation(CONFIG)
    void adminCreatesReadsUpdatesAndDeletesAnAlias() {
        Response created = putAlias("pub-v1", MODEL_ALIAS, "If-None-Match", "*");
        assertEquals(200, created.status(), created.body());
        String etag = created.headers().get("etag");
        assertNotNull(etag);
        assertEquals(412, putAlias("pub-v1", MODEL_ALIAS, "If-None-Match", "*").status());

        Response read = admin(HttpMethod.GET, ALIAS_URL + "pub-v1", null);
        assertEquals(200, read.status(), read.body());
        JsonObject body = new JsonObject(read.body());
        assertEquals("models/gpt-4-internal", body.getJsonObject("target").getString("path"));
        assertEquals("Public assistant", body.getString("displayName"));
        assertEquals("pub-v1", body.getString("name"));
        assertFalse(body.containsKey("forwardAuthToken"), read.body());

        Response updated = putAlias("pub-v1", "{\"displayName\":\"Renamed\",\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"models/gpt-4-internal\"}}",
                "If-Match", etag);
        assertEquals(200, updated.status(), updated.body());
        assertEquals("Renamed", new JsonObject(admin(HttpMethod.GET, ALIAS_URL + "pub-v1", null).body()).getString("displayName"));

        Response listing = admin(HttpMethod.GET, "/v1/metadata/aliases/public/", null);
        assertEquals(200, listing.status(), listing.body());
        assertTrue(listing.body().contains(PUB), listing.body());

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            assertEquals(200, chat(PUB, "default-key").status());

            assertEquals(204, admin(HttpMethod.DELETE, ALIAS_URL + "pub-v1", null).status());
            assertEquals(404, admin(HttpMethod.GET, ALIAS_URL + "pub-v1", null).status());
            assertEquals(404, chat(PUB, "default-key").status());
        }
        assertFalse(ids(models("default-key")).contains(PUB));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void onlyAdminsManageAliases() {
        assertEquals(200, putAlias("pub-v1", MODEL_ALIAS).status());

        for (String[] caller : List.of(new String[] {"authorization", "user"}, new String[] {"api-key", "default-key"})) {
            assertEquals(403, send(HttpMethod.PUT, ALIAS_URL + "other-v1", null, MODEL_ALIAS, caller).status());
            assertEquals(403, send(HttpMethod.GET, ALIAS_URL + "pub-v1", null, null, caller).status());
            assertEquals(403, send(HttpMethod.DELETE, ALIAS_URL + "pub-v1", null, null, caller).status());
            assertEquals(403, send(HttpMethod.GET, "/v1/metadata/aliases/public/", null, null, caller).status());
        }
        assertEquals(200, admin(HttpMethod.GET, ALIAS_URL + "pub-v1", null).status());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void aliasesLiveDirectlyInThePublicBucket() {
        assertEquals(404, admin(HttpMethod.PUT, "/v1/aliases/" + bucket + "/pub-v1", MODEL_ALIAS).status());
        assertEquals(404, admin(HttpMethod.PUT, "/v1/aliases/platform/pub-v1", MODEL_ALIAS).status());
        assertEquals(400, putAlias("folder/pub-v1", MODEL_ALIAS).status());
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
        assertBadRequest("{\"presentAs\":\"widget\",\"target\":{\"path\":\"models/gpt-4-internal\"}}");
        assertBadRequest("{\"displayName\":\"No target\"}");
        assertBadRequest("{\"name\":\"other-v1\",\"target\":{\"path\":\"models/gpt-4-internal\"}}");

        // a resource target that exists must fit too: an application without mcp is not a toolset
        verify(send(HttpMethod.PUT, "/v1/applications/public/res-app", null,
                "{\"endpoint\": \"http://localhost:4848/res-app/chat/completions\"}", "authorization", "admin"), 200);
        assertBadRequest("{\"presentAs\":\"toolset\",\"target\":{\"path\":\"applications/public/res-app\"}}");
        // a resource target must be public: the owner of a user bucket could change it at any time
        assertBadRequest("{\"target\":{\"path\":\"applications/" + bucket + "/res-app\"}}");
        assertBadRequest("{\"target\":{\"path\":\"toolsets/" + bucket + "/tools\"}}");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void listedIdIsTheIdThatIsCalled() {
        // the url encoding changes these names, so their listed id could not be called back
        assertEquals(400, putAlias("x%5B1%5D", MODEL_ALIAS).status());
        assertEquals(400, putAlias("x%25", MODEL_ALIAS).status());
        assertEquals(400, putAlias("pub@v1", MODEL_ALIAS).status());

        // letters, digits, dot, underscore and dash survive it: the listed id is called as is
        assertEquals(200, putAlias("Pub.v1_2-x", MODEL_ALIAS).status());
        String id = "aliases/public/Pub.v1_2-x";
        assertTrue(ids(models("default-key")).contains(id), models("default-key").body());
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            assertEquals(200, chat(id, "default-key").status());
            Response viaBody = send(HttpMethod.POST, "/openai/v1/chat/completions", null,
                    "{\"model\":\"" + id + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, viaBody.status(), viaBody.body());
        }
        assertEquals(200, send(HttpMethod.GET, "/openai/models/" + id, null, "", "api-key", "default-key").status());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void storedBlobThatCallsIgnoreIsShownInvalid() {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(ResourceTypes.ALIAS,
                ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, "hand-v1");
        dial.getProxy().getResourceService().putResource(descriptor, "{\"displayName\":\"no target\"}", EtagHeader.ANY, "test", false);

        Response read = admin(HttpMethod.GET, ALIAS_URL + "hand-v1", null);
        assertEquals(200, read.status(), read.body());
        JsonObject body = new JsonObject(read.body());
        assertEquals("invalid", body.getString("status"));
        assertEquals("no target", body.getString("displayName"));
        assertTrue(body.getJsonArray("validationWarnings").encode().contains("target.path"), read.body());

        assertEquals(404, chat("aliases/public/hand-v1", "default-key").status());
        assertFalse(ids(models("default-key")).contains("aliases/public/hand-v1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void callerWithoutRightsOnTheTargetCallsTheAliasByItsUrl() {
        assertEquals(200, putAlias("pub-v1", MODEL_ALIAS).status());
        AtomicReference<ApiKeyData> targetKey = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", request -> {
                targetKey.set(readKey(request.getHeader("api-key")));
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });

            Response viaAlias = chat(PUB, "default-key");
            assertEquals(200, viaAlias.status(), viaAlias.body());
            assertEquals(403, chat("gpt-4-internal", "default-key").status());
        }
        assertEquals(List.of(PUB, "gpt-4-internal"), targetKey.get().getExecutionPath());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void shortNameDoesNotReachStoredAlias() {
        assertEquals(200, putAlias("pub-v1", MODEL_ALIAS).status());
        // a stored alias named like the config alias assistant: the short name is the config alias's
        assertEquals(200, putAlias("assistant", "{\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"models/multi-model\"}}").status());
        AtomicInteger gpt4 = new AtomicInteger();
        AtomicInteger multi = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", request -> {
                gpt4.incrementAndGet();
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                multi.incrementAndGet();
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });

            assertEquals(404, chat("pub-v1", "default-key").status());
            assertEquals(0, gpt4.get());

            assertEquals(200, chat("assistant", "default-key").status());
            assertEquals(1, gpt4.get());
            assertEquals(0, multi.get());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void userRolesDecideWhoMayCallStoredAlias() {
        Response created = putAlias("pub-v1", RESTRICTED_ALIAS);
        assertEquals(200, created.status(), created.body());
        assertEquals(List.of("default-user"),
                new JsonObject(admin(HttpMethod.GET, ALIAS_URL + "pub-v1", null).body()).getJsonArray("userRoles").getList());

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            assertEquals(200, chat(PUB, "default-key").status());
            // power-user may call the target directly, but has no role on the alias
            assertEquals(403, chat(PUB, "power-key").status());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void userRolesDecideWhereStoredAliasIsListed() {
        assertEquals(200, putAlias("pub-v1", RESTRICTED_ALIAS).status());

        assertTrue(ids(models("default-key")).contains(PUB));
        assertFalse(ids(models("power-key")).contains(PUB));
        assertEquals(200, send(HttpMethod.GET, "/openai/models/" + PUB, null, "", "api-key", "default-key").status());
        assertEquals(403, send(HttpMethod.GET, "/openai/models/" + PUB, null, "", "api-key", "power-key").status());
        assertEquals(403, send(HttpMethod.GET, "/openai/deployments/" + PUB, null, "", "api-key", "power-key").status());
        assertEquals(403, send(HttpMethod.GET, "/v1/deployments/" + PUB, null, "", "api-key", "power-key").status());
        Response names = send(HttpMethod.GET, "/v1/deployment-names", null, null, "api-key", "power-key");
        assertFalse(names.body().contains("\"" + PUB + "\""), names.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void storedAliasWithoutUserRolesTakesTheTargetRoles() {
        assertEquals(200, putAlias("open-v1", "{\"target\":{\"path\":\"models/gpt-4-internal\"}}").status());

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            // gpt-4-internal is for power-user only
            assertEquals(403, chat("aliases/public/open-v1", "default-key").status());
            assertEquals(200, chat("aliases/public/open-v1", "power-key").status());
        }
        assertTrue(ids(models("power-key")).contains("aliases/public/open-v1"));
        assertFalse(ids(models("default-key")).contains("aliases/public/open-v1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void responsesAndMessagesTakeTheUrlAsModel() {
        assertEquals(200, putAlias("multi-v1", "{\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"models/multi-model\"}}").status());
        AtomicReference<String> responsesModel = new AtomicReference<>();
        AtomicReference<String> messagesModel = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/v1/responses", request -> {
                responsesModel.set(new JsonObject(request.getBody().readUtf8()).getString("model"));
                return TestWebServer.createResponse(200, RESPONSES_BODY, "Content-Type", "application/json");
            });
            server.map(HttpMethod.POST, "/anthropic/v1/messages", request -> {
                messagesModel.set(new JsonObject(request.getBody().readUtf8()).getString("model"));
                return TestWebServer.createResponse(200, MESSAGES_BODY, "Content-Type", "application/json");
            });

            Response responses = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"aliases/public/multi-v1\",\"store\":false,\"input\":\"hello\"}",
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, responses.status(), responses.body());

            Response messages = send(HttpMethod.POST, "/anthropic/v1/messages", null,
                    "{\"model\":\"aliases/public/multi-v1\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, messages.status(), messages.body());
        }
        assertEquals("multi-model", responsesModel.get());
        assertEquals("multi-model", messagesModel.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void toolsetAliasIsCalledByItsUrlOverMcp() {
        assertEquals(200, putAlias("tools-v1", "{\"userRoles\":[\"default-user\"],\"presentAs\":\"toolset\",\"target\":{\"path\":\"applications/mcp-app\"}}").status());
        AtomicReference<String> mcpBody = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/mcp", request -> {
                mcpBody.set(request.getBody().readUtf8());
                return TestWebServer.createResponse(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}",
                        "Content-Type", "application/json");
            });

            Response response = send(HttpMethod.POST, "/v1/toolset/aliases/public/tools-v1/mcp", null,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, response.status(), response.body());
        }
        assertTrue(mcpBody.get().contains("tools/list"), mcpBody.get());

        Response toolsets = send(HttpMethod.GET, "/openai/toolsets", null, "", "api-key", "default-key");
        assertTrue(ids(toolsets).contains("aliases/public/tools-v1"), toolsets.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void consentToTheAliasShowsTheResourcesItsTargetDeclares() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/declaring-app", null, """
                {
                  "endpoint": "http://localhost:4848/declaring-app/chat/completions",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_files", "target": {"url": "files/{current-user}/reports/"}, "access": ["read"]}
                  ]
                }
                """, "authorization", "admin"), 200);
        assertEquals(200, putAlias("declaring-v1",
                "{\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"applications/public/declaring-app\"}}").status());

        Response review = send(HttpMethod.GET, "/v1/consent/aliases/public/declaring-v1", null, null, "api-key", "default-key");
        assertEquals(200, review.status(), review.body());
        JsonObject consent = new JsonObject(review.body());
        assertFalse(consent.getBoolean("accepted"), review.body());
        JsonArray resources = consent.getJsonObject("consent").getJsonArray("resources");
        assertNotNull(resources, review.body());
        assertTrue(resources.encode().contains("files/{current-user}/reports/"), review.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void listingsShowTheAliasByItsUrl() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/res-app", null,
                "{\"endpoint\": \"http://localhost:4848/res-app/chat/completions\"}", "authorization", "admin"), 200);
        assertEquals(200, putAlias("pub-v1", MODEL_ALIAS).status());
        assertEquals(200, putAlias("res-model-v1",
                "{\"userRoles\":[\"default-user\"],\"presentAs\":\"model\",\"target\":{\"path\":\"applications/public/res-app\"}}").status());
        String resModel = "aliases/public/res-model-v1";

        Response models = models("default-key");
        assertTrue(ids(models).containsAll(List.of(PUB, resModel, "assistant")), models.body());
        assertFalse(ids(models).contains("pub-v1"), models.body());
        assertFalse(models.body().contains("res-app"), models.body());
        assertFalse(models.body().contains("localhost:4848"), models.body());

        Response one = send(HttpMethod.GET, "/openai/models/" + resModel, null, "", "api-key", "default-key");
        assertEquals(200, one.status(), one.body());
        assertEquals("model", new JsonObject(one.body()).getString("object"));
        assertEquals(resModel, new JsonObject(one.body()).getString("id"));

        Response deployments = send(HttpMethod.GET, "/openai/deployments", null, "", "api-key", "default-key");
        assertTrue(ids(deployments).contains(resModel), deployments.body());
        assertEquals(200, send(HttpMethod.GET, "/openai/deployments/" + PUB, null, "", "api-key", "default-key").status());
        assertEquals(200, send(HttpMethod.GET, "/v1/deployments/" + PUB, null, "", "api-key", "default-key").status());

        Response names = send(HttpMethod.GET, "/v1/deployment-names", null, null, "api-key", "default-key");
        assertTrue(names.body().contains("\"" + PUB + "\""), names.body());
        assertTrue(names.body().contains("\"" + resModel + "\""), names.body());

        Response all = send(HttpMethod.GET, "/v1/deployments", null, null, "api-key", "default-key");
        assertTrue(all.body().contains("\"" + resModel + "\""), all.body());

        // the Anthropic listing shows the model aliases whose target serves the Messages interface
        assertEquals(200, putAlias("messages-v1", "{\"userRoles\":[\"default-user\"],\"target\":{\"path\":\"models/multi-model\"}}").status());
        Response anthropic = send(HttpMethod.GET, "/anthropic/v1/models", null, null, "api-key", "default-key");
        assertTrue(ids(anthropic).contains("aliases/public/messages-v1"), anthropic.body());
        assertFalse(ids(anthropic).contains(PUB), anthropic.body());
        assertEquals(200, send(HttpMethod.GET, "/anthropic/v1/models/aliases/public/messages-v1", null, null,
                "api-key", "default-key").status());
        assertEquals(404, send(HttpMethod.GET, "/anthropic/v1/models/" + PUB, null, null, "api-key", "default-key").status());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void brokenTargetIsAcceptedAndReported() {
        assertEquals(200, putAlias("broken-v1", "{\"target\":{\"path\":\"models/missing\"}}").status());
        assertEquals(200, putAlias("broken-app-v1", "{\"target\":{\"path\":\"applications/public/missing\"}}").status());

        assertEquals(503, chat("aliases/public/broken-v1", "default-key").status());
        Response health = admin(HttpMethod.GET, "/v1/admin/health/config", null);
        assertEquals(200, health.status(), health.body());
        assertTrue(health.body().contains("\"aliases/public/broken-v1\""), health.body());
        // the broken alias of the config file keeps the id of a file alias
        assertTrue(health.body().contains("\"aliases/broken\""), health.body());
        assertFalse(ids(models("default-key")).contains("aliases/public/broken-v1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void otherBucketsAndFoldersHoldNoAlias() {
        assertEquals(200, putAlias("pub-v1", MODEL_ALIAS).status());
        AtomicInteger calls = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", request -> {
                calls.incrementAndGet();
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });

            assertEquals(404, chat("aliases/" + bucket + "/pub-v1", "default-key").status());
            assertEquals(404, chat("aliases/public/folder/pub-v1", "default-key").status());
            for (String id : List.of("public/pub-v1", "models/public/pub-v1", "applications/public/pub-v1")) {
                Response response = chat(id, "default-key");
                assertNotEquals(200, response.status(), id + ": " + response.body());
            }
        }
        assertEquals(0, calls.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void manifestCannotTakeTheNameOfFileAlias() {
        String manifests = """
                {"precheck": false, "manifests": [{"kind": "Model", "name": "models/platform/assistant",
                  "spec": {"type": "chat", "endpoint": "http://localhost:4848/openai/deployments/gpt-4/chat/completions"}}]}
                """;

        Response validate = admin(HttpMethod.POST, "/v1/admin/validate", manifests);
        assertTrue(validate.body().contains("\"FAILED\""), validate.status() + " " + validate.body());
        Response apply = admin(HttpMethod.POST, "/v1/admin/apply", manifests);
        assertTrue(apply.body().contains("\"FAILED\""), apply.status() + " " + apply.body());
        assertTrue(apply.body().contains("already used by a different entity"), apply.body());
        assertEquals(404, admin(HttpMethod.GET, "/v1/models/platform/assistant", null).status());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void aliasesCannotBeSharedPublishedCopiedOrMoved() {
        assertEquals(200, putAlias("pub-v1", MODEL_ALIAS).status());

        Response share = operationRequest("/v1/ops/resource/share/create",
                "{\"invitationType\": \"link\", \"resources\": [{\"url\": \"aliases/public/pub-v1\"}]}", "authorization", "admin");
        assertEquals(400, share.status(), share.body());

        Response publication = operationRequest("/v1/ops/publication/create", """
                {"targetFolder": "public/", "resources": [{"action": "ADD",
                  "sourceUrl": "aliases/%s/pub-v2", "targetUrl": "aliases/public/pub-v2"}]}
                """.formatted(bucket));
        assertEquals(400, publication.status(), publication.body());
        Response unpublish = operationRequest("/v1/ops/publication/create", """
                {"targetFolder": "public/", "resources": [{"action": "DELETE", "targetUrl": "aliases/public/pub-v1"}]}
                """, "authorization", "admin");
        assertEquals(400, unpublish.status(), unpublish.body());

        Response copy = operationRequest("/v1/ops/resource/copy",
                "{\"sourceUrl\": \"aliases/public/pub-v1\", \"destinationUrl\": \"aliases/public/pub-v2\"}", "authorization", "admin");
        assertEquals(400, copy.status(), copy.body());
        Response move = operationRequest("/v1/ops/resource/move",
                "{\"sourceUrl\": \"aliases/public/pub-v1\", \"destinationUrl\": \"aliases/public/pub-v2\"}", "authorization", "admin");
        assertEquals(400, move.status(), move.body());

        assertEquals(404, admin(HttpMethod.GET, ALIAS_URL + "pub-v2", null).status());
        assertEquals(200, admin(HttpMethod.GET, ALIAS_URL + "pub-v1", null).status());
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

    @SneakyThrows
    private ApiKeyData readKey(String key) {
        return apiKeyStore.getApiKeyData(key, null).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static List<String> ids(Response response) {
        JsonArray data = new JsonObject(response.body()).getJsonArray("data");
        return data.stream().map(item -> ((JsonObject) item).getString("id")).toList();
    }
}
