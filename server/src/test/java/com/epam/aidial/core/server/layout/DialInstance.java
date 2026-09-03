package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.AiDial;
import com.epam.aidial.core.server.AiDialLifecycle;
import com.epam.aidial.core.server.FileUtil;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.security.AccessTokenValidator;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.mockito.Mockito;
import redis.embedded.RedisServer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * One full DIAL stack — embedded Redis, filesystem blob store, HTTP server — brought up under a chosen
 * storage layout and driven over HTTP.
 *
 * <p>This deliberately does not extend {@code ResourceBaseTest}: the layout is process-wide static state chosen
 * at start-up, so comparing layouts means two boots inside one test method, which a {@code @BeforeEach} base
 * class cannot express. Both boots are given the same fixed clock and the same id generator sequence, so any
 * difference in a response is attributable to the layout rather than to timing or generated identifiers.
 */
public class DialInstance implements AutoCloseable {

    /**
     * Role the global-reader rule matches. The rule is off unless {@code access.globalReader} is configured,
     * so the harness configures it — otherwise the differ would compare a rule that can never fire.
     */
    public static final String GLOBAL_READER_ROLE = "layout-diff-global-reader";

    private static final String ID_SEED = "0";
    private static final long FIRST_ID = 123;

    private final AtomicLong nextId = new AtomicLong(FIRST_ID);

    private final Path dataDir;
    private final RedisServer redis;
    private final AiDial dial;
    private final CloseableHttpClient client;

    @Getter
    private final String name;
    private final int port;

    @SneakyThrows
    public DialInstance(String name, JsonObject layoutSettings, int redisPort) {
        this.name = name;
        this.dataDir = FileUtil.resolveRes("layout-diff-" + name);
        FileUtil.deleteDir(dataDir);
        FileUtil.createDir(dataDir.resolve("test"));

        this.redis = RedisServer.newRedisServer()
                .port(redisPort)
                .bind("127.0.0.1")
                .onShutdownForceStop(true)
                .setting("maxmemory 16M")
                .setting("maxmemory-policy volatile-lfu")
                .build();

        try {
            redis.start();
            // No transparent content decoding: it would decompress a response body and strip the
            // content-encoding header before the comparison sees either, hiding exactly the class of
            // divergence a migration that loses blob metadata produces.
            this.client = HttpClientBuilder.create().disableAutomaticRetries().disableContentCompression().build();
            this.dial = start(layoutSettings, redisPort);
            this.port = dial.getServer().actualPort();
        } catch (Throwable e) {
            closeQuietly();
            throw e;
        }
    }

    private AiDial start(JsonObject layoutSettings, int redisPort) throws Exception {
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
                      "address": "redis://localhost:%d"
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
                  },
                  "codeInterpreter" : {
                    "sessionImage": "fake.image"
                  }
                }
                """.formatted(Json.encode(dataDir.toString()), redisPort);

        JsonObject settings = AiDial.settings()
                .mergeIn(new JsonObject(overrides), true)
                .mergeIn(new JsonObject().put("storageLayout", layoutSettings), true)
                .mergeIn(new JsonObject().put("access", new JsonObject().put("globalReader", new JsonObject()
                        .put("rules", new JsonArray().add(new JsonObject()
                                .put("source", "roles")
                                .put("function", "EQUAL")
                                .put("targets", new JsonArray().add(GLOBAL_READER_ROLE)))))), true);

        AiDial instance = new AiDial();
        instance.setSettings(settings);
        instance.setGenerator(() -> ID_SEED + nextId.getAndIncrement());
        instance.setClock(() -> 0L);
        instance.setAccessTokenValidator(claimsValidator());
        AiDialLifecycle.start(instance);
        return instance;
    }

    private static Set<ResourceAccessType> accessTypes(List<String> permissions) {
        return permissions.stream().map(ResourceAccessType::valueOf).collect(Collectors.toSet());
    }

    private static AccessTokenValidator claimsValidator() {
        AccessTokenValidator validator = Mockito.mock(AccessTokenValidator.class);
        Mockito.when(validator.extractClaims(Mockito.any())).thenAnswer(invocation -> {
            String authorization = invocation.getArgument(0);
            if (authorization == null) {
                return Future.succeededFuture();
            }

            ObjectNode claims = ProxyUtil.MAPPER.createObjectNode();
            claims.put("title", "Manager");
            return Future.succeededFuture(new ExtractedClaims(authorization, List.of(authorization),
                    authorization, claims, null, authorization + " user"));
        });
        return validator;
    }

    /**
     * Issues the api key an application-shaped caller uses. Four of the eleven permission rules only fire for
     * a caller that is an application rather than a person, and a per-request key is what makes one; it can
     * only be minted against a live instance, so the corpus declares the shape and this fills it in.
     */
    public String issuePerRequestKey(AccessMatrix.Subject subject) {
        ApiKeyData original = dial.getProxy().getApiKeyStore().getApiKeyData(subject.apiKey(), null).result();

        ApiKeyData perRequest = new ApiKeyData();
        perRequest.setOriginalKey(original.getOriginalKey());
        perRequest.setExtractedClaims(original.getExtractedClaims());
        perRequest.setSourceDeployment(subject.perRequest().sourceDeployment());
        perRequest.setTraceId("layout-diff-trace");

        Map<String, List<String>> attach = subject.perRequest().attach();
        if (attach != null) {
            Map<String, AutoSharedData> attached = new LinkedHashMap<>();
            attach.forEach((url, permissions) -> attached.put(url, new AutoSharedData(accessTypes(permissions))));
            perRequest.setAttachedDeployments(attached);
        }

        Map<String, List<String>> share = subject.perRequest().share();
        if (share != null) {
            Map<String, PerRequestSharedData> shared = new LinkedHashMap<>();
            share.forEach((url, permissions) -> shared.put(url, new PerRequestSharedData(accessTypes(permissions))));
            perRequest.setPerRequestSharedResources(shared);
        }

        dial.getProxy().getApiKeyStore().assignPerRequestApiKey(perRequest);
        return perRequest.getPerRequestKey();
    }

    /**
     * The bucket the given api key writes into. Derived from the caller identity and the encryption secret,
     * neither of which the layout touches, so the two instances are expected to report the same value —
     * {@code LayoutReplayDiffTest} asserts that before it trusts any other comparison.
     */
    public String bucket(String apiKey) {
        RecordedResponse response = send("GET", "/v1/bucket", null, null, Map.of("api-key", apiKey), null);
        if (response.status() != 200) {
            throw new IllegalStateException("Cannot resolve bucket for " + apiKey + ": " + response.body());
        }
        return new JsonObject(response.body()).getString("bucket");
    }

    @SneakyThrows
    public RecordedResponse send(String method, String path, String query, String body,
                                 Map<String, String> headers, Multipart multipart) {
        String uri = "http://127.0.0.1:" + port + path + (query == null ? "" : "?" + query);
        HttpUriRequestBase request = new HttpUriRequestBase(method, URI.create(uri));

        headers.forEach(request::setHeader);
        if (!request.containsHeader("authorization") && !request.containsHeader("api-key")) {
            request.setHeader("api-key", "proxyKey1");
        }
        if (body != null) {
            request.setEntity(multipart == null ? new StringEntity(body) : upload(body, multipart));
        }

        return client.execute(request, response -> {
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for (Header header : response.getHeaders()) {
                // Joined rather than last-wins, or a duplicated header would be invisible to the diff.
                responseHeaders.merge(header.getName().toLowerCase(), header.getValue(), (left, right) -> left + ", " + right);
            }
            String answer = response.getEntity() == null ? null : EntityUtils.toString(response.getEntity());
            return new RecordedResponse(response.getCode(), answer, responseHeaders);
        });
    }

    private static HttpEntity upload(String body, Multipart multipart) {
        return MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.LEGACY)
                .setCharset(StandardCharsets.UTF_8)
                .addBinaryBody("attachment", body.getBytes(StandardCharsets.UTF_8),
                        ContentType.parse(multipart.contentType()), multipart.filename())
                .build();
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            // the instance is going away; a client that will not close cannot affect the comparison
        }

        try {
            if (dial != null) {
                AiDialLifecycle.stop(dial);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot stop the " + name + " instance", e);
        } finally {
            try {
                redis.stop();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot stop redis for the " + name + " instance", e);
            }
            FileUtil.deleteDir(dataDir);
        }
    }
}
