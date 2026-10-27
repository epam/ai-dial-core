package com.epam.aidial.core.server;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpServerInstancesTest {

    @Test
    void testStartFailsBelowOneInstance() throws Exception {
        JsonObject settings = AiDial.settings();
        settings.getJsonObject("server").put("instances", 0);
        AiDial dial = new AiDial();
        dial.setSettings(settings);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, dial::start);

        assertTrue(error.getMessage().contains("server.instances"), error.getMessage());
        // the check runs before Vert.x starts, so nothing is left listening
        assertNull(dial.getVertx());
    }
}
