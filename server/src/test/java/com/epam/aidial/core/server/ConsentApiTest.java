package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ConsentApiTest extends ResourceBaseTest {

    private static final String APP_REQUEST = """
            {
               "model": "secured-app",
               "stream": false,
               "messages": [
                 {
                   "content": "what do you do?",
                   "role": "user"
                 }
               ]
             }
            """;

    @Test
    public void testUserConsentWorkflow() throws Exception {
        String appResponse = """
                {
                  "id": "eb69ae53-055b-4182-af8f-47f5f3ce810c",
                  "object": "chat.completion",
                  "created": 1687222196,
                  "model": "secured-app",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "some content",
                        "refusal": null
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """;
        TestWebServer.Handler handler = request -> {
            assertNotNull(request.getHeader(Proxy.HEADER_API_KEY));
            return new MockResponse().setBody(appResponse);
        };
        try (TestWebServer ignored = new TestWebServer(9876, handler)) {
            // user has not accepted the consent yet
            Response resp = send(HttpMethod.POST, "/openai/deployments/secured-app/chat/completions",
                    null, APP_REQUEST, "Authorization", "user", "content-type", "application/json");
            verify(resp, 403);
            // request consent
            resp = send(HttpMethod.GET, "/v1/consent/secured-app", null, null, "Authorization", "user");
            verify(resp, 200);
            ObjectNode node = (ObjectNode) ProxyUtil.MAPPER.readTree(resp.body());
            // consent is not accepted yet
            assertFalse(node.get("accepted").asBoolean());
            // accept consent
            node.remove("accepted");
            resp = send(HttpMethod.POST, "/v1/consent/secured-app", null, node.toString(), "Authorization", "user");
            verify(resp, 200);
            // user is permitted to call the deployment
            resp = send(HttpMethod.POST, "/openai/deployments/secured-app/chat/completions",
                    null, APP_REQUEST, "Authorization", "user", "content-type", "application/json");
            verify(resp, 200);
        }
    }

    @Test
    public void testRequestConsent_WhenDeploymentNotFound() {
        var resp = send(HttpMethod.GET, "/v1/consent/unknown-app", null, null, "Authorization", "user");
        verify(resp, 404);
    }

    @Test
    public void testRequestConsent_WhenDeploymentIsForbidden() {
        var resp = send(HttpMethod.GET, "/v1/consent/secured-app", null, null, "api-key", "proxyKey1");
        verify(resp, 403);
    }

    @Test
    public void testRequestConsent_WhenNoDeploymentsHaveConsentRequired() {
        var resp = send(HttpMethod.GET, "/v1/consent/app", null, null, "api-key", "proxyKey1");
        verify(resp, 200, "{\"accepted\":true}");
    }

    @Test
    public void testAcceptConsent_WhenConsentIsInvalid() {
        var resp = send(HttpMethod.POST, "/v1/consent/secured-app", null, "", "Authorization", "user");
        verify(resp, 400);

        resp = send(HttpMethod.POST, "/v1/consent/secured-app", null, """
                {"foo": {}}
                """, "Authorization", "user");
        verify(resp, 400);

        resp = send(HttpMethod.POST, "/v1/consent/secured-app", null, """
                {}
                """, "Authorization", "user");
        verify(resp, 400);
    }

    @Test
    public void testAcceptConsent_RefusedToPerRequestKey() throws Exception {
        Response resp = send(HttpMethod.GET, "/v1/consent/secured-app", null, null, "Authorization", "user");
        verify(resp, 200);
        ObjectNode consent = (ObjectNode) ProxyUtil.MAPPER.readTree(resp.body());
        consent.remove("accepted");

        ApiKeyData appKey = createAppKey("user", Map.of());
        apiKeyStore.assignPerRequestApiKey(appKey);
        resp = send(HttpMethod.POST, "/v1/consent/secured-app", null, consent.toString(),
                "api-key", appKey.getPerRequestKey());
        verify(resp, 403, "User consent is not allowed for per-request keys");

        // nothing was stored for the user
        resp = send(HttpMethod.GET, "/v1/consent/secured-app", null, null, "Authorization", "user");
        verify(resp, 200);
        assertFalse(ProxyUtil.MAPPER.readTree(resp.body()).get("accepted").asBoolean());
    }
}
