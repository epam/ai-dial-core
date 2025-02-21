package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConfigApiTest extends ResourceBaseTest {

    @Test
    public void testReloadConfig() {
        var resp = send(HttpMethod.POST, "/v1/ops/config/reload", null, null, "api-key", "proxyKey1");
        assertEquals(200, resp.status());
    }
}
