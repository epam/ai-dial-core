package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aliases defined in the config: calling the target through the alias, the call chain it records, and listings.
 */
class AliasApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/aliases.json";

    private static final String CHAT_REQUEST = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String CHAT_RESPONSE = "{\"id\":\"cmpl_1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    private static final String RESPONSES_BODY = "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":[],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String MESSAGES_BODY = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String STREAM_REQUEST = "{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String CHAT_STREAM = "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hel\"}}]}\n\n"
            + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\n"
            + "data: [DONE]\n\n";

    @Test
    @DialConfigLocation(CONFIG)
    void callerWithoutRightsOnTheTargetCallsTheAlias() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            Response viaAlias = chat("assistant", "default-key");
            assertEquals(200, viaAlias.status(), viaAlias.body());
            assertEquals("hello", new JsonObject(viaAlias.body()).getJsonArray("choices").getJsonObject(0)
                    .getJsonObject("message").getString("content"));

            Response direct = chat("gpt-4-internal", "default-key");
            assertEquals(403, direct.status(), direct.body());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void aliasDefaultHeaderReachesTheTarget() {
        AtomicReference<String> team = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", request -> {
                team.set(request.getHeader("X-Team"));
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });
            Response response = chat("assistant", "default-key");
            assertEquals(200, response.status(), response.body());
        }
        assertEquals("a", team.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void callerWithoutTheAliasRoleIsRefused() {
        Response response = chat("assistant", "power-key");
        assertEquals(403, response.status(), response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void aliasWithoutUserRolesTakesTheTargetRoles() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            assertEquals(403, chat("inherited-alias", "default-key").status());
            Response allowed = chat("inherited-alias", "power-key");
            assertEquals(200, allowed.status(), allowed.body());
        }

        assertFalse(ids(send(HttpMethod.GET, "/openai/models", null, "", "api-key", "default-key")).contains("inherited-alias"));
        assertTrue(ids(send(HttpMethod.GET, "/openai/models", null, "", "api-key", "power-key")).contains("inherited-alias"));
        assertEquals(403, send(HttpMethod.GET, "/openai/models/inherited-alias", null, "", "api-key", "default-key").status());
        Response names = send(HttpMethod.GET, "/v1/deployment-names", null, null, "api-key", "default-key");
        assertFalse(names.body().contains("\"inherited-alias\""), names.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void callerSignsInToTheToolsetThroughTheAlias() {
        // the caller has no rights on the target toolset itself
        verify(send(HttpMethod.POST, "/v1/ops/toolset/signin", null, signIn("secured-search", "Bearer user-secret"),
                "api-key", "default-key"), 403);
        verify(send(HttpMethod.POST, "/v1/ops/toolset/signin", null, signIn("secured-tools", "Bearer user-secret"),
                "api-key", "default-key"), 200, "true");

        Response toolsets = send(HttpMethod.GET, "/openai/toolsets", null, "", "api-key", "default-key");
        JsonObject entry = find(toolsets, "secured-tools");
        JsonObject authSettings = entry.getJsonObject("auth_settings");
        assertEquals("API_KEY", authSettings.getString("authentication_type"), entry.encode());
        assertEquals("SIGNED_IN", authSettings.getString("user_level_auth_status"), entry.encode());

        AtomicReference<String> authorization = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/secured/mcp", request -> {
                authorization.set(request.getHeader("Authorization"));
                return TestWebServer.createResponse(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}",
                        "Content-Type", "application/json");
            });
            Response response = send(HttpMethod.POST, "/v1/toolset/secured-tools/mcp", null,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, response.status(), response.body());
        }
        assertEquals("Bearer user-secret", authorization.get());

        verify(send(HttpMethod.POST, "/v1/ops/toolset/signout", null, """
                {"url": "secured-tools", "credentialsLevel": "USER", "authenticationType": "API_KEY"}
                """, "api-key", "default-key"), 200, "true");
        // organization-level credentials stay the admin's to set
        verify(send(HttpMethod.POST, "/v1/ops/toolset/signin", null,
                signIn("secured-tools", "Bearer org-secret").replace("\"USER\"", "\"GLOBAL\""), "api-key", "default-key"), 403);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void targetPassCarriesTheCallChain() {
        AtomicReference<ApiKeyData> targetKey = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", request -> {
                targetKey.set(readKey(request.getHeader("api-key")));
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });

            Response response = chat("assistant", "default-key");
            assertEquals(200, response.status(), response.body());
        }
        assertEquals(List.of("assistant", "gpt-4-internal"), targetKey.get().getExecutionPath());
        // the key the target pass hands on lends the target itself only, for its own callbacks such as a translator's
        assertEquals("gpt-4-internal", targetKey.get().getLentDeployment());
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void limitIsCheckedUnderTheTargetName() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            // the first call is under the limit and charges 2 tokens against a limit of 1
            assertEquals(200, chat("assistant", "limited-key").status());
            Thread.sleep(1000); // usage is saved after the answer
            Response second = chat("assistant", "limited-key");
            assertEquals(429, second.status(), second.body());
            // the limit of one caller is not remembered against the alias
            assertEquals(200, chat("assistant", "default-key").status());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void targetClosedToPerRequestKeysStillServesTheAlias() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/closed/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            Response response = chat("closed-alias", "default-key");
            assertEquals(200, response.status(), response.body());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void keyHandedOnByTheTargetDoesNotCarryTheLending() {
        AtomicInteger nestedStatus = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/relay/chat/completions", request -> {
                nestedStatus.set(chat("gpt-4-internal", request.getHeader("api-key")).status());
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });

            Response response = chat("relay-alias", "default-key");
            assertEquals(200, response.status(), response.body());
        }
        assertEquals(403, nestedStatus.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void responsesAndMessagesNameTheTargetInTheBody() {
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
                    "{\"model\":\"multi-alias\",\"store\":false,\"input\":\"hello\"}",
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, responses.status(), responses.body());

            Response messages = send(HttpMethod.POST, "/anthropic/v1/messages", null,
                    "{\"model\":\"multi-alias\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, messages.status(), messages.body());
        }
        assertEquals("multi-model", responsesModel.get());
        assertEquals("multi-model", messagesModel.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void storedResponseIsReadBackThroughTheAlias() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/v1/responses", 200, RESPONSES_BODY, "Content-Type", "application/json");
            server.map(HttpMethod.GET, "/openai/v1/responses/resp_1", 200, RESPONSES_BODY, "Content-Type", "application/json");

            Response created = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"multi-alias\",\"input\":\"hello\"}",
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, created.status(), created.body());
            String id = new JsonObject(created.body()).getString("id");

            Response read = send(HttpMethod.GET, "/openai/v1/responses/" + id, null, null, "api-key", "default-key");
            assertEquals(200, read.status(), read.body());
            assertEquals(id, new JsonObject(read.body()).getString("id"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void featureRequestsReachTheTargetThroughTheAlias() {
        AtomicReference<String> rating = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/featured/tokenize", 200, "{\"outputs\":[]}", "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/featured/rate", request -> {
                rating.set(request.getBody().readUtf8());
                return TestWebServer.createResponse(200, "", "Content-Type", "application/json");
            });

            Response tokenize = send(HttpMethod.POST, "/v1/deployments/featured-alias/tokenize", null, "{\"inputs\":[]}",
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, tokenize.status(), tokenize.body());

            Response rate = send(HttpMethod.POST, "/v1/featured-alias/rate", null, "{\"responseId\":\"r1\",\"rate\":true}",
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, rate.status(), rate.body());
        }
        assertTrue(rating.get() != null && rating.get().contains("r1"), String.valueOf(rating.get()));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void consentToTheAliasCoversItsTarget() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/consent/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            assertEquals(403, chat("consent-alias", "default-key").status());

            Response review = send(HttpMethod.GET, "/v1/consent/consent-alias", null, null, "api-key", "default-key");
            assertEquals(200, review.status(), review.body());
            JsonObject consent = new JsonObject(review.body());
            assertFalse(consent.getBoolean("accepted"));
            JsonObject deployments = consent.getJsonObject("consent").getJsonObject("deployments");
            assertTrue(deployments.getJsonObject("consent-alias").getBoolean("consentRequired"), review.body());
            assertTrue(deployments.getJsonObject("consent-model").getBoolean("consentRequired"), review.body());

            consent.remove("accepted");
            Response accept = send(HttpMethod.POST, "/v1/consent/consent-alias", null, consent.encode(),
                    "api-key", "default-key", "Content-Type", "application/json");
            assertEquals(200, accept.status(), accept.body());

            Response call = chat("consent-alias", "default-key");
            assertEquals(200, call.status(), call.body());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void toolsetAliasReachesTheMcpApplication() {
        AtomicReference<String> mcpBody = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/mcp", request -> {
                mcpBody.set(request.getBody().readUtf8());
                return TestWebServer.createResponse(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}",
                        "Content-Type", "application/json");
            });

            // the fixture's limit of one request a day under the alias's name has no effect
            for (int i = 0; i < 2; i++) {
                Response response = send(HttpMethod.POST, "/v1/toolset/mcp-tools/mcp", null,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                        "api-key", "default-key", "Content-Type", "application/json");
                assertEquals(200, response.status(), response.body());
                assertTrue(response.body().contains("\"tools\""), response.body());
            }
        }
        assertTrue(mcpBody.get().contains("tools/list"), mcpBody.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void chatCompletionToToolsetAliasAnswersAsForToolset() {
        Response viaAlias = chat("search-tools", "default-key");
        Response direct = chat("search", "power-key");
        assertNotEquals(200, viaAlias.status(), viaAlias.body());
        assertEquals(direct.status(), viaAlias.status(), viaAlias.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void aliasWithMissingTargetAnswers503AndIsNotListed() {
        Response response = chat("broken", "default-key");
        assertEquals(503, response.status(), response.body());

        Response models = send(HttpMethod.GET, "/openai/models", null, "", "api-key", "default-key");
        assertFalse(ids(models).contains("broken"), models.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void modelListingShowsTheAliasWithTheTargetPricing() {
        Response response = send(HttpMethod.GET, "/openai/models", null, "", "api-key", "default-key");
        assertEquals(200, response.status(), response.body());

        List<String> ids = ids(response);
        assertTrue(ids.contains("assistant"), response.body());
        assertFalse(ids.contains("gpt-4-internal"), response.body());
        assertFalse(ids.contains("mcp-tools"), response.body());

        JsonObject assistant = find(response, "assistant");
        assertEquals("Assistant", assistant.getString("display_name"));
        assertEquals("0.01", assistant.getJsonObject("pricing").getString("prompt"));
        assertFalse(response.body().contains("localhost:4848"), response.body());
        assertFalse(assistant.encode().contains("gpt-4-internal"), assistant.encode());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void applicationShownAsModel() {
        Response response = send(HttpMethod.GET, "/openai/models/app-as-model", null, "", "api-key", "default-key");
        assertEquals(200, response.status(), response.body());
        assertEquals("model", new JsonObject(response.body()).getString("object"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void toolsetAliasIsListedWithToolsets() {
        Response toolsets = send(HttpMethod.GET, "/openai/toolsets", null, "", "api-key", "default-key");
        assertEquals(200, toolsets.status(), toolsets.body());
        assertTrue(ids(toolsets).contains("mcp-tools"), toolsets.body());
        assertFalse(toolsets.body().contains("localhost:4848"), toolsets.body());

        Response deployments = send(HttpMethod.GET, "/v1/deployments", null, "", "api-key", "default-key");
        assertEquals(200, deployments.status(), deployments.body());
        assertTrue(deployments.body().contains("\"mcp-tools\""), deployments.body());
        assertTrue(deployments.body().contains("\"assistant\""), deployments.body());

        Response info = send(HttpMethod.GET, "/v1/deployments/mcp-tools", null, "", "api-key", "default-key");
        assertEquals(200, info.status(), info.body());
        assertEquals("toolset", new JsonObject(info.body()).getString("object"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void configAliasGrantsResourceTarget() {
        // the fixture's target is in the bucket of proxyKey1
        assertEquals("3R4ac3QT6AZjujjrzHhd8M", bucket);
        verify(send(HttpMethod.PUT, "/v1/applications/" + bucket + "/res-app", null,
                "{\"endpoint\": \"http://localhost:4848/res-app/chat/completions\"}"), 200);

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/res-app/chat/completions", 200, CHAT_RESPONSE, "Content-Type", "application/json");

            Response response = chat("resource-alias", "default-key");
            assertEquals(200, response.status(), response.body());
        }
        verify(send(HttpMethod.GET, "/v1/applications/" + bucket + "/res-app", null, null, "api-key", "default-key"), 403);

        Response applications = send(HttpMethod.GET, "/openai/applications", null, "", "api-key", "default-key");
        assertEquals(200, applications.status(), applications.body());
        assertTrue(ids(applications).contains("resource-alias"), applications.body());
        assertFalse(applications.body().contains("res-app"), applications.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void interceptorOfTheTargetKeepsTheLending() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/interceptor", request -> {
                Response next = send(HttpMethod.POST, "/openai/deployments/interceptor/chat/completions", null,
                        request.getBody().readUtf8(), "api-key", request.getHeader("api-key"), "Content-Type", "application/json");
                return TestWebServer.createResponse(next.status(), next.body(), "Content-Type", "application/json");
            });
            server.map(HttpMethod.POST, "/openai/deployments/intercepted/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");

            Response response = chat("intercepted-alias", "default-key");
            assertEquals(200, response.status(), response.body());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void deploymentNamesLeaveOutBrokenAliases() {
        Response names = send(HttpMethod.GET, "/v1/deployment-names", null, null, "api-key", "default-key");
        assertEquals(200, names.status(), names.body());
        assertTrue(names.body().contains("\"assistant\""), names.body());
        assertFalse(names.body().contains("\"broken\""), names.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void aliasWithoutPresentAsTakesTheTargetTypeAndDisplayFields() {
        Response models = send(HttpMethod.GET, "/openai/models", null, "", "api-key", "default-key");
        assertEquals(200, models.status(), models.body());
        JsonObject plain = find(models, "plain-alias-v1");
        assertEquals("model", plain.getString("object"));
        assertEquals("GPT-4 internal", plain.getString("display_name"));
        assertEquals("The internal GPT-4 deployment.", plain.getString("description"));
        assertEquals("http://icons/gpt-4.svg", plain.getString("icon_url"));
        // the alias has a version of its own, so the target's does not show as the alias's
        assertFalse(plain.containsKey("display_version"), plain.encode());
        // provider and lifecycle dates describe the model itself, so the alias shows the target's
        assertEquals("Azure", plain.getString("provider"));
        assertEquals("2027-06-30", plain.getString("deprecation_date"));
        assertEquals("2027-12-31", plain.getString("end_of_service"));

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            Response response = chat("plain-alias-v1", "default-key");
            assertEquals(200, response.status(), response.body());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void healthReportsAliasWithMissingTarget() {
        Response health = send(HttpMethod.GET, "/v1/admin/health/config", null, null, "Authorization", "admin");
        assertEquals(200, health.status(), health.body());
        JsonObject body = new JsonObject(health.body());
        assertEquals("degraded", body.getString("status"));
        assertTrue(health.body().contains("\"aliases/broken\""), health.body());
        assertTrue(health.body().contains("alias_target_missing"), health.body());
        // a resource target is read for the report
        assertTrue(health.body().contains("\"aliases/gone-resource-alias\""), health.body());
        assertFalse(health.body().contains("aliases/assistant"), health.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void fileConfigListsTheAliases() {
        Response list = send(HttpMethod.GET, "/v1/admin/config/file/aliases", null, null, "Authorization", "admin");
        assertEquals(200, list.status(), list.body());
        assertTrue(list.body().contains("\"assistant\""), list.body());

        Response one = send(HttpMethod.GET, "/v1/admin/config/file/aliases/assistant", null, null, "Authorization", "admin");
        assertEquals(200, one.status(), one.body());
        assertTrue(one.body().contains("models/gpt-4-internal"), one.body());

        verify(send(HttpMethod.GET, "/v1/admin/config/file/aliases", null, null, "api-key", "default-key"), 403);
    }

    // deployment-aliases "Target pass served in process", scenario "Call chain unchanged"

    @Test
    @DialConfigLocation(CONFIG)
    void targetRowNamesTheAliasAsItsParent() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            verify(chat("assistant", "default-key"), 200);
        }
        List<AnalyticsLogContext> rows = awaitAnalyticsRows(2);
        assertEquals(2, rows.size());
        rowOf(rows, "assistant");
        AnalyticsLogContext target = rowOf(rows, "gpt-4-internal");
        assertEquals("assistant", target.getParentDeployment());
        assertEquals(List.of("assistant", "gpt-4-internal"), target.getExecutionPath());
    }

    // deployment-aliases "Headers of an in-process alias call"

    @Test
    @DialConfigLocation(CONFIG)
    void targetOriginGetsNoOverrideNameFromTheAliasPass() {
        AtomicReference<RecordedRequest> seen = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", request -> {
                seen.set(request);
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });
            verify(chat("assistant", "default-key"), 200);
        }
        assertNull(seen.get().getHeader("X-DIAL-OVERRIDE-NAME"));
        assertEquals("gpt-4-internal", seen.get().getHeader("X-DIAL-DEPLOYMENT-ID"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void answerThroughAnAliasCarriesCorsHeaderOnce() {
        HttpResponse<String> response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_RESPONSE,
                    "Content-Type", "application/json");
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/openai/deployments/assistant/chat/completions"))
                    .header("api-key", "default-key").header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(CHAT_REQUEST))
                    .build();
            // a client that keeps every header, so a duplicate shows
            response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(request, HttpResponse.BodyHandlers.ofString());
        }
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, response.headers().allValues("access-control-allow-origin").size(), response.headers().map().toString());
    }

    // deployment-aliases "Attachments shared on through an alias"

    @Test
    @DialConfigLocation(CONFIG)
    void applicationCallingAnAliasMayReadTheFileTheTargetShares() {
        AtomicReference<String> fileUrl = new AtomicReference<>();
        AtomicInteger uploaded = new AtomicInteger();
        AtomicInteger called = new AtomicInteger();
        AtomicInteger read = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848)) {
            // the model's origin stores a file in the bucket of the key it got, and attaches it to its answer
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", request -> {
                String key = request.getHeader("api-key");
                String bucket = new JsonObject(send(HttpMethod.GET, "/v1/bucket", null, "", "api-key", key).body()).getString("bucket");
                String url = "files/" + bucket + "/generated.txt";
                uploaded.set(upload(HttpMethod.PUT, "/v1/" + url, null, "generated", "api-key", key).status());
                fileUrl.set(url);
                return TestWebServer.createResponse(200, chatWithAttachment(url), "Content-Type", "application/json");
            });
            // the application calls the alias with its own key, then reads the attached file with it
            server.map(HttpMethod.POST, "/relay/chat/completions", request -> {
                String appKey = request.getHeader("api-key");
                called.set(chat("assistant", appKey).status());
                read.set(send(HttpMethod.GET, "/v1/" + fileUrl.get(), null, null, "api-key", appKey).status());
                return TestWebServer.createResponse(200, CHAT_RESPONSE, "Content-Type", "application/json");
            });
            verify(chat("relay-alias", "default-key"), 200);
        }
        assertEquals(200, uploaded.get());
        assertEquals(200, called.get());
        assertEquals(200, read.get());
    }

    // analytics-log "Rows without bodies for an alias pass"

    @Test
    @DialConfigLocation(CONFIG)
    void messagesThroughAnAliasLogBodiesInTheTargetRowOnly() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/anthropic/v1/messages", 200, MESSAGES_BODY, "Content-Type", "application/json");
            verify(send(HttpMethod.POST, "/anthropic/v1/messages", null,
                    "{\"model\":\"multi-alias\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                    "api-key", "default-key", "Content-Type", "application/json"), 200);
        }
        List<AnalyticsLogContext> rows = awaitAnalyticsRows(2);
        AnalyticsLogContext alias = rowOf(rows, "multi-alias");
        assertNull(alias.getRequestBody());
        assertNull(alias.getResponseBody());
        assertNull(alias.getAssembledStreamingResponse());
        assertTrue(alias.isBodiesOmitted());
        assertNotNull(alias.getTraceId());
        AnalyticsLogContext target = rowOf(rows, "multi-model");
        assertEquals("multi-model", new JsonObject(target.getRequestBody().toString()).getString("model"));
        assertTrue(target.getResponseBody().toString().contains("msg_1"), String.valueOf(target.getResponseBody()));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void streamedChatThroughAnAliasAssemblesInTheTargetRowOnly() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions", 200, CHAT_STREAM,
                    "Content-Type", "text/event-stream");
            verify(send(HttpMethod.POST, "/openai/deployments/assistant/chat/completions", null, STREAM_REQUEST,
                    "api-key", "default-key", "Content-Type", "application/json"), 200);
        }
        List<AnalyticsLogContext> rows = awaitAnalyticsRows(2);
        assertNull(rowOf(rows, "assistant").getAssembledStreamingResponse());
        assertNotNull(rowOf(rows, "gpt-4-internal").getAssembledStreamingResponse());
    }

    // Messages count_tokens through an alias keeps going over HTTP to Core's own address

    @Test
    @DialConfigLocation(CONFIG)
    void countTokensThroughAnAlias() {
        AtomicReference<String> path = new AtomicReference<>();
        Response response;
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/anthropic/v1/messages/count_tokens", request -> {
                path.set(request.getPath());
                return TestWebServer.createResponse(200, "{\"input_tokens\":3}", "Content-Type", "application/json");
            });
            response = send(HttpMethod.POST, "/anthropic/v1/messages/count_tokens", null,
                    "{\"model\":\"multi-alias\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                    "api-key", "default-key", "Content-Type", "application/json");
        }
        verify(response, 200);
        assertEquals("/anthropic/v1/messages/count_tokens", path.get());
    }

    // deployment-origin "Client gone during the relay", through an alias whose target pass runs in process

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void clientGoneMidStreamThroughAnAliasStillChargesTheTarget() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4/chat/completions",
                    TestWebServer.createResponse(200, CHAT_STREAM, "Content-Type", "text/event-stream")
                            .throttleBody(16, 100, TimeUnit.MILLISECONDS));
            byte[] body = STREAM_REQUEST.getBytes(StandardCharsets.UTF_8);
            try (Socket socket = new Socket("localhost", serverPort)) {
                OutputStream out = socket.getOutputStream();
                out.write(("POST /openai/deployments/assistant/chat/completions HTTP/1.1\r\nHost: localhost\r\n"
                        + "api-key: default-key\r\ncontent-type: application/json\r\ncontent-length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
                // the answer has started; the client leaves before it ends
                assertTrue(socket.getInputStream().read() >= 0);
            }
            // the model's stream is read to its end and charged, as on a direct call
            awaitCondition(() -> analyticsRows.stream().anyMatch(row -> "gpt-4-internal".equals(row.getDeploymentName())
                    && row.getTokenUsage() != null && row.getTokenUsage().getTotalTokens() == 2));
        }
    }

    private static String chatWithAttachment(String url) {
        return "{\"id\":\"cmpl_2\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"made\",\"custom_content\":{\"attachments\":"
                + "[{\"type\":\"text/plain\",\"url\":\"" + url + "\"}]}}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    private static AnalyticsLogContext rowOf(List<AnalyticsLogContext> rows, String deployment) {
        return rows.stream().filter(row -> deployment.equals(row.getDeploymentName())).findFirst()
                .orElseThrow(() -> new AssertionError("no row of " + deployment + ": "
                        + rows.stream().map(AnalyticsLogContext::getDeploymentName).toList()));
    }

    private static String signIn(String url, String apiKey) {
        return """
                {"url": "%s", "credentialsLevel": "USER", "authenticationType": "API_KEY", "api_key": "%s"}
                """.formatted(url, apiKey);
    }

    private Response chat(String deployment, String apiKey) {
        return send(HttpMethod.POST, "/openai/deployments/" + deployment + "/chat/completions", null, CHAT_REQUEST,
                "api-key", apiKey, "Content-Type", "application/json");
    }

    @SneakyThrows
    private ApiKeyData readKey(String key) {
        return apiKeyStore.getApiKeyData(key, null).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static List<String> ids(Response response) {
        JsonArray data = new JsonObject(response.body()).getJsonArray("data");
        return data.stream().map(item -> ((JsonObject) item).getString("id")).toList();
    }

    private static JsonObject find(Response response, String id) {
        JsonArray data = new JsonObject(response.body()).getJsonArray("data");
        return data.stream().map(JsonObject.class::cast).filter(item -> id.equals(item.getString("id")))
                .findFirst().orElseThrow();
    }
}
