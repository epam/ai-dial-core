package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.util.ProxyUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigApiTest extends ResourceBaseTest {

    @Test
    public void testReloadConfig_AccessDenied() {
        var resp = operationRequest("/v1/ops/config/reload", null, "api-key", "proxyKey1");
        assertEquals(403, resp.status());

        resp = operationRequest("/v1/ops/config/reload", null, "Authorization", "user");
        assertEquals(403, resp.status());
    }

    @Test
    public void testReloadConfig_Success() {
        var resp = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, resp.status());
        Config config = ProxyUtil.convertToObject(resp.body(), Config.class);
        assertNotNull(config);
        for (var model : config.getModels().values()) {
            for (var upstream : model.getUpstreams()) {
                assertNull(upstream.getKey());
            }
        }
        assertTrue(config.getKeys().isEmpty());
    }
}
