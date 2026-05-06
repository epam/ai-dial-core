package com.epam.aidial.core.server;

import com.epam.aidial.core.mcp.client.DialClient;
import com.epam.aidial.core.mcp.client.DialResponse;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpDialClientLoopbackTest extends ResourceBaseTest {

    @Test
    void getBucketRoundTripsThroughLoopback() throws Exception {
        Vertx vertx = dial.getVertx();
        DialClient dialClient = new DialClient(vertx, vertx.getOrCreateContext(), "http://localhost:" + serverPort);

        DialResponse response = dialClient.request(
                        HttpMethod.GET,
                        "/v1/bucket",
                        Map.of("api-key", "proxyKey1"),
                        Map.of(),
                        null)
                .toFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(200, response.statusCode());
        assertNotNull(response.body());
        assertTrue(response.body().contains("bucket"), () -> "Response body: " + response.body());
    }
}
