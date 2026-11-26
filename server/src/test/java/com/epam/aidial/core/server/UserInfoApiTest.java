package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UserInfoApiTest extends ResourceBaseTest {

    @Test
    public void testApiKeyInfo() {
        var resp = send(HttpMethod.GET, "/v1/user/info", null, null, "api-key", "proxyKey1");

        assertEquals(200, resp.status());
        assertEquals("""
                {"roles":["default"],"project":"EPM-RTC-GPT","costObject":"EPM-RTC-GPT","costObjectSource":"key","userDisplayName":"EPM-RTC-GPT"}""", resp.body());
    }

    @Test
    public void testUserInfoInfo() {
        var resp = send(HttpMethod.GET, "/v1/user/info", null, null, "Authorization", "user");

        assertEquals(200, resp.status());
        assertEquals("""
                {"roles":["user"],"userId":"user","userClaims":{"title":["Manager"]},"costObject":"ORG","costObjectSource":"default",\
                "userDisplayName":"user user"}""", resp.body());
    }

    @Test
    public void testUserInfoWithoutDisplayName() {
        var resp = send(HttpMethod.GET, "/v1/user/info", null, null, "Authorization", "azp:svc");

        assertEquals(200, resp.status());
        assertEquals("""
                {"roles":[],"userId":"svc","userClaims":{"azp":["svc"]},"costObject":"ORG","costObjectSource":"default"}""", resp.body());
    }

    @Test
    public void testJwtUserInfoWithProject() {
        var resp = send(HttpMethod.GET, "/v1/user/info", null, null, "Authorization", "user@projA");

        assertEquals(200, resp.status());
        assertEquals("""
                {"roles":["user"],"userId":"user","userClaims":{"title":["Manager"]},"project":"projA","costObject":"projA",\
                "costObjectSource":"claim","userDisplayName":"user user"}""", resp.body());
    }

    @Test
    public void testPerRequestKeyOfJwtCallerWithProject() {
        ApiKeyData key = new ApiKeyData();
        key.setExtractedClaims(createClaims("user", "projA"));
        key.setSourceDeployment("testapp");
        key.setTraceId("trace-id");
        apiKeyStore.assignPerRequestApiKey(key);

        var resp = send(HttpMethod.GET, "/v1/user/info", null, null, "api-key", key.getPerRequestKey());

        assertEquals(200, resp.status(), resp.body());
        JsonObject body = new JsonObject(resp.body());
        assertEquals("projA", body.getString("project"));
        assertEquals("user", body.getString("userId"));
        assertEquals("projA", body.getString("costObject"));
        assertEquals("claim", body.getString("costObjectSource"));
    }
}
