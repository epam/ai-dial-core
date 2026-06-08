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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // ===== TEMPORARY DIAGNOSTIC — REMOVE before merge (investigating Snowflake invalid_client #1527) =====
    // Dedicated logger for the request/response dump below. OFF by default. The dump prints the
    // Authorization header (Base64(client_id:client_secret)) — SENSITIVE. The logger name is OUTSIDE the
    // com.epam.aidial.* hierarchy so raising the credentials package to DEBUG will NOT enable it; only
    // explicitly setting AIDIAL_AUTH_DUMP_LEVEL=DEBUG (see logback.xml) does. Never leave on in production.
    private static final Logger AUTH_DUMP = LoggerFactory.getLogger("DIAL_AUTH_REQUEST_DUMP");
    // ===== END TEMPORARY DIAGNOSTIC =====

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

        // ===== TEMPORARY DIAGNOSTIC — REMOVE before merge (investigating Snowflake invalid_client #1527) =====
        // Full outbound request dump (method, URL, headers, body), mirroring a Postman console capture.
        // The Authorization header carries Base64(client_id:client_secret) — SENSITIVE.
        if (AUTH_DUMP.isDebugEnabled()) {
            AUTH_DUMP.debug("[TEMP outbound request] {} {} | headers={} | body={}",
                    request.method(), request.uri(), request.headers().map(), stringPayload);
        }
        // ===== END TEMPORARY DIAGNOSTIC =====

        return execute(request, responseType);
    }

    @SneakyThrows
    private <R> R execute(HttpRequest request, Class<R> responseType) {
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // ===== TEMPORARY DIAGNOSTIC — REMOVE before merge (investigating Snowflake invalid_client #1527) =====
            // Response + TLS dump (mirrors Postman's Network/Response sections). Confirms which endpoint
            // actually answered (peer cert) and what came back. Body is logged separately on error only.
            if (AUTH_DUMP.isDebugEnabled()) {
                AUTH_DUMP.debug("[TEMP inbound response] {} {} status={} | tls=[{}] | headers={}",
                        request.method(), request.uri(), response.statusCode(), describeTls(response), response.headers().map());
            }
            // ===== END TEMPORARY DIAGNOSTIC =====

            int status = response.statusCode();
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

    // ===== TEMPORARY DIAGNOSTIC — REMOVE before merge (investigating Snowflake invalid_client #1527) =====
    // Summarizes the negotiated TLS session: protocol, cipher and the peer (server) certificate subject.
    // The peer subject confirms exactly which Snowflake endpoint answered (e.g. a privatelink SAN).
    private static String describeTls(HttpResponse<?> response) {
        return response.sslSession().map(session -> {
            String peer;
            try {
                peer = session.getPeerPrincipal().getName();
            } catch (Exception e) {
                peer = "unknown (" + e.getClass().getSimpleName() + ")";
            }
            return "protocol=" + session.getProtocol() + ", cipher=" + session.getCipherSuite() + ", peer=" + peer;
        }).orElse("no-tls");
    }
    // ===== END TEMPORARY DIAGNOSTIC =====

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
