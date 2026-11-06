package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.Rule;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end resolution scenarios: the consented flow, the two failure modes distinguished
 * (required vs optional without consent), content binding on declaration change, an
 * interceptor-protected app, and the audit fields. Scenarios run on the chat-completions path; the
 * consented and required-without-consent flows also on Anthropic Messages and the Responses API.
 */
public class ResourceDependencyApiTest extends ResourceBaseTest {

    private static final String PROMPT_BODY =
            "{\"id\":\"prompt-by-app\",\"folderId\":\"dep-smoke/\",\"name\":\"prompt-by-app\",\"content\":\"app content\"}";

    private String userBucket() {
        Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        return new JsonObject(response.body()).getString("bucket");
    }

    private Response putDeclaringApp(String dependenciesJson) {
        return send(HttpMethod.PUT, "/v1/applications/public/dep-e2e-app", null, """
                {
                  "endpoint": "http://localhost:4849/chat/completions",
                  "interfaces": {
                    "anthropicMessages": {"base_url": "http://localhost:4849"},
                    "openaiResponses": {"base_url": "http://localhost:4849"}
                  },
                  "display_name": "Dependency E2E App",
                  "resource_dependencies": [%s]
                }
                """.formatted(dependenciesJson), "authorization", "admin");
    }

    private Response grantConsent() {
        return send(HttpMethod.POST, "/v1/admin-consent/applications/public/dep-e2e-app", null,
                reviewedResources("applications/public/dep-e2e-app"),
                "authorization", "admin");
    }

    /** A chat completion against the declaring app; the upstream exercises the declared target with the app's key. */
    private Response chat(AtomicReference<Integer> targetStatus, String targetPath) {
        try (TestWebServer server = new TestWebServer(4849)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                String perRequestKey = request.getHeader("Api-Key");
                targetStatus.set(send(HttpMethod.PUT, targetPath, null, PROMPT_BODY,
                        "api-key", perRequestKey).status());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                        "Content-Type", "application/json");
            });
            return send(HttpMethod.POST, "/openai/deployments/applications/public/dep-e2e-app/chat/completions",
                    null, """
                    {"messages":[{"role":"user","content":"how are you?"}]}
                    """, "authorization", "user", "Content-Type", "application/json");
        }
    }

    private static final String REQUIRED_WRITE_PROMPTS = """
            {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": true}
            """;

    private static final String MESSAGES_BODY = """
            {"model": "applications/public/dep-e2e-app", "max_tokens": 10, "messages": [{"role": "user", "content": "hi"}]}""";

    private static final String RESPONSES_BODY = """
            {"model": "applications/public/dep-e2e-app", "input": "hi"}""";

    private static final List<List<String>> MESSAGES_AND_RESPONSES = List.of(
            List.of("/anthropic/v1/messages", MESSAGES_BODY),
            List.of("/openai/v1/responses", RESPONSES_BODY));

    /** A call to the declaring app through {@code ingressPath}; the upstream exercises the declared target with the app's key. */
    private Response call(String ingressPath, String body, AtomicReference<Integer> targetStatus, String targetPath) {
        try (TestWebServer server = new TestWebServer(4849)) {
            server.map(HttpMethod.POST, ingressPath, request -> {
                targetStatus.set(send(HttpMethod.PUT, targetPath, null, PROMPT_BODY, "api-key", request.getHeader("Api-Key")).status());
                return TestWebServer.createResponse(200, "{}", "Content-Type", "application/json");
            });
            return send(HttpMethod.POST, ingressPath, null, body,
                    "authorization", "user", "Content-Type", "application/json", "anthropic-version", "2023-06-01");
        }
    }

    @Test
    void testConsentedFlowOnMessagesAndResponses() {
        verify(putDeclaringApp(REQUIRED_WRITE_PROMPTS), 200);
        verify(grantConsent(), 200);
        String target = "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(userBucket());

        for (List<String> entry : MESSAGES_AND_RESPONSES) {
            AtomicReference<Integer> targetStatus = new AtomicReference<>();
            Response response = call(entry.get(0), entry.get(1), targetStatus, target);
            assertEquals(200, response.status(), () -> entry.get(0) + ": " + response.body());
            assertEquals(200, targetStatus.get(), entry.get(0) + ": the consented target must be writable by the app's key");
        }
    }

    @Test
    void testRequiredDependencyWithoutConsentFailsMessagesAndResponses() {
        verify(putDeclaringApp(REQUIRED_WRITE_PROMPTS), 200);
        String target = "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(userBucket());

        for (List<String> entry : MESSAGES_AND_RESPONSES) {
            AtomicReference<Integer> targetStatus = new AtomicReference<>();
            Response response = call(entry.get(0), entry.get(1), targetStatus, target);
            assertEquals(403, response.status(), () -> entry.get(0) + ": " + response.body());
            assertNull(targetStatus.get(), entry.get(0) + ": the upstream must never be reached when a required dependency fails");
        }
    }

    @Test
    void testRequiredDependencyWithoutConsentFailsTheCall() {
        verify(putDeclaringApp("""
                {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": true}
                """), 200);
        String bucket = userBucket();

        AtomicReference<Integer> targetStatus = new AtomicReference<>();
        Response completion = chat(targetStatus, "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(bucket));

        assertEquals(403, completion.status(), () -> "Body: " + completion.body());
        assertEquals(null, targetStatus.get(), "the upstream must never be reached when a required dependency fails");
    }

    @Test
    void testOptionalDependencyWithoutConsentDegradesSilently() {
        verify(putDeclaringApp("""
                {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": false}
                """), 200);
        String bucket = userBucket();

        AtomicReference<Integer> targetStatus = new AtomicReference<>();
        Response completion = chat(targetStatus, "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(bucket));

        assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        assertEquals(403, targetStatus.get(), "the call succeeds, the undeclared-consent target stays off-limits");
    }

    @Test
    void testConsentedRequiredDependencyGrantsAndFailsAfterWithdrawal() {
        verify(putDeclaringApp("""
                {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": true}
                """), 200);
        verify(grantConsent(), 200);
        String bucket = userBucket();

        AtomicReference<Integer> grantedStatus = new AtomicReference<>();
        Response completion = chat(grantedStatus, "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(bucket));
        assertEquals(200, completion.status());
        assertEquals(200, grantedStatus.get(), "the consented target must be writable by the app's key");

        verify(send(HttpMethod.DELETE, "/v1/admin-consent/applications/public/dep-e2e-app", null, "",
                "authorization", "admin"), 200);

        AtomicReference<Integer> withdrawnStatus = new AtomicReference<>();
        Response afterWithdrawal = chat(withdrawnStatus, "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(bucket));
        assertEquals(403, afterWithdrawal.status(), "after withdrawal the next call fails for a required dependency");
    }

    @Test
    void testDeclarationChangeInvalidatesTheGrant() {
        verify(putDeclaringApp("""
                {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": true}
                """), 200);
        verify(grantConsent(), 200);
        // Any declaration change re-requires the grant — content binding.
        verify(putDeclaringApp("""
                {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": true},
                {"kind": "dial.resourceLink", "link_id": "lnk_extra", "target": {"path": "prompts/{current-user}/other/"}, "access": ["read"]}
                """), 200);
        String bucket = userBucket();

        AtomicReference<Integer> targetStatus = new AtomicReference<>();
        Response completion = chat(targetStatus, "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(bucket));

        assertEquals(403, completion.status(), "the stale grant must not cover the changed declaration");
    }

    @Test
    void testPublicTargetGrantFollowsTheUsersOwnReach() {
        // a public target resolves only as far as the user's own publication rules reach — the declaration never widens it
        verify(upload(HttpMethod.PUT, "/v1/files/public/dep-open/some.txt", null, "open", "authorization", "admin"), 200);
        verify(upload(HttpMethod.PUT, "/v1/files/public/dep-restricted/some.txt", null, "restricted", "authorization", "admin"), 200);
        Rule adminsOnly = new Rule();
        adminsOnly.setSource("roles");
        adminsOnly.setFunction(Rule.Function.EQUAL);
        adminsOnly.setTargets(List.of("admin"));
        dial.getProxy().getRuleService().storeRules("public/dep-restricted/", List.of(adminsOnly));

        verify(putDeclaringApp("""
                {"kind": "dial.resourceLink", "link_id": "lnk_open", "target": {"path": "files/public/dep-open/"}, "access": ["read"]},
                {"kind": "dial.resourceLink", "link_id": "lnk_restricted", "target": {"path": "files/public/dep-restricted/"}, "access": ["read"]}
                """), 200);
        verify(grantConsent(), 200);

        AtomicReference<Integer> openViaApp = new AtomicReference<>();
        AtomicReference<Integer> restrictedViaApp = new AtomicReference<>();
        List<String> events = captureAuditEvents("applications/public/dep-e2e-app", () -> {
            try (TestWebServer server = new TestWebServer(4849)) {
                server.map(HttpMethod.POST, "/chat/completions", request -> {
                    String key = request.getHeader("Api-Key");
                    openViaApp.set(send(HttpMethod.GET, "/v1/files/public/dep-open/some.txt", null, "", "api-key", key).status());
                    restrictedViaApp.set(send(HttpMethod.GET, "/v1/files/public/dep-restricted/some.txt", null, "", "api-key", key).status());
                    return TestWebServer.createResponse(200, "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                            "Content-Type", "application/json");
                });
                verify(send(HttpMethod.POST, "/openai/deployments/applications/public/dep-e2e-app/chat/completions", null, """
                        {"messages":[{"role":"user","content":"how are you?"}]}
                        """, "authorization", "user", "Content-Type", "application/json"), 200);
            }
        });

        assertEquals(403, send(HttpMethod.GET, "/v1/files/public/dep-restricted/some.txt", null, "", "authorization", "user").status());
        assertEquals(200, openViaApp.get());
        assertEquals(403, restrictedViaApp.get(), "a consented declaration must not widen the user's own reach");
        assertTrue(events.stream().anyMatch(e -> e.startsWith("event=resource_dependency_grant") && e.contains("targets=files/public/dep-open/")),
                events::toString);
        assertTrue(events.stream().anyMatch(e -> e.startsWith("event=resource_dependency_denial") && e.contains("targets=files/public/dep-restricted/")),
                events::toString);
    }

    @Test
    void testInterceptorProtectedAppStillResolvesOnTheRootCall() {
        // a root call through an interceptor re-enters the app with the interceptor's per-request key; it must still resolve
        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-e2e-app", null, """
                {
                  "endpoint": "http://localhost:4849/chat/completions",
                  "display_name": "Dependency E2E App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": true}
                  ],
                  "interceptors": ["interceptor1"]
                }
                """, "authorization", "admin"), 200);
        verify(grantConsent(), 200);
        String bucket = userBucket();

        AtomicReference<Integer> targetStatus = new AtomicReference<>();
        try (TestWebServer appServer = new TestWebServer(4849);
                TestWebServer interceptorServer = new TestWebServer(4088)) {
            appServer.map(HttpMethod.POST, "/chat/completions", request -> {
                targetStatus.set(send(HttpMethod.PUT,
                        "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(bucket), null, PROMPT_BODY,
                        "api-key", request.getHeader("Api-Key")).status());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                        "Content-Type", "application/json");
            });
            // the interceptor hands back to Core the way real interceptors do: /openai/deployments/interceptor/…
            interceptorServer.map(HttpMethod.POST, "/api/v1/interceptor/handle", request -> {
                String perRequestKey = request.getHeader("Api-Key");
                Response completion = send(HttpMethod.POST,
                        "/openai/deployments/interceptor/chat/completions", null, """
                        {"messages":[{"role":"user","content":"how are you?"}]}
                        """, "api-key", perRequestKey, "Content-Type", "application/json");
                return TestWebServer.createResponse(completion.status(), completion.body(),
                        "Content-Type", "application/json");
            });

            List<String> events = captureAuditEvents("applications/public/dep-e2e-app", () -> {
                Response completion = send(HttpMethod.POST,
                        "/openai/deployments/applications/public/dep-e2e-app/chat/completions", null, """
                        {"messages":[{"role":"user","content":"how are you?"}]}
                        """, "authorization", "user", "Content-Type", "application/json");
                assertEquals(200, completion.status(), () -> "Body: " + completion.body());
            });
            assertEquals(1, events.size(), () -> "Events: " + events);
            assertTrue(events.get(0).startsWith("event=resource_dependency_grant"), events.get(0));
            assertTrue(events.get(0).contains("source_deployment=interceptor1"), events.get(0));
        }

        assertEquals(200, targetStatus.get(),
                "an interceptor-protected declaring app must resolve its dependencies on a plain root call");
    }

    @Test
    void testAuditEventsCarryTheDesignFields() {
        String bucket = userBucket();
        List<String> events = captureAuditEvents("applications/public/dep-e2e-app", () -> {
            verify(putDeclaringApp("""
                    {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": false}
                    """), 200);

            // optional, unconsented: the call succeeds and the denial is audited
            AtomicReference<Integer> targetStatus = new AtomicReference<>();
            assertEquals(200, chat(targetStatus, "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(bucket)).status());

            // required, unconsented: the call fails and the runtime failure is audited
            verify(putDeclaringApp("""
                    {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": true}
                    """), 200);
            AtomicReference<Integer> failedStatus = new AtomicReference<>();
            assertEquals(403, chat(failedStatus, "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(bucket)).status());
        });

        String denial = events.stream().filter(e -> e.contains("event=resource_dependency_denial")).findFirst().orElse(null);
        assertNotNull(denial, () -> "Events: " + events);
        assertTrue(denial.contains("targets=prompts/{current-user}/dep-smoke/"), denial);
        assertTrue(denial.contains("access_types=WRITE"), denial);
        assertTrue(denial.contains("user_id=user"), denial);
        assertTrue(denial.contains("trace_id=") && !denial.contains("trace_id=,") && !denial.contains("trace_id=null"), denial);
        assertTrue(denial.contains("source_deployment=root"), denial);

        String runtimeFail = events.stream()
                .filter(e -> e.contains("event=resource_dependency_runtime_fail")).findFirst().orElse(null);
        assertNotNull(runtimeFail, () -> "Events: " + events);
        assertTrue(runtimeFail.contains("outcome=RUNTIME_FAIL"), runtimeFail);
        assertTrue(runtimeFail.contains("source_deployment=root"), runtimeFail);

        // consented: the call succeeds and the grant is audited, by content
        verify(grantConsent(), 200);
        List<String> granted = captureAuditEvents("applications/public/dep-e2e-app",
                () -> assertEquals(200, chat(new AtomicReference<>(), "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(bucket)).status()));
        String grant = granted.stream().filter(e -> e.startsWith("event=resource_dependency_grant")).findFirst().orElse(null);
        assertNotNull(grant, () -> "Events: " + granted);
        assertTrue(grant.contains("targets=prompts/{current-user}/dep-smoke/"), grant);
        assertTrue(grant.contains("access_types=WRITE"), grant);
        assertTrue(grant.contains("user_id=user"), grant);
        assertTrue(grant.contains("source_deployment=root"), grant);
        assertTrue(grant.contains("trace_id=") && !grant.contains("trace_id=null"), grant);
    }
}
