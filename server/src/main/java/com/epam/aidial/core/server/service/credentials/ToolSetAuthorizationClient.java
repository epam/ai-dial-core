package com.epam.aidial.core.server.service.credentials;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ContentType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Slf4j
@RequiredArgsConstructor
public class ToolSetAuthorizationClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public <R> R executeGet(String url, Class<R> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(createRequestConfig())
                    .GET()
                    .build();

            return execute(request, responseType);
        } catch (IOException | InterruptedException e) {
            log.error(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public <R> R executePost(String url, Object requestPayload, String contentType, Class<R> responseType) {
        try {
            String stringPayload;
            if (contentType.equals(ContentType.APPLICATION_JSON.toString())) {
                stringPayload = ProxyUtil.convertToString(requestPayload);
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
        } catch (IOException | InterruptedException e) {
            log.error(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private <R> R execute(HttpRequest request, Class<R> responseType) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        String body = response.body();

        if (status != 200 && status != 201) {
            log.error("Error executing request: {}", response);
            throw new HttpException(status, body);
        }

        return ProxyUtil.convertToObject(body, responseType);
    }

    private java.time.Duration createRequestConfig() {
        return java.time.Duration.ofSeconds(30);
    }
}
