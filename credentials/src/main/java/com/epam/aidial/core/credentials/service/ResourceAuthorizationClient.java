package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.google.common.annotations.VisibleForTesting;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ContentType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Slf4j
public class ResourceAuthorizationClient {

    private final HttpClient httpClient;

    public ResourceAuthorizationClient() {
        this.httpClient = HttpClient.newHttpClient();
    }

    @SuppressWarnings("unused")
    @VisibleForTesting
    private ResourceAuthorizationClient(HttpClient httpClient) {
        this.httpClient = httpClient;
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
                .POST(HttpRequest.BodyPublishers.ofString(stringPayload, StandardCharsets.UTF_8))
                .build();

        return execute(request, responseType);
    }

    @SneakyThrows
    private <R> R execute(HttpRequest request, Class<R> responseType) {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        String body = response.body();

        if (status != 200 && status != 201) {
            log.info("Error executing request {}: status {}, response {}", request.uri(), response.statusCode(), response.body());
            throw new HttpException(status, "Authorization server returns error code");
        }

        return JsonMapperUtil.convertToObject(body, responseType);
    }

    private java.time.Duration createRequestConfig() {
        return java.time.Duration.ofSeconds(30);
    }
}
