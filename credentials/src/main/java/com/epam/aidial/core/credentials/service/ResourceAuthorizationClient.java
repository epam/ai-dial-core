package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.credentials.service.metadata.HttpHeadersHandler;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.tracing.BlockingCallTracer;
import com.epam.aidial.core.storage.util.Compression;
import com.google.common.annotations.VisibleForTesting;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ContentType;

import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import javax.annotation.Nullable;

@Slf4j
public class ResourceAuthorizationClient {

    private final HttpClient httpClient;
    private final HttpHeadersHandler httpHeadersHandler;
    private final BlockingCallTracer tracing;

    public ResourceAuthorizationClient(@Nullable ProxySelector proxySelector, BlockingCallTracer tracing) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        builder.connectTimeout(Duration.of(5, ChronoUnit.SECONDS));
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        this.httpClient = builder.build();
        this.httpHeadersHandler = new HttpHeadersHandler();
        this.tracing = tracing;
    }

    @SuppressWarnings("unused")
    @VisibleForTesting
    private ResourceAuthorizationClient(HttpClient httpClient, HttpHeadersHandler httpHeadersHandler, BlockingCallTracer tracing) {
        this.httpClient = httpClient;
        this.httpHeadersHandler = httpHeadersHandler;
        this.tracing = tracing;
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
        extraHeaders.forEach(requestBuilder::header);
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(stringPayload, StandardCharsets.UTF_8))
                .build();

        return execute(request, responseType);
    }

    private <R> R execute(HttpRequest request, Class<R> responseType) {
        return tracing.trace("oauth.request", () -> {
            Span span = BlockingCallTracer.currentSpan();
            span.setAttribute("http.request.method", request.method());
            span.setAttribute("server.address", request.uri().getHost());
            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (ConnectException e) {
                // the message ends up on the span, so the query string is left out
                String endpoint = request.uri().toString().split("\\?", 2)[0];
                if (hasUnresolvedAddressException(e)) {
                    throw new IllegalArgumentException(
                            "Connection failed: The specified endpoint '%s' is invalid or unreachable.".formatted(endpoint), e);
                }
                ConnectException error = new ConnectException("Cannot connect to %s".formatted(endpoint));
                error.initCause(e);
                throw error;
            }

            int status = response.statusCode();
            span.setAttribute("http.response.status_code", status);
            String body = decodeBody(response);

            if (status != 200 && status != 201) {
                log.warn("Error executing request {}: status {}, response {}",
                        request.uri(), response.statusCode(), body);
                if (status == 401) {
                    throw new HttpException(HttpStatus.UNAUTHORIZED, "Authorization server returns 401 error code",
                            httpHeadersHandler.convertHttpHeadersToMap(response.headers()), body);
                } else {
                    throw new HttpException(HttpStatus.fromStatusCode(status, HttpStatus.INTERNAL_SERVER_ERROR),
                            "Authorization server returns error code", Map.of(), body);
                }
            }

            checkOauthError(body, request.uri());

            return JsonMapperUtil.convertToObject(body, responseType);
        });
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
