package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A web client to Application Controller Web Service that manages deployments for applications with functions.
 */
public class ApplicationOperatorService {

    private final HttpClient client;
    private final String endpoint;
    private final long timeout;
    private final long connectTimeout;

    public ApplicationOperatorService(HttpClient client, JsonObject settings) {
        this.client = client;
        this.endpoint = "http://dial-app-controller-test.dial-development.svc.cluster.local";
        this.timeout = settings.getLong("controllerTimeout", 240000L);
        this.connectTimeout = settings.getLong("controllerConnectTimeout", 10000L);
    }

    public boolean isActive() {
        return endpoint != null;
    }

    public void verifyActive() {
        if (!isActive()) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "The application controller is not available");
        }
    }

    void createApplicationImage(Application.Function function, ApiKeyData apiKey) {
        callController(HttpMethod.POST, "/v1/image/" + function.getId(),
                request -> {
                    request.putHeader(Proxy.HEADER_API_KEY, apiKey.getPerRequestKey());
                    request.putHeader(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);

                    CreateImageRequest body = new CreateImageRequest(function.getRuntime(), function.getTargetFolder());
                    return ProxyUtil.convertToString(body);
                },
                body -> convertServerSentEvent(body, EmptyResponse.class));
    }

    String createApplicationDeployment(Application.Function function) {
        CreateDeploymentResponse deployment = callController(HttpMethod.POST, "/v1/deployment/" + function.getId(),
                request -> {
                    request.putHeader(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
                    CreateDeploymentRequest body = new CreateDeploymentRequest(function.getEnv());
                    return ProxyUtil.convertToString(body);
                },
                body -> convertServerSentEvent(body, CreateDeploymentResponse.class));

        return deployment.url();
    }

    void deleteApplicationImage(Application.Function function) {
        callController(HttpMethod.DELETE, "/v1/image/" + function.getId(),
                request -> null,
                body -> convertServerSentEvent(body, EmptyResponse.class));
    }

    void deleteApplicationDeployment(Application.Function function) {
        callController(HttpMethod.DELETE, "/v1/deployment/" + function.getId(),
                request -> null,
                body -> convertServerSentEvent(body, EmptyResponse.class));
    }

    Application.Logs getApplicationLogs(Application.Function function) {
        return callController(HttpMethod.GET, "/v1/deployment/" + function.getId() + "/logs",
                request -> null,
                body -> ProxyUtil.convertToObject(body, Application.Logs.class));
    }

    public String createCodeInterpreterDeployment(String id, String image) {
        CreateDeploymentResponse deployment = callController(HttpMethod.POST, "/v1/deployment/" + id,
                request -> {
                    request.putHeader(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
                    CreateDeploymentRequest body = new CreateDeploymentRequest(image, 1, 1, 1, Map.of());
                    return ProxyUtil.convertToString(body);
                },
                body -> convertServerSentEvent(body, CreateDeploymentResponse.class));

        return deployment.url();
    }

    public void deleteCodeInterpreterDeployment(String id) {
        callController(HttpMethod.DELETE, "/v1/deployment/" + id,
                request -> null,
                body -> convertServerSentEvent(body, EmptyResponse.class));
    }

    public String createCodeInterpreterSession(String id, String image) {
        CreateSessionResponse session = callController(HttpMethod.POST, "/v1/session/" + id,
                request -> {
                    request.putHeader(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
                    CreateSessionRequest body = new CreateSessionRequest(image);
                    return ProxyUtil.convertToString(body);
                },
                body -> convertServerSentEvent(body, CreateSessionResponse.class));

        return session.url();
    }

    public void deleteCodeInterpreterSession(String id) {
        callController(HttpMethod.DELETE, "/v1/session/" + id,
                request -> null,
                body -> convertServerSentEvent(body, EmptyResponse.class));
    }

    @SneakyThrows
    private <R> R callController(HttpMethod method, String path,
                                 Function<HttpClientRequest, String> requestMapper,
                                 Function<String, R> responseMapper) {
        verifyActive();

        AtomicReference<HttpClientRequest> reference = new AtomicReference<>();

        RequestOptions options = new RequestOptions()
                .setMethod(method)
                .setAbsoluteURI(endpoint + path)
                .setConnectTimeout(connectTimeout)
                .setIdleTimeout(timeout);

        Future<R> future = client.request(options)
                .compose(request -> {
                    reference.set(request);
                    String body = requestMapper.apply(request);
                    return request.send((body == null) ? "" : body)
                            .compose(response -> { // must be inside to eliminate race condition for response.body()
                                if (response.statusCode() != 200) {
                                    throw new IllegalStateException("Controller API error. Code: " + response.statusCode());
                                }

                                return response.body();
                            });
                })
                .map(buffer -> {
                    String body = buffer.toString(StandardCharsets.UTF_8);
                    return responseMapper.apply(body);
                })
                .timeout(timeout, TimeUnit.MILLISECONDS);

        try {
            return Future.await(future);
        } catch (Throwable e) {
            if (e instanceof TimeoutException) {
                HttpClientRequest request = reference.get();

                if (request != null) {
                    request.reset();
                }
            }

            throw e;
        }
    }

    private static <T> T convertServerSentEvent(String body, Class<T> clazz) {
        StringTokenizer tokenizer = new StringTokenizer(body, "\n");

        while (tokenizer.hasMoreTokens()) {
            String line = tokenizer.nextToken().trim();
            if (line.isBlank() || line.startsWith(":")) {
                continue; // empty or comment
            }

            if (!line.startsWith("event:")) {
                throw new IllegalStateException("Invalid response. Invalid line: " + line);
            }

            if (!tokenizer.hasMoreTokens()) {
                throw new IllegalStateException("Invalid response. No line: \"data:\"" + line);
            }

            String event = line.substring("event:".length()).trim();
            line = tokenizer.nextToken().trim();
            String data = line.substring("data:".length()).trim();

            if (event.equals("result")) {
                return ProxyUtil.convertToObject(data, clazz);
            }

            if (event.equals("error")) {
                ErrorResponse error = ProxyUtil.convertToObject(data, ErrorResponse.class);
                throw new IllegalStateException(error == null ? "Unknown error" : error.message());
            }

            throw new IllegalStateException("Invalid response. Invalid event: " + event);
        }

        throw new IllegalStateException("Invalid response. Unexpected end of stream");
    }

    private record CreateImageRequest(String runtime, String sources) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CreateDeploymentRequest(String image, Integer initialScale, Integer minScale, Integer maxScale,
                                           Map<String, String> env) {
        private CreateDeploymentRequest(Map<String, String> env) {
            this(null, null, null, null, env);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CreateDeploymentResponse(String url) {
    }

    private record CreateSessionRequest(String image) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CreateSessionResponse(String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmptyResponse() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorResponse(String message) {
    }
}