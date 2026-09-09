package com.epam.aidial.core.server;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
