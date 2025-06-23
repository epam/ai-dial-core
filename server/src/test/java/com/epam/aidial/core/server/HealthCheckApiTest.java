package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HealthCheckApiTest extends ResourceBaseTest {

    @Test
    public void testHealthCheck() {
        var resp = send(HttpMethod.GET, "/health", null, null);

        assertEquals(200, resp.status());
    }

}
