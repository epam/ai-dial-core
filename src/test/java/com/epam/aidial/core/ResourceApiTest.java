package com.epam.aidial.core;

import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.embedded.RedisServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
class ResourceApiTest {

    private static RedisServer redis;
    private static AiDial dial;
    private static Path testDir;
    private String bucket;

    @BeforeAll
    static void init() throws Exception {
        try {
            testDir = FileUtil.baseTestPath(ResourceApiTest.class);
            redis = RedisServer.builder()
                    .port(16370)
                    .setting("bind 127.0.0.1")
                    .setting("maxmemory 4M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            redis.start();

            String overrides = """
                    { "storage": {
                        "bucket": "test",
                        "provider": "filesystem",
                        "identity": "access-key",
                        "credential": "secret-key",
                        "overrides": {
                          "jclouds.filesystem.basedir": "%s"
                        }
                      },
                      "redis": {
                        "singleServerConfig": {
                          "address": "redis://localhost:16370"
                        }
                      },
                      "resources": {
                        "syncPeriod": 100,
                        "syncDelay": 100,
                        "cacheExpiration": 100
                      }
                    }
                    """.formatted(testDir);

            JsonObject settings = AiDial.settings()
                    .mergeIn(new JsonObject(overrides), true);

            dial = new AiDial();
            dial.setSettings(settings);
            dial.start();
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterAll
    static void destroy() {
        try {
            if (dial != null) {
                dial.stop();
            }
        } finally {
            if (redis != null) {
                redis.stop();
            }
        }
    }

    @BeforeEach
    void setUp() {
        FileUtil.createDir(testDir.resolve("test"));
        Response response = send(HttpMethod.GET, "/v1/bucket", "");
        assertEquals(response.response().statusCode(), 200);
        bucket = new JsonObject(response.body).getString("bucket");
        assertNotNull(bucket);
    }

    @AfterEach
    void tearDown() {
        FileUtil.deleteDir(testDir);
    }

    @Test
    void testWorkflow() {
        Response response = request(HttpMethod.GET, "/folder/conversation");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = metadata("/folder/");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/");

        response = request(HttpMethod.PUT, "/folder/conversation", "12345");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        response = request(HttpMethod.GET, "/folder/conversation");
        verify(response, 200, "12345");

        response = request(HttpMethod.PUT, "/folder/conversation", "123456");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder");

        response = request(HttpMethod.GET, "/folder/conversation");
        verify(response, 200, "123456");

        response = metadata("/folder/");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation\"");

        response = metadata("/");
        verifyNotExact(response, 200, "\"url\":\"conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/\"");

        response = request(HttpMethod.DELETE, "/folder/conversation");
        verify(response, 200, "");

        response = request(HttpMethod.GET, "/folder/conversation");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = request(HttpMethod.DELETE, "/folder/conversation");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/conversation");

        response = metadata("/folder/");
        verify(response, 404, "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/folder/");
    }

    @Test
    void testLimit() {
        Response response = request(HttpMethod.PUT, "/folder/big", "1".repeat(1024 * 1024 + 1));
        verify(response, 413, "Resource size: 1048577 exceeds max limit: 1048576");
    }

    @Test
    void testRandom() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 1000; i++) {
            int type = random.nextInt(0, 3);
            int id = random.nextInt(0, 200);
            int size = random.nextInt(0, 1024);
            String body = "a".repeat(size);
            String path = "/folder1/folder2/conversation" + id;
            String notFound = "Not found: conversations/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST" + path;

            if (type == 0) {
                Response resource = request(HttpMethod.PUT, path, body);
                verifyNotExact(resource, 200, path);

                resource = request(HttpMethod.GET, path);
                verify(resource, 200, body);
                continue;
            }

            if (type == 1) {
                Response response = request(HttpMethod.DELETE, path);
                verify(response, response.ok() ? 200 : 404, response.ok() ? "" : notFound);
                continue;
            }

            if (type == 2) {
                Response response = request(HttpMethod.GET, path);
                if (response.status() == 200) {
                    body = response.body() + body;
                    Response resource = request(HttpMethod.PUT, path, body);
                    verifyNotExact(resource, 200, path);

                    resource = request(HttpMethod.GET, path);
                    verify(resource, 200, body);
                } else {
                    verify(response, 404, notFound);
                }
                continue;
            }

            throw new IllegalStateException("Unreachable code");
        }
    }

    private static void verify(Response response, int status, String body) {
        assertEquals(status, response.status());
        assertEquals(body, response.body());
    }

    private static void verifyNotExact(Response response, int status, String part) {
        assertEquals(status, response.status());
        if (!response.body.contains(part)) {
            Assertions.fail("Body: " + response.body + ". Does not contains: " + part);
        }
    }

    private Response request(HttpMethod method, String resource) {
        return request(method, resource, "");
    }

    private Response request(HttpMethod method, String resource, String body) {
        return send(method, "/v1/conversations/" + bucket + resource, body);
    }

    private Response metadata(String resource) {
        return send(HttpMethod.GET, "/v1/metadata/conversations/" + bucket + resource, "");
    }

    @SneakyThrows
    private Response send(HttpMethod method, String uri, String body) {
        CompletableFuture<Response> result = new CompletableFuture<>();
        dial.getClient().request(method, dial.getServer().actualPort(), "127.0.0.1", uri)
                .compose(request -> {
                    request.headers().add("api-key", "proxyKey1");
                    return request.send(body);
                })
                .compose(response -> response.body().map(bytes -> new Response(response, bytes.toString(StandardCharsets.UTF_8))))
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally);

        return result.get(15, TimeUnit.SECONDS);
    }

    private record Response(HttpClientResponse response, String body) {
        public int status() {
            return response.statusCode();
        }

        public boolean ok() {
            return status() == 200;
        }
    }
}