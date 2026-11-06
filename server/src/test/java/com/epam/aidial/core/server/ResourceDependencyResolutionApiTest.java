package com.epam.aidial.core.server;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dependency resolution end to end: grants baked into the per-request key on the chat and MCP mint sites,
 * at the root call and on chained hops.
 */
public class ResourceDependencyResolutionApiTest extends ResourceBaseTest {

    private static final String SKILL_MANIFEST = """
            ---
            name: Created By App
            description: Written through the app's per-request key
            version: 1.0.0
            ---
            # Created By App
            """;

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

        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
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
    void testAppWritesSkillIntoUsersSkillsThroughItsKey() {
        // the skill creator: the declared write reaches a complex resource, not only plain ones
        Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        String userBucket = new JsonObject(response.body()).getString("bucket");
        String appUrl = "applications/public/dep-skill-app";

        verify(send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                  "endpoint": "http://localhost:4848/chat/completions",
                  "display_name": "Skill Creator",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_skills",
                     "target": {"path": "skills/{current-user}/"}, "access": ["read", "write"], "required": true}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
                "authorization", "admin"), 200);

        AtomicReference<Integer> skillStatus = new AtomicReference<>();
        AtomicReference<Integer> promptStatus = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                String perRequestKey = request.getHeader("Api-Key");
                skillStatus.set(uploadComplex("skills/" + userBucket + "/created-by-app", perRequestKey, "SKILL.md", SKILL_MANIFEST).status());
                promptStatus.set(send(HttpMethod.PUT, "/v1/prompts/%s/outside-scope".formatted(userBucket), null,
                        "{\"id\":\"p\",\"folderId\":\"f\",\"name\":\"p\",\"content\":\"c\"}", "api-key", perRequestKey).status());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                        "Content-Type", "application/json");
            });

            Response completion = send(HttpMethod.POST, "/openai/deployments/%s/chat/completions".formatted(appUrl), null, """
                    {"messages":[{"role":"user","content":"make me a skill"}]}
                    """, "authorization", "user", "Content-Type", "application/json");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        assertEquals(200, skillStatus.get(), "the declared skills folder must be writable by the app's key");
        assertEquals(403, promptStatus.get(), "the user's other resources must stay off-limits");
        Response written = send(HttpMethod.GET, "/v2/skills/%s/created-by-app/files/SKILL.md".formatted(userBucket), null, "",
                "authorization", "user");
        assertEquals(200, written.status(), () -> "Body: " + written.body());
    }

    @Test
    void testAppWritesProjectIntoUsersProjectsThroughItsKey() {
        Response response = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        String userBucket = new JsonObject(response.body()).getString("bucket");
        String appUrl = "applications/public/dep-project-app";

        verify(send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                  "endpoint": "http://localhost:4848/chat/completions",
                  "display_name": "Project Builder",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk_projects",
                     "target": {"path": "projects/{current-user}/"}, "access": ["read", "write"], "required": true}
                  ]
                }
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
                "authorization", "admin"), 200);

        AtomicReference<Integer> projectStatus = new AtomicReference<>();
        AtomicReference<Integer> readStatus = new AtomicReference<>();
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                String perRequestKey = request.getHeader("Api-Key");
                projectStatus.set(uploadComplex("projects/" + userBucket + "/built-by-app", perRequestKey,
                        "README.md", "# Built by the app").status());
                readStatus.set(send(HttpMethod.GET, "/v2/projects/%s/built-by-app/files/README.md".formatted(userBucket), null, "",
                        "api-key", perRequestKey).status());
                return TestWebServer.createResponse(200,
                        "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                        "Content-Type", "application/json");
            });

            Response completion = send(HttpMethod.POST, "/openai/deployments/%s/chat/completions".formatted(appUrl), null, """
                    {"messages":[{"role":"user","content":"build me a project"}]}
                    """, "authorization", "user", "Content-Type", "application/json");
            assertEquals(200, completion.status(), () -> "Body: " + completion.body());
        }

        assertEquals(200, projectStatus.get(), "the declared projects folder must be writable by the app's key");
        assertEquals(200, readStatus.get(), "the declared projects folder must be readable by the app's key");
        Response written = send(HttpMethod.GET, "/v2/projects/%s/built-by-app/files/README.md".formatted(userBucket), null, "",
                "authorization", "user");
        assertEquals(200, written.status(), () -> "Body: " + written.body());
    }

    @Test
    void testChainedHopResolvesItsOwnDeclarationAgainstTheInitiator() {
        // the chained key carries the user's claims, so the user's own folder resolves, not the orchestrator's sandbox
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
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
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
            List<String> events = captureAuditEvents(appUrl, () -> {
                Response completion = send(HttpMethod.POST, "/openai/deployments/%s/chat/completions".formatted(appUrl), null, """
                        {"messages":[{"role":"user","content":"how are you?"}]}
                        """, "api-key", orchestratorKey.getPerRequestKey(), "Content-Type", "application/json");
                assertEquals(200, completion.status(), () -> "Body: " + completion.body());
            });
            assertEquals(1, events.size(), () -> "Events: " + events);
            assertTrue(events.get(0).startsWith("event=resource_dependency_grant"), events.get(0));
            assertTrue(events.get(0).contains("source_deployment=testapp"), events.get(0));
        }

        assertEquals(200, targetStatus.get(),
                "a chained hop must resolve its own declaration against the initiator");
    }

    @Test
    void testChainedHopFailsWhenRequiredDependencyIsUnreachable() {
        // a required dependency the initiator cannot reach fails the chained call before the app is invoked
        String adminBucket = new JsonObject(
                send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "admin").body()).getString("bucket");
        String appUrl = "applications/public/dep-chained-required-app";

        verify(send(HttpMethod.PUT, "/v1/applications/public/dep-chained-required-app", null, """
                {
                  "endpoint": "http://localhost:4835/chat/completions",
                  "display_name": "Dependency Chained Required App",
                  "resource_dependencies": [
                    {"kind": "dial.resourceLink", "link_id": "lnk",
                     "target": {"path": "files/%s/secret.txt"}, "access": ["read"], "required": true}
                  ]
                }
                """.formatted(adminBucket), "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
                "authorization", "admin"), 200);

        ApiKeyData orchestratorKey = createAppKey("user", Map.of());
        orchestratorKey.setExecutionPath(List.of("orchestrator"));
        apiKeyStore.assignPerRequestApiKey(orchestratorKey);

        AtomicInteger upstreamHits = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4835)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                upstreamHits.incrementAndGet();
                return TestWebServer.createResponse(200,
                        "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":[]}",
                        "Content-Type", "application/json");
            });
            Response completion = send(HttpMethod.POST, "/openai/deployments/%s/chat/completions".formatted(appUrl), null, """
                    {"messages":[{"role":"user","content":"how are you?"}]}
                    """, "api-key", orchestratorKey.getPerRequestKey(), "Content-Type", "application/json");
            assertEquals(403, completion.status(), () -> "Body: " + completion.body());
            assertTrue(completion.body().contains("Required resource dependencies are not accessible"), completion.body());
        }

        assertEquals(0, upstreamHits.get(), "the declaring app must not be invoked when its required dependency fails");
    }

    @Test
    void testChainedHopIsSandboxedFromTheCallersOwnGrants() {
        // a grant held by the calling app must not satisfy the called app's reach check
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
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
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
            List<String> events = captureAuditEvents(appUrl, () -> {
                Response completion = send(HttpMethod.POST, "/openai/deployments/%s/chat/completions".formatted(appUrl), null, """
                        {"messages":[{"role":"user","content":"how are you?"}]}
                        """, "api-key", orchestratorKey.getPerRequestKey(), "Content-Type", "application/json");
                assertEquals(200, completion.status(), () -> "Body: " + completion.body());
            });
            assertEquals(1, events.size(), () -> "Events: " + events);
            assertTrue(events.get(0).startsWith("event=resource_dependency_denial"), events.get(0));
            assertTrue(events.get(0).contains("targets=" + targetPath), events.get(0));
            assertTrue(events.get(0).contains("source_deployment=testapp"), events.get(0));
        }

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
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
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
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
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
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
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
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
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
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
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
                "a chained MCP hop must resolve its own declaration against the initiator");
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
        verify(send(HttpMethod.POST, "/v1/admin-consent/" + appUrl, null, reviewedResources(appUrl),
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

            List<String> events = captureAuditEvents(appUrl, () -> {
                Response completion = send(HttpMethod.POST, "/v1/deployments/" + appUrl + "/mcp", null, """
                        {"payload":"foo"}
                        """, "api-key", orchestratorKey.getPerRequestKey(), "Content-type", "application/json");
                assertEquals(200, completion.status(), () -> "Body: " + completion.body());
            });
            assertEquals(1, events.size(), () -> "Events: " + events);
            assertTrue(events.get(0).startsWith("event=resource_dependency_denial"), events.get(0));
            assertTrue(events.get(0).contains("targets=" + targetPath), events.get(0));
            assertTrue(events.get(0).contains("source_deployment=testapp"), events.get(0));
        }

        assertEquals(403, targetStatus.get(),
                "a grant already held by the calling app must not satisfy the called app's reach check over MCP");
    }

    /** Writes a skill or a project, a complex resource uploaded as a file tree. */
    @SneakyThrows
    private Response uploadComplex(String typeAndPath, String apiKey, String fileName, String content) {
        HttpUriRequest request = new HttpUriRequestBase(HttpMethod.PUT.name(), URI.create("http://127.0.0.1:" + serverPort + "/v2/" + typeAndPath));
        request.setHeader("api-key", apiKey);
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.LEGACY);
        builder.setCharset(StandardCharsets.UTF_8);
        builder.addBinaryBody(fileName, content.getBytes(StandardCharsets.UTF_8), ContentType.DEFAULT_BINARY, fileName);
        request.setEntity(builder.build());
        return client.execute(request, ResourceBaseTest::toResponse);
    }
}
