package com.epam.aidial.core.server;

import com.epam.aidial.core.server.log.AnalyticsLogContext;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A caller matching {@code analytics.securedSubject} has its analytics rows written without bodies (spec: analytics-log,
 * "Secured callers"). The rows are captured before the log writer drops the bodies of a secured row, so the tests
 * assert the row's flag; GfLogStoreTest covers what the writer does with it. The config's subject is: role
 * {@code legal}, OR claim {@code user_name} equal to {@code test-user}, OR an entry of claim {@code groups} containing
 * {@code secure}, OR claim {@code c-level-management} equal to {@code "true"}.
 */
class SecuredSubjectAnalyticsLogApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/secured-subject.json";
    private static final String MODEL_PATH = "/openai/deployments/m/chat/completions";
    private static final String APP_PATH = "/openai/deployments/app/chat/completions";
    private static final String SETTINGS_URL = "/v1/settings/platform/global";
    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String CHAT_ANSWER = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    @Test
    @DialConfigLocation(CONFIG)
    void testRoleRule() {
        assertTrue(callModel("authorization", "roles:legal").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testNoRuleMatches() {
        assertFalse(callModel("authorization", "roles:dev").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testClaimRuleIgnoresCase() {
        assertTrue(callModel("authorization", "claims:{\"user_name\":\"TEST-USER\"}").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testClaimRuleOtherValue() {
        assertFalse(callModel("authorization", "claims:{\"user_name\":\"someone\"}").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testContainRuleOnArrayClaim() {
        assertTrue(callModel("authorization", "claims:{\"groups\":[\"dev\",\"Team-Secure-Ops\"]}").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testContainRuleNoEntryContainsTheTarget() {
        assertFalse(callModel("authorization", "claims:{\"groups\":[\"dev\",\"ops\"]}").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testOneRuleOfSeveral() {
        assertTrue(callModel("authorization", "roles:dev,legal").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testStringClaimTrue() {
        assertTrue(callModel("authorization", "claims:{\"c-level-management\":\"true\"}").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBooleanClaimNotMatched() {
        assertFalse(callModel("authorization", "claims:{\"c-level-management\":true}").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApiKeyWithMatchingRole() {
        assertTrue(callModel("api-key", "legalKey").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApiKeyWithoutMatchingRole() {
        assertFalse(callModel("api-key", "devKey").isSecured());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApplicationCalledBySecuredUser() {
        AtomicReference<Response> inner = new AtomicReference<>();
        try (TestWebServer server = upstream()) {
            server.map(HttpMethod.POST, APP_PATH, request -> {
                inner.set(send(HttpMethod.POST, MODEL_PATH, null, CHAT_BODY,
                        "content-type", "application/json", "api-key", request.getHeader("Api-Key")));
                return TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json");
            });

            verify(send(HttpMethod.POST, APP_PATH, null, CHAT_BODY,
                    "content-type", "application/json", "authorization", "roles:legal"), 200);
            verify(inner.get(), 200);

            assertTrue(awaitRows("app", 1).getFirst().isSecured());
            assertTrue(awaitRows("m", 1).getFirst().isSecured());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSettingsBlobReplacesTheSubject() {
        try (TestWebServer server = upstream()) {
            // the blob has no analytics, so its subject is empty: nobody is secured
            verify(send(HttpMethod.PUT, SETTINGS_URL, null, "{}", "authorization", "admin"), 200);
            verify(postChat("authorization", "roles:legal"), 200);
            assertFalse(awaitRows("m", 1).getFirst().isSecured());

            // deleting the blob restores the file's subject
            verify(send(HttpMethod.DELETE, SETTINGS_URL, null, "", "authorization", "admin"), 204);
            verify(postChat("authorization", "roles:legal"), 200);
            assertTrue(awaitRows("m", 2).get(1).isSecured());
        }
    }

    private AnalyticsLogContext callModel(String header, String value) {
        try (TestWebServer server = upstream()) {
            verify(postChat(header, value), 200);
            return awaitRows("m", 1).getFirst();
        }
    }

    private Response postChat(String header, String value) {
        return send(HttpMethod.POST, MODEL_PATH, null, CHAT_BODY, "content-type", "application/json", header, value);
    }

    private List<AnalyticsLogContext> awaitRows(String deployment, int count) {
        awaitCondition(() -> rowsOf(deployment).size() >= count);
        return rowsOf(deployment);
    }

    private List<AnalyticsLogContext> rowsOf(String deployment) {
        return List.copyOf(analyticsRows).stream()
                .filter(row -> deployment.equals(row.getDeploymentName()))
                .toList();
    }

    private static TestWebServer upstream() {
        TestWebServer server = new TestWebServer(4848);
        server.map(HttpMethod.POST, MODEL_PATH,
                request -> TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json"));
        return server;
    }
}
