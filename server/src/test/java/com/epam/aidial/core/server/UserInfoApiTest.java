package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UserInfoApiTest extends ResourceBaseTest {

    @Test
    public void testApiKeyInfo() {
        var resp = send(HttpMethod.GET, "/v1/user/info", null, null, "api-key", "proxyKey1");

        assertEquals(200, resp.status());
        assertEquals("""
                {"roles":["default"],"project":"EPM-RTC-GPT"}""", resp.body());
    }

    @Test
    public void testUserInfoInfo() {
        var resp = send(HttpMethod.GET, "/v1/user/info", null, null, "Authorization", "user");

        assertEquals(200, resp.status());
        assertEquals("""
                {"roles":["user"],"userId":"user","userClaims":{"title":["Manager"]}}""", resp.body());
    }
}
