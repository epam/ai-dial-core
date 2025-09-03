package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.credentials.exception.CredentialsInternalException;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
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
public class ResourceAuthorizationClient {

    private final HttpClient httpClient;

    public <R> R executeGet(String url, Class<R> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(createRequestConfig())
                    .GET()
                    .build();
            return execute(request, responseType);
        } catch (IOException e) {
            log.error("IO error occurred while executing GET request: {}", e.getMessage(), e);
            throw new CredentialsInternalException("IO error occurred while making GET request", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Request was interrupted: {}", e.getMessage(), e);
            throw new CredentialsInternalException("Request was interrupted", e);
        } catch (HttpException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error occurred: {}", e.getMessage(), e);
            throw new CredentialsInternalException("Unexpected error occurred while making GET request", e);
        }
    }

    public <R> R executePost(String url, Object requestPayload, String contentType, Class<R> responseType) {
        try {
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
        } catch (IOException e) {
            log.error("IO error occurred while executing POST request: {}", e.getMessage(), e);
            throw new CredentialsInternalException("IO error occurred while making POST request", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Request was interrupted: {}", e.getMessage(), e);
            throw new CredentialsInternalException("Request was interrupted", e);
        } catch (HttpException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error occurred: {}", e.getMessage(), e);
            throw new CredentialsInternalException("Unexpected error occurred while making POST request", e);
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

        return JsonMapperUtil.convertToObject(body, responseType);
    }

    private java.time.Duration createRequestConfig() {
        return java.time.Duration.ofSeconds(30);
    }
}
