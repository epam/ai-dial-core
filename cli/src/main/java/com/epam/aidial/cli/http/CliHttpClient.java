package com.epam.aidial.cli.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class CliHttpClient {

    private final String apiUrl;
    private final String apiKey;
    private final HttpClient delegate;

    public CliHttpClient(String apiUrl, String apiKey) {
        this(apiUrl, apiKey, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public CliHttpClient(String apiUrl, String apiKey, HttpClient delegate) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.delegate = delegate;
    }

    public Response get(String pathAndQuery) {
        URI uri;
        try {
            uri = URI.create(apiUrl + pathAndQuery);
        } catch (IllegalArgumentException e) {
            throw new NetworkException("Invalid URL " + apiUrl + pathAndQuery + ": " + e.getMessage(), e);
        }
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Api-Key", apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<String> r = delegate.send(req, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new NetworkException("Network error contacting " + apiUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NetworkException("Interrupted contacting " + apiUrl, e);
        }
    }

    public static int toExitCode(int status) {
        if (status >= 200 && status < 300) {
            return 0;
        }
        if (status == 401 || status == 403) {
            return 3;
        }
        if (status == 404) {
            return 4;
        }
        return 1;
    }

    public record Response(int status, String body) { }

    public static class NetworkException extends RuntimeException {
        public NetworkException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
