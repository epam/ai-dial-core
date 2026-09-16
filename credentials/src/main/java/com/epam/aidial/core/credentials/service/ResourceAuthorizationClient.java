package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.credentials.service.metadata.HttpHeadersHandler;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.util.Compression;
import com.google.common.annotations.VisibleForTesting;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ContentType;

import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import javax.annotation.Nullable;

@Slf4j
public class ResourceAuthorizationClient {

    private static final String MCP_SESSION_HEADER = "Mcp-Session-Id";

    private final HttpClient httpClient;
    private final HttpHeadersHandler httpHeadersHandler;

    public ResourceAuthorizationClient(@Nullable ProxySelector proxySelector) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        builder.connectTimeout(Duration.of(5, ChronoUnit.SECONDS));
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        this.httpClient = builder.build();
        this.httpHeadersHandler = new HttpHeadersHandler();
    }

    @SuppressWarnings("unused")
    @VisibleForTesting
    private ResourceAuthorizationClient(HttpClient httpClient, HttpHeadersHandler httpHeadersHandler) {
        this.httpClient = httpClient;
        this.httpHeadersHandler = httpHeadersHandler;
    }

    public <R> R executeGet(String url, Class<R> responseType) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(createRequestConfig())
                .GET()
                .build();
        return execute(request, responseType);
    }

    public <R> R executePost(String url, Object requestPayload, String contentType, Class<R> responseType) {
        return executePost(url, requestPayload, contentType, Map.of(), responseType);
    }

    public <R> R executePost(String url, Object requestPayload, String contentType,
                             Map<String, String> extraHeaders, Class<R> responseType) {
        return execute(buildPost(url, requestPayload, contentType, extraHeaders), responseType);
    }

    private HttpRequest buildPost(String url, Object requestPayload, String contentType, Map<String, String> extraHeaders) {
        String stringPayload;
        if (contentType.equals(ContentType.APPLICATION_JSON.toString())) {
            stringPayload = JsonMapperUtil.convertToString(requestPayload);
        } else {
            stringPayload = requestPayload.toString();
        }

        assert stringPayload != null;
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(createRequestConfig())
                .header("Content-Type", contentType)
                .header("Accept", ContentType.APPLICATION_JSON.toString());
        // setHeader, not header: these replace the defaults above rather than appending a second value
        extraHeaders.forEach(requestBuilder::setHeader);
        return requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(stringPayload, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Sends a request to observe only its status and headers, abandoning the body unread.
     *
     * <p>Discovery probes an MCP endpoint to draw out its 401 challenge. A probe that instead
     * succeeds may be answered with an SSE stream the server holds open indefinitely, and
     * {@link HttpRequest#timeout} does not bound that: it stops once the response headers arrive,
     * long before the body. Reading such a body would hang the calling thread for as long as the
     * peer keeps the stream open, so the body is never requested at all.
     */
    public void executeProbe(String url, Object requestPayload, String contentType, Map<String, String> extraHeaders) {
        HttpRequest request = buildPost(url, requestPayload, contentType, extraHeaders);
        HttpResponse<Void> response = exchange(request, abandonBody());
        response.headers().firstValue(MCP_SESSION_HEADER)
                .ifPresent(sessionId -> terminateProbeSession(url, sessionId));
        int status = response.statusCode();
        if (status != 200 && status != 201) {
            throw errorFor(request, status, response.headers(), "");
        }
    }

    /**
     * A probe that reaches a stateful MCP server opens a session there, and the probe never becomes
     * a real connection - so it closes the session again rather than leaving one behind on every
     * toolset create, update and repair.
     *
     * <p>Best-effort by design: servers on protocol revisions that dropped sessions answer DELETE
     * with 405, and a failure here costs the caller nothing.
     */
    private void terminateProbeSession(String url, String sessionId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(createRequestConfig())
                    .header(MCP_SESSION_HEADER, sessionId)
                    .DELETE()
                    .build();
            httpClient.send(request, abandonBody());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Could not close the MCP session opened by the discovery probe at {}: {}", url, e.getMessage());
        }
    }

    /**
     * A body handler that completes as soon as the response headers are in and cancels the body
     * subscription, so no part of the response body is read or buffered.
     */
    private static HttpResponse.BodyHandler<Void> abandonBody() {
        return responseInfo -> new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<Void> body = new CompletableFuture<>();

            @Override
            public CompletionStage<Void> getBody() {
                return body;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.cancel();
                body.complete(null);
            }

            @Override
            public void onNext(List<ByteBuffer> item) {
                // never requested
            }

            @Override
            public void onError(Throwable throwable) {
                body.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                body.complete(null);
            }
        };
    }

    @SneakyThrows
    private <R> R execute(HttpRequest request, Class<R> responseType) {
        String body = send(request);
        checkOauthError(body, request.uri());
        return JsonMapperUtil.convertToObject(body, responseType);
    }

    private String send(HttpRequest request) {
        HttpResponse<byte[]> response = exchange(request, HttpResponse.BodyHandlers.ofByteArray());

        int status = response.statusCode();
        String body = decodeBody(response);

        if (status != 200 && status != 201) {
            throw errorFor(request, status, response.headers(), body);
        }

        return body;
    }

    private HttpException errorFor(HttpRequest request, int status, java.net.http.HttpHeaders headers, String body) {
        log.warn("Error executing request {}: status {}, response {}", request.uri(), status, body);
        if (status == 401) {
            return new HttpException(HttpStatus.UNAUTHORIZED, "Authorization server returns 401 error code",
                    httpHeadersHandler.convertHttpHeadersToMap(headers), body);
        }
        return new HttpException(HttpStatus.fromStatusCode(status, HttpStatus.INTERNAL_SERVER_ERROR),
                "Authorization server returns error code", Map.of(), body);
    }

    @SneakyThrows
    private <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (ConnectException e) {
            if (hasUnresolvedAddressException(e)) {
                throw new IllegalArgumentException(
                        "Connection failed: The specified endpoint '%s' is invalid or unreachable.".formatted(request.uri()));
            }
            throw new ConnectException("Cannot connect to %s".formatted(request.uri()));
        }
    }

    private static boolean hasUnresolvedAddressException(Throwable ex) {
        while (ex != null) {
            if (ex instanceof UnresolvedAddressException) {
                return true;
            }
            ex = ex.getCause();
        }
        return false;
    }

    // Some OAuth servers (e.g., CDN-fronted) return Content-Encoding: gzip even when the client
    // did not request it. Java's built-in HttpClient does not auto-decompress, so we do it here.
    private static String decodeBody(HttpResponse<byte[]> response) {
        try {
            byte[] decoded = Compression.decodeHttpBody(response.headers().allValues("Content-Encoding"), response.body());
            return decoded == null ? "" : new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private java.time.Duration createRequestConfig() {
        return java.time.Duration.ofSeconds(30);
    }

    /**
     * Some OAuth Authorization Servers return HTTP 200 with an error payload
     * instead of a proper error status code. Detect and handle this case.
     */
    private static void checkOauthError(String body, URI uri) {
        if (body == null || body.isBlank()) {
            return;
        }
        var node = JsonMapperUtil.convertToObject(body, java.util.Map.class);
        if (node != null && node.containsKey("error")) {
            String error = String.valueOf(node.get("error"));
            String description = node.containsKey("error_description")
                    ? String.valueOf(node.get("error_description"))
                    : "no description";
            log.info("OAuth error in 200 response from {}: error={}, description={}", uri, error, description);
            throw new HttpException(HttpStatus.BAD_REQUEST, "Authorization server returned error: %s (%s)".formatted(error, description),
                    Map.of(), body);
        }
    }
}
