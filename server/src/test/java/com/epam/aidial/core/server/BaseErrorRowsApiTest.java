package com.epam.aidial.core.server;

import com.epam.aidial.core.server.log.AnalyticsLogContext;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A model whose origin is a mock adapter. Core calls the model's {@code endpoint} (the adapter) for every attempt and only
 * names the chosen upstream in {@code X-UPSTREAM-ENDPOINT}, so the adapter answers by that header: upstream {@code up0.test}
 * sits in tier 0 and {@code up1.test} in tier 1, and a model with both tries them in that order.
 *
 * <p>Every test makes its own model: the balancer keeps each upstream's last failure per model name, and a name shared between
 * tests would carry it over.
 */
abstract class BaseErrorRowsApiTest extends ResourceBaseTest {

    static final int ADAPTER_PORT = 4891;
    static final int RAW_PORT = 4892;
    static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    static final String STREAM_BODY = "{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    static final String ANSWER = """
            {"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"mock",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":10,"total_tokens":20}}""";
    static final String EVENT = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
            + "\"delta\":{\"content\":\"x\"}}]}\n\n";
    static final String USAGE_EVENT = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10,\"total_tokens\":20}}\n\ndata: [DONE]\n\n";
    static final String STREAM_HEADERS = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n";
    // a status that stands for an origin that accepts the request and never sends response headers
    static final int NO_HEADERS = -1;

    private static final HttpClient STREAM_CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    // which upstream of which model the adapter answered, in order: "model#0" for tier 0
    final List<String> adapterCalls = new CopyOnWriteArrayList<>();
    TestWebServer adapter;

    abstract boolean isErrorRowsOn();

    @Override
    protected JsonObject additionalSettingsOverrides() {
        JsonObject settings = new JsonObject()
                // short, so that an origin without response headers fails within a test
                .put("client", new JsonObject().put("idleTimeout", 1500).put("idleTimeoutUnit", "MILLISECONDS"))
                .put("tracing", new JsonObject().put("responseTraceHeaders", true))
                // small, so that a body over it gets 413
                .put("resources", new JsonObject().put("maxSize", 20000));
        return isErrorRowsOn() ? settings : settings.put("analytics", new JsonObject().put("errorRows", false));
    }

    @BeforeEach
    void startAdapter() {
        adapter = new TestWebServer(ADAPTER_PORT);
    }

    @AfterEach
    void stopAdapter() {
        adapter.close();
    }

    static String adapterUrl(String model) {
        return "http://localhost:" + ADAPTER_PORT + adapterPath(model);
    }

    static String adapterPath(String model) {
        return "/openai/deployments/" + model + "/chat/completions";
    }

    static String rawUrl(String name) {
        return "http://localhost:" + RAW_PORT + adapterPath(name);
    }

    /**
     * A platform model with one upstream: the adapter at its own endpoint, one attempt.
     */
    void putModel(String name) {
        putModel(name, adapterUrl(name), "");
    }

    void putModel(String name, String endpoint, String more) {
        verify(adminPut("/v1/models/platform/" + name, "{\"type\": \"chat\", \"endpoint\": \"" + endpoint + "\"" + more + "}"), 200);
    }

    /**
     * A platform model whose two upstreams are tried in tier order, with two attempts.
     */
    void putModelWithTwoUpstreams(String name) {
        putModel(name, adapterUrl(name), """
                , "maxRetryAttempts": 2,
                "upstreams": [{"endpoint": "http://up0.test/%1$s", "key": "k", "tier": 0},
                              {"endpoint": "http://up1.test/%1$s", "key": "k", "tier": 1}]""".formatted(name));
    }

    void putApplication(String name, String endpoint) {
        verify(adminPut("/v1/applications/platform/" + name, "{\"endpoint\": \"" + endpoint + "\"}"), 200);
    }

    Response adminPut(String path, String body) {
        return send(HttpMethod.PUT, path, null, body, "authorization", "admin");
    }

    /**
     * The adapter answers {@code statuses[i]} for the upstream of tier {@code i}, or the only status for a model without upstreams.
     */
    void serve(String model, int... statuses) {
        adapter.map(HttpMethod.POST, adapterPath(model), request -> {
            String upstream = request.getHeader(Proxy.HEADER_UPSTREAM_ENDPOINT);
            int tier = upstream != null && upstream.contains("up1.test") ? 1 : 0;
            adapterCalls.add(model + "#" + tier);
            return reply(statuses[Math.min(tier, statuses.length - 1)]);
        });
    }

    static MockResponse reply(int status) {
        if (status == NO_HEADERS) {
            return reply(200).setHeadersDelay(4, TimeUnit.SECONDS);
        }
        if (status == 200) {
            return TestWebServer.createResponse(200, ANSWER, "content-type", "application/json");
        }
        return TestWebServer.createResponse(status, "{\"error\":{\"message\":\"mock " + status + "\"}}", "content-type", "application/json");
    }

    Response chat(String model) {
        return send(HttpMethod.POST, adapterPath(model), null, CHAT_BODY, "content-type", "application/json");
    }

    /**
     * A streamed chat completion to {@code deployment}, its body read as it arrives. Closing the body closes the connection.
     */
    @SneakyThrows
    HttpResponse<InputStream> streamed(String deployment) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + adapterPath(deployment)))
                .version(HttpClient.Version.HTTP_1_1)
                .header("api-key", "proxyKey1")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(STREAM_BODY))
                .build();
        return STREAM_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * Exactly {@code expected} rows: waits for them, then for long enough that a second row, if the code wrote one, has arrived.
     */
    @SneakyThrows
    List<AnalyticsLogContext> rows(int expected) {
        awaitAnalyticsRows(expected);
        Thread.sleep(700);
        assertEquals(expected, analyticsRows.size(), () -> "rows: " + analyticsRows.stream()
                .map(row -> row.getDeploymentName() + "/" + row.getResponseStatusCode()).toList());
        return List.copyOf(analyticsRows);
    }

    static void assertErrorRow(AnalyticsLogContext row, String model, int clientStatus) {
        assertEquals(model, row.getDeploymentName());
        assertEquals(clientStatus, row.getResponseStatusCode());
    }

    /**
     * An origin on a plain socket, for the answers {@code MockWebServer} cannot cut where a test needs: it reads a request, then
     * runs the script on the socket's output and closes the socket. It serves one connection at a time until closed.
     */
    static class RawOrigin implements AutoCloseable {

        private final ServerSocket server;
        private final Thread thread;

        RawOrigin(int port, Script script) throws IOException {
            server = new ServerSocket(port, 5, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        drain(socket);
                        script.run(socket.getOutputStream());
                    } catch (Exception e) {
                        // the test ends the origin by closing it, and a cut connection is what the script is for
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        // the request is small and arrives in one read
        private static void drain(Socket socket) throws IOException {
            socket.setSoTimeout(300);
            try {
                socket.getInputStream().read(new byte[8192]);
            } catch (SocketTimeoutException e) {
                // nothing more to read
            }
        }

        @Override
        @SneakyThrows
        public void close() {
            server.close();
            thread.join(2000);
        }

        interface Script {
            void run(OutputStream out) throws Exception;
        }

        static void write(OutputStream out, String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        static String chunk(String data) {
            return Integer.toHexString(data.getBytes(StandardCharsets.UTF_8).length) + "\r\n" + data + "\r\n";
        }

        /** 200 and the event-stream headers, then the connection closes with no body. */
        static Script closesAfterHeaders() {
            return out -> {
                write(out, STREAM_HEADERS);
                Thread.sleep(150);
            };
        }

        /** The headers and two events, then the connection closes, with no usage and no end of the stream. */
        static Script breaksMidStream() {
            return out -> {
                write(out, STREAM_HEADERS + chunk(EVENT) + chunk(EVENT));
                Thread.sleep(150);
            };
        }

        /** The headers and a Responses {@code response.created} event, which carries the response id, then the connection closes. */
        static Script breaksAfterResponseCreated() {
            return out -> {
                write(out, STREAM_HEADERS + chunk("event: response.created\ndata: {\"type\":\"response.created\","
                        + "\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"in_progress\"}}\n\n"));
                Thread.sleep(150);
            };
        }

        /** A healthy stream of ten events, 300 ms apart, ending with the usage: long enough for a client to leave in the middle. */
        static Script slowStream() {
            return out -> {
                write(out, STREAM_HEADERS);
                for (int i = 0; i < 10; i++) {
                    write(out, chunk(EVENT));
                    Thread.sleep(300);
                }
                write(out, chunk(USAGE_EVENT) + "0\r\n\r\n");
            };
        }
    }
}
