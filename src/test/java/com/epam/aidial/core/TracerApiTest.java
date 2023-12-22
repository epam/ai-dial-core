package com.epam.aidial.core;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
public class TracerApiTest {
    private static AiDial dial;
    private static int serverPort;
    private static Path testDir;

    @BeforeAll
    public static void init() throws Exception {
        // initialize server
        dial = new AiDial();
        testDir = FileUtil.baseTestPath(FileApiTest.class);
        dial.setStorage(FileUtil.buildFsBlobStorage(testDir));
        dial.start();
        serverPort = dial.getServer().actualPort();
    }

    @BeforeEach
    public void setUp() {
        // prepare test directory
        FileUtil.createDir(testDir.resolve("test"));
    }

    @AfterEach
    public void clean() {
        // clean test directory
        FileUtil.deleteDir(testDir);
    }

    @AfterAll
    public static void destroy() {
        // stop server
        dial.stop();
    }

    @Test
    public void testTraceNotFound(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.post(serverPort, "localhost", "/openai/deployments/app/chat/completions")
                .putHeader("Api-key", "proxyKey2")
                .putHeader("content-type", "application/json")
                .putHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(400, response.statusCode());
                        context.completeNow();
                    });
                }));
    }

    @Test
    public void testInvalidTrace(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.post(serverPort, "localhost", "/openai/deployments/app/chat/completions")
                .putHeader("Api-key", "proxyKey2")
                .putHeader("content-type", "application/json")
                .putHeader("traceparent", "00-123-123")
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(400, response.statusCode());
                        context.completeNow();
                    });
                }));
    }

}
