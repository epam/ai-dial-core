package com.epam.aidial.core.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v1 checklist as test cases (design §12, resolution PR): the consented flow, the two
 * failure modes distinguished (required vs optional without consent), content binding on
 * declaration change, and the audit fields. Scenarios run on the chat-completions path; the
 * Anthropic-Messages and Responses sites share the identical chain position (see
 * {@code ResourceDependencyResolutionApiTest} for the full happy-path walk).
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
                  "display_name": "Dependency E2E App",
                  "resource_dependencies": [%s]
                }
                """.formatted(dependenciesJson), "authorization", "admin");
    }

    private Response grantConsent() {
        return send(HttpMethod.POST, "/v1/consent/applications/public/dep-e2e-app/admin-consent", null, "",
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

        verify(send(HttpMethod.DELETE, "/v1/consent/applications/public/dep-e2e-app/admin-consent", null, "",
                "authorization", "admin"), 200);

        AtomicReference<Integer> withdrawnStatus = new AtomicReference<>();
        Response afterWithdrawal = chat(withdrawnStatus, "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(bucket));
        assertEquals(403, afterWithdrawal.status(), "withdrawal stops the run immediately for a required dependency");
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
        // Pointer semantics, end to end (D-04): the declaration adds visibility and consent, never
        // access — the app's key reaches a public target exactly when the originating user does.
        verify(putDeclaringApp("""
                {"kind": "dial.resourceLink", "link_id": "lnk", "target": {"path": "files/public/dep-public/"}, "access": ["read"], "required": false}
                """), 200);
        verify(grantConsent(), 200);

        int userDirect = send(HttpMethod.GET, "/v1/files/public/dep-public/some.txt", null, "",
                "authorization", "user").status();

        AtomicReference<Integer> viaApp = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4849)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                viaApp.set(send(HttpMethod.GET, "/v1/files/public/dep-public/some.txt", null, "",
                        "api-key", request.getHeader("Api-Key")).status());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                        "Content-Type", "application/json");
            });
            Response completion = send(HttpMethod.POST,
                    "/openai/deployments/applications/public/dep-e2e-app/chat/completions", null, """
                    {"messages":[{"role":"user","content":"how are you?"}]}
                    """, "authorization", "user", "Content-Type", "application/json");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        assertEquals(userDirect, viaApp.get(),
                "the app's key must see exactly what the originating user sees — no more, no less");
    }

    @Test
    void testInterceptorProtectedAppStillResolvesOnTheRootCall() {
        // A plain-root-call correctness bug, not a chained-composition scenario: the interceptor's
        // own call back into DeploymentPostController.handleDeployment(initialDeployment) re-enters
        // with the interceptor's per-request key already in context, hitting the very guard PR4b
        // removes — so before this PR an interceptor-protected declaring app silently never
        // resolved its dependencies, even on a plain user root call.
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
            // The interceptor server: forwards to the original deployment with the per-request key
            // it was handed — this second, inbound call is where handleDeployment re-enters with a
            // per-request key already present.
            interceptorServer.map(HttpMethod.POST, "/api/v1/interceptor/handle", request -> {
                String perRequestKey = request.getHeader("Api-Key");
                Response completion = send(HttpMethod.POST,
                        "/openai/deployments/applications/public/dep-e2e-app/chat/completions", null, """
                        {"messages":[{"role":"user","content":"how are you?"}]}
                        """, "api-key", perRequestKey, "Content-Type", "application/json");
                return TestWebServer.createResponse(completion.status(), completion.body(),
                        "Content-Type", "application/json");
            });

            Response completion = send(HttpMethod.POST,
                    "/openai/deployments/applications/public/dep-e2e-app/chat/completions", null, """
                    {"messages":[{"role":"user","content":"how are you?"}]}
                    """, "authorization", "user", "Content-Type", "application/json");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        // Before this PR: 403 (the guard skipped resolution on this re-entry). After: resolved.
        assertEquals(200, targetStatus.get(),
                "an interceptor-protected declaring app must resolve its dependencies on a plain root call");
    }

    @Test
    void testAuditEventsCarryTheDesignFields() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_RESOURCE_DEPS_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        try {
            String bucket = userBucket();
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

            List<String> events = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("event=resource_dependency_"))
                    .collect(Collectors.toList());

            String denial = events.stream().filter(e -> e.contains("event=resource_dependency_denial")).findFirst().orElse(null);
            assertNotNull(denial, () -> "Events: " + events);
            assertTrue(denial.contains("application_id=applications/public/dep-e2e-app"), denial);
            assertTrue(denial.contains("targets=prompts/{current-user}/dep-smoke/"), denial);
            assertTrue(denial.contains("user_id=user"), denial);
            assertTrue(denial.contains("trace_id=") && !denial.contains("trace_id=,") && !denial.contains("trace_id=null"), denial);
            // A plain user root call: no calling deployment, so source_deployment is the "root"
            // sentinel — pinned here since it's the path least likely to be exercised elsewhere.
            assertTrue(denial.contains("source_deployment=root"), denial);

            String runtimeFail = events.stream()
                    .filter(e -> e.contains("event=resource_dependency_runtime_fail")).findFirst().orElse(null);
            assertNotNull(runtimeFail, () -> "Events: " + events);
            assertTrue(runtimeFail.contains("outcome=RUNTIME_FAIL"), runtimeFail);
        } finally {
            auditLogger.detachAppender(appender);
        }
    }
}
