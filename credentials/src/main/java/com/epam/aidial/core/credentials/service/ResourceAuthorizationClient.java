package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.credentials.service.metadata.HttpHeadersHandler;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.storage.http.HttpException;
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
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

@Slf4j
public class ResourceAuthorizationClient {

    private final HttpClient httpClient;
    private final HttpHeadersHandler httpHeadersHandler;

    public ResourceAuthorizationClient(@Nullable ProxySelector proxySelector) {
        HttpClient.Builder builder = HttpClient.newBuilder();
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
        String stringPayload;
        if (contentType.equals(ContentType.APPLICATION_JSON.toString())) {
            stringPayload = JsonMapperUtil.convertToString(requestPayload);
        } else {
            stringPayload = requestPayload.toString();
        }

        assert stringPayload != null;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(createRequestConfig())
                .header("Content-Type", contentType)
                .header("Accept", ContentType.APPLICATION_JSON.toString())
                .POST(HttpRequest.BodyPublishers.ofString(stringPayload, StandardCharsets.UTF_8))
                .build();

        return execute(request, responseType);
    }

    @SneakyThrows
    private <R> R execute(HttpRequest request, Class<R> responseType) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            String body = response.body();

            if (status != 200 && status != 201) {
                log.debug("Error executing request {}: status {}, response {}, headers: {}",
                        request.uri(), response.statusCode(), response.body(), response.headers());
                if (status == 401) {
                    throw new HttpException(status, "Authorization server returns 401 error code",
                            httpHeadersHandler.convertHttpHeadersToMap(response.headers()));
                } else {
                    throw new HttpException(status, "Authorization server returns error code");
                }
            }

            return JsonMapperUtil.convertToObject(body, responseType);
        } catch (ConnectException e) {
            if (hasUnresolvedAddressException(e)) {
                throw new IllegalArgumentException(
                        "Connection failed: The specified endpoint '%s' is invalid or unreachable.".formatted(request.uri()));
            }
            throw e;
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

    private java.time.Duration createRequestConfig() {
        return java.time.Duration.ofSeconds(30);
    }
}
