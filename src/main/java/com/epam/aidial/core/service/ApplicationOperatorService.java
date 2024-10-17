package com.epam.aidial.core.service;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
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
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A web client to Application Controller Web Service that manages deployments for applications with functions.
 */
class ApplicationOperatorService {

    private final HttpClient client;
    private final String endpoint;
    private final long timeout;

    public ApplicationOperatorService(HttpClient client, JsonObject settings) {
        this.client = client;
        this.endpoint = settings.getString("controllerEndpoint");
        this.timeout = settings.getLong("controllerTimeout", 240000L);
    }

    boolean isActive() {
        return endpoint != null;
    }

    void verifyActive() {
        if (!isActive()) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "The application controller is not available");
        }
    }

    void createApplicationImage(ProxyContext context, Application.Function function) {
        callController(HttpMethod.POST, "/v1/image/" + function.getId(),
                request -> {
                    String apiKey = context.getRequest().getHeader(Proxy.HEADER_API_KEY);
                    String auth = context.getRequest().getHeader(HttpHeaders.AUTHORIZATION);

                    if (apiKey != null) {
                        request.putHeader(Proxy.HEADER_API_KEY, apiKey);
                    }

                    if (auth != null) {
                        request.putHeader(HttpHeaders.AUTHORIZATION, auth);
                    }

                    request.putHeader(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);

                    CreateImageRequest body = new CreateImageRequest(function.getRuntime(), function.getTargetFolder());
                    return ProxyUtil.convertToString(body);
                },
                (response, body) -> convertServerSentEvent(body, EmptyResponse.class));
    }

    String createApplicationDeployment(ProxyContext context, Application.Function function) {
        CreateDeploymentResponse deployment = callController(HttpMethod.POST, "/v1/deployment/" + function.getId(),
                request -> {
                    String apiKey = context.getRequest().getHeader(Proxy.HEADER_API_KEY);
                    String auth = context.getRequest().getHeader(HttpHeaders.AUTHORIZATION);

                    if (apiKey != null) {
                        request.putHeader(Proxy.HEADER_API_KEY, apiKey);
                    }

                    if (auth != null) {
                        request.putHeader(HttpHeaders.AUTHORIZATION, auth);
                    }

                    request.putHeader(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);

                    CreateDeploymentRequest body = new CreateDeploymentRequest(function.getEnv());
                    return ProxyUtil.convertToString(body);
                },
                (response, body) -> convertServerSentEvent(body, CreateDeploymentResponse.class));

        return deployment.url();
    }

    void deleteApplicationImage(Application.Function function) {
        callController(HttpMethod.DELETE, "/v1/image/" + function.getId(),
                request -> null,
                (response, body) -> convertServerSentEvent(body, EmptyResponse.class));
    }

    void deleteApplicationDeployment(Application.Function function) {
        callController(HttpMethod.DELETE, "/v1/deployment/" + function.getId(),
                request -> null,
                (response, body) -> convertServerSentEvent(body, EmptyResponse.class));
    }

    Application.Logs getApplicationLogs(Application.Function function) {
        return callController(HttpMethod.GET, "/v1/deployment/" + function.getId() + "/logs",
                request -> null,
                (response, body) -> ProxyUtil.convertToObject(body, Application.Logs.class));
    }

    @SneakyThrows
    private <R> R callController(HttpMethod method, String path,
                                 Function<HttpClientRequest, String> requestMapper,
                                 BiFunction<HttpClientResponse, String, R> responseMapper) {
        verifyActive();

        CompletableFuture<R> resultFuture = new CompletableFuture<>();
        AtomicReference<HttpClientRequest> requestReference = new AtomicReference<>();
        AtomicReference<HttpClientResponse> responseReference = new AtomicReference<>();

        RequestOptions requestOptions = new RequestOptions()
                .setMethod(method)
                .setAbsoluteURI(endpoint + path)
                .setIdleTimeout(timeout);

        client.request(requestOptions)
                .compose(request -> {
                    requestReference.set(request);
                    String body = requestMapper.apply(request);
                    return request.send((body == null) ? "" : body);
                })
                .compose(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Controller API error. Code: " + response.statusCode());
                    }

                    responseReference.set(response);
                    return response.body();
                })
                .map(buffer -> {
                    HttpClientResponse response = responseReference.get();
                    String body = buffer.toString(StandardCharsets.UTF_8);
                    return responseMapper.apply(response, body);
                })
                .onSuccess(resultFuture::complete)
                .onFailure(resultFuture::completeExceptionally);

        try {
            return resultFuture.get(timeout, TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            if (e instanceof TimeoutException) {
                HttpClientRequest request = requestReference.get();

                if (request != null) {
                    request.reset();
                }
            }

            if (e instanceof ExecutionException) {
                e = e.getCause();
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

    private record CreateDeploymentRequest(Map<String, String> env) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CreateDeploymentResponse(String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmptyResponse() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorResponse(String message) {
    }
}
