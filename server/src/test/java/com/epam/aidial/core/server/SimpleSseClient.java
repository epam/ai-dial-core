package com.epam.aidial.core.server;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

public class SimpleSseClient {

    public interface SseEventListener {
        void onEvent(SseEvent event);

        void onError(Throwable t);

        void onClosed();

        void onConnect(HttpResponse<?> response);
    }

    public record SseEvent(String id, String event, String data, String rawEvent) {

        @NotNull
        @Override
        public String toString() {
            return "SseEvent{"
                    + "id='" + id + '\''
                    + ", event='" + event + '\''
                    + ", data='" + data + '\'' + '}';
        }
    }

    private final String url;
    private final Map<String, String> headers;
    private final String payload;
    private final SseEventListener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public SimpleSseClient(String url, Map<String, String> headers, SseEventListener listener) {
        this(url, headers, null, listener);
    }

    public SimpleSseClient(String url, Map<String, String> headers, String payload, SseEventListener listener) {
        this.url = url;
        this.payload = payload;
        this.listener = listener;
        this.headers = headers;
    }

    /**
     * Start the SSE client in a background thread.
     */
    public void start() {
        if (running.getAndSet(true)) {
            return; // already running
        }

        workerThread = new Thread(this::runLoop, "SSE-Client-Thread");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    /**
     * Stop reading from the SSE stream and close the connection.
     */
    public void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    private void runLoop() {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {

            HttpRequest.BodyPublisher bodyPublisher;
            if (payload == null) {
                bodyPublisher = HttpRequest.BodyPublishers.noBody();
            } else {
                bodyPublisher = HttpRequest.BodyPublishers.ofString(payload);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/event-stream") // Optional, but good practice
                    .POST(bodyPublisher);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            HttpRequest request = builder.build();

            // The response body is a Stream<String> where each string is a line from the server
            HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            int status = response.statusCode();
            if (status == 200) {
                listener.onConnect(response);
                StringBuilder eventBuffer = new StringBuilder();
                // Process each line as it arrives
                response.body().forEach(line -> {
                    if (line.isEmpty()) {
                        // End of one SSE event
                        if (!eventBuffer.isEmpty()) {
                            SseEvent event = parseEvent(eventBuffer.toString());
                            listener.onEvent(event);
                            eventBuffer.setLength(0);
                        }
                    } else if (!line.startsWith(":")) {
                        eventBuffer.append(line).append("\n");
                    }
                });
            } else {
                listener.onError(new IOException("Non-OK response: " + status));
            }

            long interval = TimeUnit.MILLISECONDS.toNanos(10);
            while (running.get()) {
                LockSupport.parkNanos(interval);
            }

        } catch (Exception e) {
            if (running.get()) {
                listener.onError(e);
            }
        } finally {
            running.set(false);
            listener.onClosed();
        }
    }

    private SseEvent parseEvent(String rawEvent) {
        String id = null;
        String eventType = "message";   // default according to SSE spec
        StringBuilder data = new StringBuilder();

        String[] lines = rawEvent.split("\n");
        for (String l : lines) {
            if (l.startsWith("id:")) {
                id = l.substring(3).trim();
            } else if (l.startsWith("event:")) {
                eventType = l.substring(6).trim();
            } else if (l.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append("\n");
                }
                data.append(l.substring(5).trim());
            }
            // You can add support for "retry:" etc. if needed.
        }

        return new SseEvent(id, eventType, data.toString(), rawEvent);
    }

}