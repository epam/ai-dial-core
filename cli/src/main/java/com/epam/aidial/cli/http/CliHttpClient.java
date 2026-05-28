package com.epam.aidial.cli.http;

import com.epam.aidial.cli.CliException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class CliHttpClient {

    private final String apiUrl;
    private final String apiKey;
    private final HttpClient delegate;

    public CliHttpClient(String apiUrl, String apiKey) {
        this(apiUrl, apiKey, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    public CliHttpClient(String apiUrl, String apiKey, HttpClient delegate) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.delegate = delegate;
    }

    public Response get(String path) {
        return get(path, null, "application/json");
    }

    public Response get(String path, String query) {
        return get(path, query, "application/json");
    }

    public Response get(String path, String query, String accept) {
        URI uri = buildUri(path, query);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Api-Key", apiKey)
                .header("Accept", accept)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return sendForString(req);
    }

    public Response post(String path, String body) {
        return post(path, null, body);
    }

    public Response post(String path, String query, String body) {
        URI uri = buildUri(path, query);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Api-Key", apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return sendForString(req);
    }

    public Response put(String path, String body, String ifMatch) {
        return put(path, null, body, ifMatch, null);
    }

    public Response put(String path, String query, String body, String ifMatch, String ifNoneMatch) {
        URI uri = buildUri(path, query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Api-Key", apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (ifMatch != null && !ifMatch.isBlank()) {
            builder.header("If-Match", ifMatch);
        }
        if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
            builder.header("If-None-Match", ifNoneMatch);
        }
        return sendForString(builder.build());
    }

    public Response delete(String path, String ifMatch) {
        return delete(path, null, ifMatch);
    }

    public Response delete(String path, String query, String ifMatch) {
        URI uri = buildUri(path, query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Api-Key", apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .DELETE();
        if (ifMatch != null && !ifMatch.isBlank()) {
            builder.header("If-Match", ifMatch);
        }
        return sendForString(builder.build());
    }

    private URI buildUri(String path, String query) {
        try {
            URI base = URI.create(apiUrl);
            return new URI(base.getScheme(), base.getUserInfo(), base.getHost(), base.getPort(), path, query, null);
        } catch (URISyntaxException e) {
            throw new NetworkException("Invalid URL " + apiUrl + path + "?" + query + ": " + e.getMessage(), e);
        }
    }

    private Response sendForString(HttpRequest req) {
        try {
            HttpResponse<String> r = delegate.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body(), r.headers().firstValue("etag").orElse(null));
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
        if (status == 409) {
            return 5;
        }
        if (status == 412) {
            return 6;
        }
        if (status == 400 || status == 422) {
            return 2;
        }
        return 1;
    }

    public record Response(int status, String body, String etag) { }

    public static class NetworkException extends CliException {
        public NetworkException(String message, Throwable cause) {
            super(message, 1, cause);
        }
    }
}
