package com.epam.aidial.core;

import com.epam.aidial.core.security.AccessTokenValidator;
import com.epam.aidial.core.security.ApiKeyStore;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.security.ExtractedClaims;
import com.epam.aidial.core.service.NotificationService;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import redis.embedded.RedisServer;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ResourceBaseTest {

    public static final String CONVERSATION_BODY_1 = """
            {
            "id": "conversation_id",
            "name": "display_name",
            "model": {"id": "model_id"},
            "prompt": "system prompt",
            "temperature": 1,
            "folderId": "folder1",
            "messages": [],
            "selectedAddons": ["R", "T", "G"],
            "assistantModelId": "assistantId",
            "lastActivityDate": 4848683153
            }
            """;

    public static final String CONVERSATION_BODY_2 = """
            {
            "id": "conversation_id2",
            "name": "display_name2",
            "model": {"id": "model_id2"},
            "prompt": "system prompt2",
            "temperature": 0,
            "folderId": "folder1",
            "messages": [],
            "selectedAddons": [],
            "assistantModelId": "assistantId2",
            "lastActivityDate": 98746886446
            }
            """;

    public static final String PROMPT_BODY = """
                        {
            "id": "prompt_id",
            "name": "prompt",
            "folderId": "folder",
            "content": "content"
            }
            """;

    RedisServer redis;
    AiDial dial;
    Path testDir;
    CloseableHttpClient client;
    String bucket;
    long time = 0;
    long id = 123;

    int serverPort;
    ApiKeyStore apiKeyStore;
    NotificationService notificationService;
    EncryptionService encryptionService;

    AccessTokenValidator validator = Mockito.mock(AccessTokenValidator.class);

    @BeforeEach
    void init() throws Exception {
        try {
            testDir = FileUtil.baseTestPath(ResourceApiTest.class);
            FileUtil.createDir(testDir.resolve("test"));

            redis = RedisServer.newRedisServer()
                    .port(16370)
                    .setting("bind 127.0.0.1")
                    .setting("maxmemory 16M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            redis.start();

            client = HttpClientBuilder.create().build();

            String overrides = """
                    {
                      "client": {
                        "connectTimeout": 5000
                      },
                      "storage": {
                        "bucket": "test",
                        "provider": "filesystem",
                        "identity": "access-key",
                        "credential": "secret-key",
                        "prefix": "test-2",
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

            Mockito.when(validator.extractClaims(Mockito.any()))
                    .thenAnswer(invocation -> {
                        String authorization = invocation.getArgument(0);
                        if (authorization == null) {
                            return Future.succeededFuture();
                        }

                        if (authorization.equals("user") || authorization.equals("admin")) {
                            return Future.succeededFuture(new ExtractedClaims(authorization, List.of(authorization), authorization, Map.of("title", List.of("Manager"))));
                        }

                        return Future.failedFuture("Not authorized");
                    });

            dial = new AiDial();
            dial.setSettings(settings);
            dial.setGenerator(this::generate);
            dial.setClock(() -> time);
            dial.setAccessTokenValidator(validator);
            dial.start();
            serverPort = dial.getServer().actualPort();
            apiKeyStore = dial.getProxy().getApiKeyStore();
            notificationService = dial.getProxy().getNotificationService();
            encryptionService = dial.getProxy().getEncryptionService();

            Response response = send(HttpMethod.GET, "/v1/bucket", null, "");
            assertEquals(response.status, 200);
            bucket = new JsonObject(response.body).getString("bucket");
            assertNotNull(bucket);
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterEach
    void destroy() throws Exception {
        try {
            if (client != null) {
                client.close();
            }

            if (dial != null) {
                dial.stop();
            }
        } finally {
            if (redis != null) {
                redis.stop();
            }

            FileUtil.deleteDir(testDir);
        }
    }

    protected String generate() {
        return "0" + id++;
    }

    static void verify(Response response, int status) {
        assertEquals(status, response.status());
    }

    static void verify(Response response, int status, String body) {
        assertEquals(status, response.status());
        assertEquals(body, response.body());
    }

    static void verifyJson(Response response, int status, String body) {
        assertEquals(status, response.status());
        try {
            assertEquals(ProxyUtil.MAPPER.readTree(body).toPrettyString(), ProxyUtil.MAPPER.readTree(response.body()).toPrettyString());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    static void verifyJsonNotExact(Response response, int status, String body) {
        assertEquals(status, response.status());
        try {
            JsonNode expected = ProxyUtil.MAPPER.readTree(body);
            JsonNode actual = ProxyUtil.MAPPER.readTree(response.body());

            if (new NotExactComparator().compare(expected, actual) != 0) {
                Assertions.assertEquals(expected.toPrettyString(), actual.toPrettyString());
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    static void verifyNotExact(Response response, int status, String part) {
        assertEquals(status, response.status());
        if (!response.body.contains(part)) {
            Assertions.fail("Body: " + response.body + ". Does not contains: " + part);
        }
    }

    Response resourceRequest(HttpMethod method, String resource) {
        return resourceRequest(method, resource, "");
    }

    Response resourceRequest(HttpMethod method, String resource, String body, String... headers) {
        return send(method, "/v1/conversations/" + bucket + resource, null, body, headers);
    }

    Response operationRequest(String path, String body, String... headers) {
        return send(HttpMethod.POST, path, null, body, headers);
    }

    Response metadata(String resource) {
        return send(HttpMethod.GET, "/v1/metadata/conversations/" + bucket + resource, null, "");
    }

    Response send(HttpMethod method, String path) {
        return send(method, path, null, "");
    }

    @SneakyThrows
    Response send(HttpMethod method, String path, String queryParams, String body, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + path + (queryParams != null ? "?" + queryParams : "");
        HttpUriRequest request;

        if (method == HttpMethod.GET) {
            request = new HttpGet(uri);
        } else if (method == HttpMethod.PUT) {
            HttpPut put = new HttpPut(uri);
            put.setEntity(new StringEntity(body));
            request = put;
        } else if (method == HttpMethod.DELETE) {
            request = new HttpDelete(uri);
        } else if (method == HttpMethod.POST) {
            HttpPost post = new HttpPost(uri);
            post.setEntity(new StringEntity(body));
            request = post;
        } else {
            throw new IllegalArgumentException("Unsupported method: " + method);
        }

        boolean isAuthorized = false;

        for (int i = 0; i < headers.length; i += 2) {
            String key = headers[i];
            String value = headers[i + 1];
            request.setHeader(key, value);
            isAuthorized = key.equalsIgnoreCase("authorization") || key.equalsIgnoreCase("api-key");
        }

        if (!isAuthorized) {
            request.setHeader("api-key", "proxyKey1");
        }

        try (CloseableHttpResponse response = client.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            String answer = EntityUtils.toString(response.getEntity());
            return new Response(status, answer);
        }
    }

    record Response(int status, String body) {
        public boolean ok() {
            return status() == 200;
        }
    }

    private static class NotExactComparator implements Comparator<JsonNode> {

        @Override
        public int compare(JsonNode expected, JsonNode actual) {
            if (expected.isTextual() && expected.asText().equals("@ignore")) {
                return 0;
            }

            if (expected.isObject() && actual.isObject()) {
                if (actual.size() != expected.size()) {
                    return -1;
                }

                for (Iterator<String> iterator = actual.fieldNames(); iterator.hasNext();) {
                    String name = iterator.next();
                    JsonNode left = expected.get(name);
                    JsonNode right = actual.get(name);

                    if (compare(left, right) != 0) {
                        return -1;
                    }
                }

                return 0;
            }

            if (expected.isArray() && actual.isArray()) {
                ArrayNode lefts = (ArrayNode) expected;
                ArrayNode rights = (ArrayNode) actual;

                if (lefts.size() != rights.size()) {
                    return -1;
                }

                for (JsonNode left : lefts) {
                    boolean equal = false;

                    for (JsonNode right : rights) {
                        if (compare(left, right) == 0) {
                            equal = true;
                            break;
                        }
                    }

                    if (!equal) {
                        return -1;
                    }
                }

                return 0;
            }

            return expected.equals(actual) ? 0 : -1;
        }
    }
}
