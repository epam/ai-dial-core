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
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import redis.embedded.RedisServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
                    .bind("127.0.0.1")
                    .setting("maxmemory 16M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            redis.start();

            client = HttpClients.createDefault();

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
                          "jclouds.filesystem.basedir": %s
                        }
                      },
                      "redis": {
                        "singleServerConfig": {
                          "address": "redis://localhost:16370"
                        }
                      },
                      "resources": {
                        "syncPeriod": 1000,
                        "syncDelay": 1000,
                        "cacheExpiration": 1000,
                        "heartbeatPeriod": 1000
                      },
                      "applications": {
                        "controllerEndpoint": "http://localhost:17321",
                        "checkDelay": 1000,
                        "checkPeriod": 1000
                      }
                    }
                    """.formatted(Json.encode(testDir.toString()));

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
        assertEquals(status, response.status(), () -> "Actual response body: " + response.body());
    }

    static void verify(Response response, int status, String body) {
        assertEquals(status, response.status(), () -> "Actual response body: " + response.body());
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
        verifyJsonNotExact(body, response.body);
    }

    static void verifyJsonNotExact(String expected, String actual) {
        try {
            JsonNode expectedTree = ProxyUtil.MAPPER.readTree(expected);
            JsonNode actualTree = ProxyUtil.MAPPER.readTree(actual);

            if (new NotExactComparator().compare(expectedTree, actualTree) != 0) {
                Assertions.assertEquals(expectedTree.toPrettyString(), actualTree.toPrettyString());
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
            request = new HttpPut(uri);
        } else if (method == HttpMethod.DELETE) {
            request = new HttpDelete(uri);
        } else if (method == HttpMethod.POST) {
            request = new HttpPost(uri);
        } else if (method == HttpMethod.HEAD) {
            request = new HttpHead(uri);
        } else {
            throw new IllegalArgumentException("Unsupported method: " + method);
        }

        for (int i = 0; i < headers.length; i += 2) {
            String key = headers[i];
            String value = headers[i + 1];
            request.setHeader(key, value);
        }

        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        if (body != null) {
            request.setEntity(new StringEntity(body));
        }

        return client.execute(request, response -> {
            int status = response.getCode();
            String answer = (response.getEntity() == null) ? null : EntityUtils.toString(response.getEntity());
            Map<String, String> responseHeaders = new HashMap<>();

            for (Header header : response.getHeaders()) {
                responseHeaders.put(header.getName(), header.getValue());
            }

            return new Response(status, answer, responseHeaders);
        });
    }

    @SneakyThrows
    Response upload(HttpMethod method, String path, String queryParams, String body, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + path + (queryParams != null ? "?" + queryParams : "");
        HttpUriRequest request;

        if (method == HttpMethod.PUT) {
            request = new HttpPut(uri);
        } else if (method == HttpMethod.POST) {
            request = new HttpPost(uri);
        } else {
            throw new IllegalArgumentException("Unsupported method: " + method);
        }

        for (int i = 0; i < headers.length; i += 2) {
            String key = headers[i];
            String value = headers[i + 1];
            request.setHeader(key, value);
        }

        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }

        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.setMode(HttpMultipartMode.LEGACY);
            builder.setCharset(StandardCharsets.UTF_8);
            builder.addBinaryBody("text", bytes, ContentType.TEXT_PLAIN, "file.txt");
            request.setEntity(builder.build());
        }

        return client.execute(request, response -> {
            int status = response.getCode();
            String answer = EntityUtils.toString(response.getEntity());
            Map<String, String> responseHeaders = new HashMap<>();

            for (Header header : response.getHeaders()) {
                responseHeaders.put(header.getName(), header.getValue());
            }

            return new Response(status, answer, responseHeaders);
        });
    }

    @SneakyThrows
    EventStream subscribe(String body, String... headers) {
        String uri = "http://127.0.0.1:" + serverPort + "/v1/ops/resource/subscribe";
        CompletableFuture<HttpClientRequest> requestFuture = new CompletableFuture<>();

        dial.getClient().request(new RequestOptions().setAbsoluteURI(uri).setMethod(HttpMethod.POST))
                .onSuccess(requestFuture::complete)
                .onFailure(requestFuture::completeExceptionally);

        HttpClientRequest request = requestFuture.get(10, TimeUnit.SECONDS);

        for (int i = 0; i < headers.length; i += 2) {
            String key = headers[i];
            String value = headers[i + 1];
            request.putHeader(key, value);
        }

        if (!request.headers().contains("authorization") && !request.headers().contains("api-key")) {
            request.putHeader("api-key", "proxyKey1");
        }

        CompletableFuture<HttpClientResponse> responseFuture = new CompletableFuture<>();
        request.send(body)
                .onSuccess(responseFuture::complete)
                .onFailure(responseFuture::completeExceptionally);

        HttpClientResponse response = responseFuture.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals("text/event-stream", response.getHeader(HttpHeaders.CONTENT_TYPE));

        EventStream stream = new EventStream(response);
        response.handler(event -> {
            String text = event.toString(StandardCharsets.UTF_8);
            stream.add(text);
        });

        return stream;
    }

    record Response(int status, String body, Map<String, String> headers) {
        public boolean ok() {
            return status() == 200;
        }
    }

    static class EventStream implements AutoCloseable {
        AtomicBoolean active = new AtomicBoolean(true);
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        Semaphore heartbeats = new Semaphore(0);
        HttpClientResponse response;

        private EventStream(HttpClientResponse response) {
            this.response = response;
        }

        private void add(String event) {
            if (active.get()) {
                if (event.startsWith("data:")) {
                    Assertions.assertTrue(event.endsWith("\n\n"));
                    String message = event.substring("data:".length(), event.length() - "\n\n".length()).trim();
                    events.add(message);
                } else if (event.startsWith(":")) {
                    heartbeats.release();
                } else {
                    Assertions.fail("Expected either a data chunk or a comment, but got: " + event);
                }
            }
        }

        @SneakyThrows
        public String take() {
            String event = events.poll(10, TimeUnit.SECONDS);
            assertNotNull(event, "No event received");
            return event;
        }

        public int peekHeartbeats() {
            return heartbeats.availablePermits();
        }

        @SneakyThrows
        public boolean takeHeartbeat(long timeout, TimeUnit timeUnit) {
            return heartbeats.tryAcquire(timeout, timeUnit);
        }

        @Override
        @SneakyThrows
        public void close() {
            active.set(false);
            response.request().reset();
            Assertions.assertIterableEquals(List.of(), events);
        }
    }

    private static class NotExactComparator implements Comparator<JsonNode> {

        @Override
        public int compare(JsonNode expected, JsonNode actual) {
            if (expected == null && actual != null) {
                return -1;
            }

            if (expected != null && actual == null) {
                return -1;
            }

            if (expected.isTextual() && expected.asText().equals("@ignore")) {
                return 0;
            }

            if (expected.isObject() && actual.isObject()) {
                if (actual.size() != expected.size()) {
                    return -1;
                }

                for (Iterator<String> iterator = actual.fieldNames(); iterator.hasNext(); ) {
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
