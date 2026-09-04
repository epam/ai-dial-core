package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

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
}
