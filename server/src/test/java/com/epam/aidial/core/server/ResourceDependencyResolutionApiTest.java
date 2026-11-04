package com.epam.aidial.core.server;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v1 happy path, end to end on the chat-completions mint site: an admin-authored app
 * declares a typed personal dependency, an admin grants, a user chats — and the per-request key
 * the app's upstream receives carries the grant, verified by exercising the user's folder with
 * that key. The full six-scenario suite lands with the resolution-coverage PR.
 */
public class ResourceDependencyResolutionApiTest extends ResourceBaseTest {

    @Test
    void testChatPathBakesConsentedDependencyGrantIntoThePerRequestKey() {
        Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        String userBucket = new JsonObject(response.body()).getString("bucket");
        String appUrl = "applications/public/dep-smoke-app";

        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-smoke-app", null, """
                {
                  "endpoint": "http://localhost:4848/chat/completions",
                  "display_name": "Dependency Smoke App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_prompts",
                     "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": true}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);

        verify(send(HttpMethod.POST, "/v1/consent/" + appUrl + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        AtomicReference<String> appKey = new AtomicReference<>();
        AtomicReference<Integer> inScopeStatus = new AtomicReference<>();
        AtomicReference<Integer> outOfScopeStatus = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                String perRequestKey = request.getHeader("Api-Key");
                appKey.set(perRequestKey);
                // Exercise the grant from inside the request, while the per-request key is live —
                // the only window an application ever holds it.
                inScopeStatus.set(send(HttpMethod.PUT,
                        "/v1/prompts/%s/dep-smoke/written-by-app".formatted(userBucket), null,
                        "{\"id\":\"prompt-by-app\",\"folderId\":\"dep-smoke/\",\"name\":\"prompt-by-app\",\"content\":\"app content\"}", "api-key", perRequestKey).status());
                outOfScopeStatus.set(send(HttpMethod.PUT,
                        "/v1/prompts/%s/outside-scope".formatted(userBucket), null,
                        "{\"id\":\"prompt-by-app\",\"folderId\":\"dep-smoke/\",\"name\":\"prompt-by-app\",\"content\":\"app content\"}", "api-key", perRequestKey).status());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                        "Content-Type", "application/json");
            });

            Response completion = send(HttpMethod.POST, "/openai/deployments/%s/chat/completions".formatted(appUrl), null, """
                    {"messages":[{"role":"user","content":"how are you?"}]}
                    """, "authorization", "user", "Content-Type", "application/json");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        assertNotNull(appKey.get(), "the app upstream must receive a per-request key");
        // The grant travels with the key: the app writes under the user's dep-smoke folder…
        assertEquals(200, inScopeStatus.get(), "the consented dependency target must be writable by the app's key");
        // …and only there — the user's files root stays off-limits to the app's key.
        assertEquals(403, outOfScopeStatus.get(), "anything outside the declared target must stay off-limits");
    }

    @Test
    void testChainedHopResolvesItsOwnDeclarationAgainstTheOriginatingUser() {
        // The rewritten successor of the removed root-call-only test (design §7.1, D-24):
        // createAppKey puts the user's ExtractedClaims on the chained key, so ProxyContext.userId
        // is still "user", buildInitiatorBucket is still Users/user/, and
        // lookupOriginatingUserPermissions's own-bucket rule grants ALL — whereas the general
        // chain would have asked whether Keys/testapp/ (the orchestrator's own sandbox) owns the
        // prompt folder, and answered no.
        Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        String userBucket = new JsonObject(response.body()).getString("bucket");
        String appUrl = "applications/public/dep-chained-app";

        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-chained-app", null, """
                {
                  "endpoint": "http://localhost:4847/chat/completions",
                  "display_name": "Dependency Chained App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk",
                     "target": {"path": "prompts/{current-user}/dep-smoke/"}, "access": ["write"], "required": true}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/consent/" + appUrl + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        // The key an orchestrator would hold when delegating to the declaring app — a chained hop.
        ApiKeyData orchestratorKey = createAppKey("user", Map.of());
        orchestratorKey.setExecutionPath(List.of("orchestrator"));
        apiKeyStore.assignPerRequestApiKey(orchestratorKey);

        AtomicReference<Integer> targetStatus = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4847)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                targetStatus.set(send(HttpMethod.PUT,
                        "/v1/prompts/%s/dep-smoke/prompt-by-app".formatted(userBucket), null,
                        "{\"id\":\"prompt-by-app\",\"folderId\":\"dep-smoke/\",\"name\":\"prompt-by-app\",\"content\":\"app content\"}",
                        "api-key", request.getHeader("Api-Key")).status());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                        "Content-Type", "application/json");
            });
            Response completion = send(HttpMethod.POST, "/openai/deployments/%s/chat/completions".formatted(appUrl), null, """
                    {"messages":[{"role":"user","content":"how are you?"}]}
                    """, "api-key", orchestratorKey.getPerRequestKey(), "Content-Type", "application/json");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        // Before this PR: 200 and 403 (the guard skipped resolution on this hop). After: 200 and 200.
        assertEquals(200, targetStatus.get(),
                "a chained hop must resolve its own declaration against the originating user");
    }

    @Test
    void testChainedHopIsSandboxedFromTheCallersOwnGrants() {
        // The single most important assertion in this PR, end to end (D-24): a grant already
        // baked into the CALLING app's own per-request key must not satisfy the CALLED app's
        // independent reach check — otherwise app1 could launder its own grants into app2.
        String adminBucket = new JsonObject(
                send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "admin").body()).getString("bucket");
        String targetPath = "files/" + adminBucket + "/secret.txt";
        String appUrl = "applications/public/dep-laundering-app";

        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-laundering-app", null, """
                {
                  "endpoint": "http://localhost:4846/chat/completions",
                  "display_name": "Dependency Laundering App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk",
                     "target": {"path": "%s"}, "access": ["write"], "required": false}
                  ]
                }
                """.formatted(targetPath), "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/consent/" + appUrl + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        // The orchestrator holds a grant on this exact target already — the plain "user" role has
        // no reach to the admin's bucket on their own standing.
        ApiKeyData orchestratorKey = createAppKey("user", Map.of());
        orchestratorKey.setExecutionPath(List.of("orchestrator"));
        orchestratorKey.getPerRequestSharedResources().put(targetPath,
                new PerRequestSharedData(new HashSet<>(Set.of(ResourceAccessType.WRITE))));
        apiKeyStore.assignPerRequestApiKey(orchestratorKey);

        AtomicReference<Integer> targetStatus = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4846)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                targetStatus.set(send(HttpMethod.PUT, "/v1/" + targetPath, null,
                        "{\"content\":\"nope\"}", "api-key", request.getHeader("Api-Key")).status());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                        "Content-Type", "application/json");
            });
            Response completion = send(HttpMethod.POST, "/openai/deployments/%s/chat/completions".formatted(appUrl), null, """
                    {"messages":[{"role":"user","content":"how are you?"}]}
                    """, "api-key", orchestratorKey.getPerRequestKey(), "Content-Type", "application/json");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        // Without D-24's exclusions this test's write would pass at 200 — the orchestrator's own
        // grant laundered straight through into the declaring app's key.
        assertEquals(403, targetStatus.get(),
                "a grant already held by the calling app must not satisfy the called app's reach check");
    }

    // ---- the MCP-application-proxy mint site ----

    @Test
    void testMcpMintSiteBakesConsentedDependencyGrantIntoThePerRequestKey() {
        Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        String userBucket = new JsonObject(response.body()).getString("bucket");
        String appUrl = "applications/public/dep-mcp-app";

        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-mcp-app", null, """
                {
                  "display_name": "Dependency MCP App",
                  "mcp": {"endpoint": "http://localhost:4845/mcp", "transport": "HTTP"},
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_skills",
                     "target": {"path": "prompts/{current-user}/mcp-demo/"}, "access": ["write"], "required": true}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/consent/" + appUrl + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        AtomicReference<Integer> inScopeStatus = new AtomicReference<>();
        AtomicReference<Integer> outOfScopeStatus = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4845)) {
            server.map(HttpMethod.POST, "/mcp", request -> {
                String perRequestKey = request.getHeader(Proxy.HEADER_API_KEY);
                inScopeStatus.set(send(HttpMethod.PUT,
                        "/v1/prompts/%s/mcp-demo/skill-by-app".formatted(userBucket), null,
                        "{\"id\":\"skill-by-app\",\"folderId\":\"mcp-demo/\",\"name\":\"skill-by-app\",\"content\":\"app content\"}",
                        "api-key", perRequestKey).status());
                outOfScopeStatus.set(send(HttpMethod.PUT,
                        "/v1/prompts/%s/outside-scope".formatted(userBucket), null,
                        "{\"id\":\"skill-by-app\",\"folderId\":\"mcp-demo/\",\"name\":\"skill-by-app\",\"content\":\"app content\"}",
                        "api-key", perRequestKey).status());
                return TestWebServer.createResponse(200, "{\"result\":\"ok\"}", "Content-Type", "application/json");
            });

            Response completion = send(HttpMethod.POST, "/v1/deployments/" + appUrl + "/mcp", null, """
                    {"payload":"foo"}
                    """, "authorization", "user", "Content-type", "application/json");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        assertEquals(200, inScopeStatus.get(), "the consented dependency target must be writable by the app's key");
        assertEquals(403, outOfScopeStatus.get(), "anything outside the declared target must stay off-limits");
    }

    @Test
    void testMcpMintSiteSurvivesAnUpstreamTrailingSlashRedirect() {
        // a redirect re-enters handleProxyRequest; the second hit must reuse the grant-bearing key
        Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        String userBucket = new JsonObject(response.body()).getString("bucket");
        String appUrl = "applications/public/dep-mcp-redirect-app";

        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-mcp-redirect-app", null, """
                {
                  "display_name": "Dependency MCP Redirect App",
                  "mcp": {"endpoint": "http://localhost:4844/mcp", "transport": "HTTP"},
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_skills",
                     "target": {"path": "prompts/{current-user}/mcp-demo/"}, "access": ["write"], "required": true}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/consent/" + appUrl + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        List<String> observedKeys = new CopyOnWriteArrayList<>();
        AtomicReference<Integer> targetStatus = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4844)) {
            server.map(HttpMethod.POST, "/mcp", request -> {
                observedKeys.add(request.getHeader(Proxy.HEADER_API_KEY));
                return TestWebServer.createResponse(308, "", "Location", "/mcp/");
            });
            server.map(HttpMethod.POST, "/mcp/", request -> {
                String perRequestKey = request.getHeader(Proxy.HEADER_API_KEY);
                observedKeys.add(perRequestKey);
                targetStatus.set(send(HttpMethod.PUT,
                        "/v1/prompts/%s/mcp-demo/skill-by-app".formatted(userBucket), null,
                        "{\"id\":\"skill-by-app\",\"folderId\":\"mcp-demo/\",\"name\":\"skill-by-app\",\"content\":\"app content\"}",
                        "api-key", perRequestKey).status());
                return TestWebServer.createResponse(200, "{\"result\":\"ok\"}", "Content-Type", "application/json");
            });

            List<String> events = captureAuditEvents(appUrl, () -> {
                Response completion = send(HttpMethod.POST, "/v1/deployments/" + appUrl + "/mcp", null, """
                        {"payload":"foo"}
                        """, "authorization", "user", "Content-type", "application/json");
                assertEquals(200, completion.status(), () -> "Body: " + completion.body());
            });
            assertEquals(1, events.size(), () -> "one audit group per request, not per redirect: " + events);
            assertTrue(events.get(0).startsWith("event=resource_dependency_grant"), events.get(0));
        }

        assertEquals(2, observedKeys.size(), "expected exactly two upstream hits: the redirect and the target");
        assertEquals(observedKeys.get(0), observedKeys.get(1),
                "the same per-request key must be forwarded on both the redirected and the final request");
        assertEquals(200, targetStatus.get(), "the grant baked before the chain ran must still be usable after the redirect");
    }

    @Test
    void testMcpMintSiteFailsTheCallWhenRequiredDependencyIsUnresolvable() {
        // a 400 "Invalid JSON request body" here would mean the key was created after the chain
        String appUrl = "applications/public/dep-mcp-fail-app";
        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-mcp-fail-app", null, """
                {
                  "display_name": "Dependency MCP Fail App",
                  "mcp": {"endpoint": "http://localhost:4843/mcp", "transport": "HTTP"},
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_skills",
                     "target": {"path": "prompts/{current-user}/mcp-demo/"}, "access": ["write"], "required": true}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        // No admin consent granted.

        List<String> events = captureAuditEvents(appUrl, () -> {
            Response completion = send(HttpMethod.POST, "/v1/deployments/" + appUrl + "/mcp", null, """
                    {"payload":"foo"}
                    """, "authorization", "user", "Content-type", "application/json");

            assertEquals(403, completion.status(), () -> "Body: " + completion.body());
            assertTrue(completion.body().contains("Required resource dependencies are not accessible"), completion.body());
            assertTrue(completion.body().contains("prompts/{current-user}/mcp-demo/"), completion.body());
        });

        List<String> runtimeFails = events.stream().filter(e -> e.startsWith("event=resource_dependency_runtime_fail")).toList();
        assertEquals(1, runtimeFails.size(), () -> "Events: " + events);
    }

    @Test
    void testMcpMintSiteGrantsNothingWhenTheAppDoesNotForwardThePerRequestKey() {
        String appUrl = "applications/public/dep-mcp-noforward-app";
        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-mcp-noforward-app", null, """
                {
                  "display_name": "Dependency MCP No-Forward App",
                  "mcp": {"endpoint": "http://localhost:4842/mcp", "transport": "HTTP", "forwardPerRequestKey": false},
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_skills",
                     "target": {"path": "prompts/{current-user}/mcp-demo/"}, "access": ["write"], "required": false}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/consent/" + appUrl + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        AtomicReference<String> observedKey = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4842)) {
            server.map(HttpMethod.POST, "/mcp", request -> {
                observedKey.set(request.getHeader(Proxy.HEADER_API_KEY));
                return TestWebServer.createResponse(200, "{\"result\":\"ok\"}", "Content-Type", "application/json");
            });

            List<String> events = captureAuditEvents(appUrl, () -> {
                Response completion = send(HttpMethod.POST, "/v1/deployments/" + appUrl + "/mcp", null, """
                        {"payload":"foo"}
                        """, "authorization", "user", "Content-type", "application/json");
                assertEquals(200, completion.status(), () -> "Body: " + completion.body());
            });
            assertEquals(1, events.size(), () -> "Events: " + events);
            assertTrue(events.get(0).startsWith("event=resource_dependency_denial"), events.get(0));
        }

        assertNull(observedKey.get(), "no Api-Key header must reach the upstream when forwardPerRequestKey is false");
    }

    @Test
    void testMcpMintSiteNamesTheCauseWhenRequiredDependencyCannotBeDelivered() {
        // consented, but forwardPerRequestKey=false leaves nothing to deliver grants into: the 403 says so, the audit says why
        String appUrl = "applications/public/dep-mcp-noforward-required-app";
        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-mcp-noforward-required-app", null, """
                {
                  "display_name": "Dependency MCP No-Forward Required App",
                  "mcp": {"endpoint": "http://localhost:4838/mcp", "transport": "HTTP", "forwardPerRequestKey": false},
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_skills",
                     "target": {"path": "prompts/{current-user}/mcp-demo/"}, "access": ["write"], "required": true}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/consent/" + appUrl + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        List<String> events = captureAuditEvents(appUrl, () -> {
            Response completion = send(HttpMethod.POST, "/v1/deployments/" + appUrl + "/mcp", null, """
                    {"payload":"foo"}
                    """, "authorization", "user", "Content-type", "application/json");

            assertEquals(403, completion.status(), () -> "Body: " + completion.body());
            assertTrue(completion.body().contains("dependency grants cannot be delivered to this application"), completion.body());
            assertFalse(completion.body().contains("forwardPerRequestKey"), completion.body());
        });

        List<String> runtimeFails = events.stream().filter(e -> e.startsWith("event=resource_dependency_runtime_fail")).toList();
        assertEquals(1, runtimeFails.size(), () -> "Events: " + events);
        assertTrue(runtimeFails.get(0).contains("reason=\"dependency grants cannot be delivered: mcp.forwardPerRequestKey is false\""),
                runtimeFails.get(0));
    }

    @Test
    void testMcpMintSiteNonJsonPostGetsUsableKeyWithoutGrants() {
        // a body that skips the chain: no resolution, so no 403 for the required dependency and no grants
        Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        String userBucket = new JsonObject(response.body()).getString("bucket");
        String appUrl = "applications/public/dep-mcp-nonjson-app";
        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-mcp-nonjson-app", null, """
                {
                  "display_name": "Dependency MCP Non-JSON App",
                  "mcp": {"endpoint": "http://localhost:4837/mcp", "transport": "HTTP"},
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_skills",
                     "target": {"path": "prompts/{current-user}/mcp-demo/"}, "access": ["write"], "required": true}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        // No admin consent granted: a JSON-RPC call to this app would be 403.

        AtomicReference<Integer> keyUsableStatus = new AtomicReference<>();
        AtomicReference<Integer> targetStatus = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4837)) {
            server.map(HttpMethod.POST, "/mcp", request -> {
                String perRequestKey = request.getHeader(Proxy.HEADER_API_KEY);
                keyUsableStatus.set(send(HttpMethod.GET, "/v1/bucket", null, "", "api-key", perRequestKey).status());
                targetStatus.set(send(HttpMethod.PUT,
                        "/v1/prompts/%s/mcp-demo/skill-by-app".formatted(userBucket), null,
                        "{\"id\":\"skill-by-app\",\"folderId\":\"mcp-demo/\",\"name\":\"skill-by-app\",\"content\":\"app content\"}",
                        "api-key", perRequestKey).status());
                return TestWebServer.createResponse(200, "{\"result\":\"ok\"}", "Content-Type", "application/json");
            });

            Response completion = send(HttpMethod.POST, "/v1/deployments/" + appUrl + "/mcp", null,
                    "{\"payload\":\"foo\"}", "authorization", "user", "Content-type", "text/plain");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        assertEquals(200, keyUsableStatus.get(), "the key forwarded on a non-JSON request must be persisted and usable");
        assertEquals(403, targetStatus.get(), "a request that skips the chain carries no dependency grants");
    }

    @Test
    void testMcpChainedHopResolvesItsOwnDeclaration() {
        // an orchestrator's per-request key calls the declaring app over MCP
        Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        String userBucket = new JsonObject(response.body()).getString("bucket");
        String appUrl = "applications/public/dep-mcp-chained-app";

        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-mcp-chained-app", null, """
                {
                  "display_name": "Dependency MCP Chained App",
                  "mcp": {"endpoint": "http://localhost:4841/mcp", "transport": "HTTP"},
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_skills",
                     "target": {"path": "prompts/{current-user}/mcp-demo/"}, "access": ["write"], "required": true}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/consent/" + appUrl + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        // The key an orchestrator would hold when delegating to the declaring app over MCP.
        ApiKeyData orchestratorKey = createAppKey("user", Map.of());
        orchestratorKey.setExecutionPath(List.of("orchestrator"));
        apiKeyStore.assignPerRequestApiKey(orchestratorKey);

        AtomicReference<Integer> targetStatus = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4841)) {
            server.map(HttpMethod.POST, "/mcp", request -> {
                targetStatus.set(send(HttpMethod.PUT,
                        "/v1/prompts/%s/mcp-demo/skill-by-app".formatted(userBucket), null,
                        "{\"id\":\"skill-by-app\",\"folderId\":\"mcp-demo/\",\"name\":\"skill-by-app\",\"content\":\"app content\"}",
                        "api-key", request.getHeader(Proxy.HEADER_API_KEY)).status());
                return TestWebServer.createResponse(200, "{\"result\":\"ok\"}", "Content-Type", "application/json");
            });

            Response completion = send(HttpMethod.POST, "/v1/deployments/" + appUrl + "/mcp", null, """
                    {"payload":"foo"}
                    """, "api-key", orchestratorKey.getPerRequestKey(), "Content-type", "application/json");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        assertEquals(200, targetStatus.get(),
                "a chained MCP hop must resolve its own declaration against the originating user");
    }

    @Test
    void testMcpChainedHopIsSandboxedFromTheCallersOwnGrants() {
        // the orchestrator's own grant on the target must not satisfy the called app's reach check
        String adminBucket = new JsonObject(
                send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "admin").body()).getString("bucket");
        String targetPath = "files/" + adminBucket + "/secret.txt";
        String appUrl = "applications/public/dep-mcp-laundering-app";

        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-mcp-laundering-app", null, """
                {
                  "display_name": "Dependency MCP Laundering App",
                  "mcp": {"endpoint": "http://localhost:4839/mcp", "transport": "HTTP"},
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk",
                     "target": {"path": "%s"}, "access": ["write"], "required": false}
                  ]
                }
                """.formatted(targetPath), "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/consent/" + appUrl + "/admin-consent", null, "",
                "authorization", "admin"), 200);

        ApiKeyData orchestratorKey = createAppKey("user", Map.of());
        orchestratorKey.setExecutionPath(List.of("orchestrator"));
        orchestratorKey.getPerRequestSharedResources().put(targetPath,
                new PerRequestSharedData(new HashSet<>(Set.of(ResourceAccessType.WRITE))));
        apiKeyStore.assignPerRequestApiKey(orchestratorKey);

        AtomicReference<Integer> targetStatus = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4839)) {
            server.map(HttpMethod.POST, "/mcp", request -> {
                targetStatus.set(send(HttpMethod.PUT, "/v1/" + targetPath, null,
                        "{\"content\":\"nope\"}", "api-key", request.getHeader(Proxy.HEADER_API_KEY)).status());
                return TestWebServer.createResponse(200, "{\"result\":\"ok\"}", "Content-Type", "application/json");
            });

            Response completion = send(HttpMethod.POST, "/v1/deployments/" + appUrl + "/mcp", null, """
                    {"payload":"foo"}
                    """, "api-key", orchestratorKey.getPerRequestKey(), "Content-type", "application/json");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        assertEquals(403, targetStatus.get(),
                "a grant already held by the calling app must not satisfy the called app's reach check over MCP");
    }

    private static List<String> captureAuditEvents(String appUrl, Runnable action) {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_RESOURCE_DEPS_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        try {
            action.run();
        } finally {
            auditLogger.detachAppender(appender);
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("event=resource_dependency_")
                        && message.contains("application_id=" + appUrl + " "))
                .toList();
    }
}
