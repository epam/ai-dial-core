package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.service.AdminManagedFieldsWriteMode;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.util.Compression;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import okhttp3.mockwebserver.MockResponse;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("checkstyle:LineLength")
public class DeploymentPostApiTest extends ResourceBaseTest {

    @Test
    public void testCollectTokenUsageStats_WhenClientClosesConnection() throws IOException {
        String responseBody = """
                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"this is a"}}],"usage":null}\r
                data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"very long long"}}],"usage":null}\r
                data: {"id":"chatcmpl-3","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"answer"}}],"usage":null}\r
                data: {"id":"chatcmpl-4","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":"stop","delta":{}}], "usage":{"completion_tokens": 20, "prompt_tokens": 20, "total_tokens": 40}}\r
                data: [DONE]\r
                """;
        try (TestWebServer server = new TestWebServer(4848); CloseableHttpClient client = createHttpClient()) {
            TestWebServer.Handler handler = request -> {
                MockResponse response = new MockResponse();
                response.setResponseCode(200);
                response.setChunkedBody(responseBody, 200);
                try {
                    client.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return response;
            };
            server.map(HttpMethod.POST, "/chat/completions", handler);
            HttpUriRequest httpUriRequest = createHttpUriRequest();
            try {
                client.execute(httpUriRequest, response -> null);
            } catch (IOException e) {
                // ignored socket closed exception
            }
            // wait for core writes token usage
            Thread.sleep(1000);

            Response response = send(HttpMethod.GET, "/v1/deployments/gpt-3-turbo/limits", null, null);

            verify(response, 200);
            LimitStats limitStats = ProxyUtil.convertToObject(response.body(), LimitStats.class);
            assertNotNull(limitStats);
            assertEquals(40, limitStats.getMinuteTokenStats().getUsed());
            assertEquals(40, limitStats.getDayTokenStats().getUsed());
            assertEquals(40, limitStats.getWeekTokenStats().getUsed());
            assertEquals(40, limitStats.getMonthTokenStats().getUsed());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testCollectTokenUsageStats_WhenAdapterClosesConnection() {
        String responseBody = """
                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"this is a"}}],"usage":null}\r
                """;
        MutableObject<HttpServer> adapterRef = new MutableObject<>();
        try (CloseableHttpClient client = createHttpClient()) {
            HttpServer adapter = HttpServer.create(new InetSocketAddress(4848), 0);
            adapterRef.setValue(adapter);
            adapter.createContext("/chat/completions", exchange -> {
                // chunked response
                exchange.sendResponseHeaders(200, 0);
                OutputStream os = exchange.getResponseBody();
                os.write(responseBody.getBytes(StandardCharsets.UTF_8));
                // flush the first chunk
                os.flush();
                // close the server connection
                adapter.stop(0);
            });
            adapter.setExecutor(null); // creates a default executor
            adapter.start();

            HttpUriRequest httpUriRequest = createHttpUriRequest();
            try {
                client.execute(httpUriRequest, response -> null);
            } catch (IOException e) {
                // ignore wrong chunked response
            }
            // wait for core writes token usage
            Thread.sleep(1000);

            Response response = send(HttpMethod.GET, "/v1/deployments/gpt-3-turbo/limits", null, null);

            verify(response, 200);
            LimitStats limitStats = ProxyUtil.convertToObject(response.body(), LimitStats.class);
            assertNotNull(limitStats);
            assertEquals(0, limitStats.getMinuteTokenStats().getUsed());
            assertEquals(0, limitStats.getDayTokenStats().getUsed());
            assertEquals(0, limitStats.getWeekTokenStats().getUsed());
            assertEquals(0, limitStats.getMonthTokenStats().getUsed());
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (adapterRef.getValue() != null) {
                adapterRef.getValue().stop(0);
            }
        }
    }

    @Test
    public void testStreaming_DecompressesGzipResponse() {
        String responseBody = """
                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"this is a"}}],"usage":null}\r
                data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":"stop","delta":{}}],"usage":{"completion_tokens":20,"prompt_tokens":20,"total_tokens":40}}\r
                data: [DONE]\r
                """;
        byte[] gzipped = Compression.compress("gzip", responseBody.getBytes(StandardCharsets.UTF_8));

        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                okio.Buffer buffer = new okio.Buffer();
                buffer.write(gzipped);
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setHeader("Content-Encoding", "gzip")
                        .setChunkedBody(buffer, 16);
            });

            Response response = send(HttpMethod.POST,
                    "/openai/deployments/gpt-3-turbo/chat/completions", null,
                    """
                    {"model":"gpt-3-turbo","stream":true,"messages":[{"role":"user","content":"how are you?"}]}
                    """,
                    "content-type", "application/json");

            verify(response, 200);
            // Without decompression the SSE parser would receive gzip binary and emit nothing;
            // the decoded event proves the upstream gzip stream was inflated before parsing.
            assertTrue(response.body().contains("\"content\":\"this is a\""),
                    "Expected decoded SSE content, but got: " + response.body());
            // The body delivered to the client is plaintext re-serialized SSE,
            // so the upstream gzip Content-Encoding header must not leak to the client.
            assertNull(response.headers().get("Content-Encoding"));
        }
    }

    @Test
    public void testAutoShareAnnotationCitationAttachment() {
        // Register a public application that internally calls gpt-3-turbo.
        // Auto-sharing of annotation citation attachments only activates when the
        // inner request carries a per-request key, i.e. goes through an application.
        ApplicationService applicationService = dial.getProxy().getApplicationService();
        Application app = new Application();
        app.setEndpoint("http://localhost:4848/app");
        applicationService.putApplication(
                ResourceDescriptorFactory.fromPublicUrl("applications/public/annot-test-app"),
                EtagHeader.ANY, null, app, false, AdminManagedFieldsWriteMode.INHERIT_ONLY);

        // Shared state between the two mock handlers that run sequentially
        MutableObject<String> citedFileUrl = new MutableObject<>();

        try (TestWebServer server = new TestWebServer(4848)) {
            // Inner gpt-3-turbo handler: returns annotation whose citation points at the uploaded file.
            // By the time this handler runs, the outer handler has already set citedFileUrl.
            server.map(HttpMethod.POST, "/chat/completions", request -> {
                String body = """
                        {"id":"id1","object":"chat.completion","created":1,"model":"gpt-35-turbo",
                         "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"summary",
                           "custom_content":{"annotations":[
                             {"index":0,"body":{"title":"Source","source":{"attachment":{"type":"text/plain","url":"%s"}}}}
                           ]}
                         }}],
                         "usage":{"completion_tokens":5,"prompt_tokens":5,"total_tokens":10}}
                        """.formatted(citedFileUrl.getValue());
                return new MockResponse().setResponseCode(200).setBody(body);
            });

            // Outer application handler: uploads a file, calls gpt-3-turbo,
            // then verifies it can access the cited file via auto-sharing.
            server.map(HttpMethod.POST, "/app", request -> {
                try {
                    String apiKey = request.getHeader(Proxy.HEADER_API_KEY);

                    // resolve the application's own bucket via per-request key
                    Response bucketResponse = send(HttpMethod.GET, "/v1/bucket", null, "", "api-key", apiKey);
                    assertEquals(200, bucketResponse.status());
                    String appBucket = new JsonObject(bucketResponse.body()).getString("bucket");

                    // upload the file that will be cited in the annotation
                    String fileUrl = "files/%s/cited/source.txt".formatted(appBucket);
                    Response upload = upload(HttpMethod.PUT, "/v1/" + fileUrl, null, "cited content", "api-key", apiKey);
                    assertEquals(200, upload.status());
                    citedFileUrl.setValue(fileUrl);

                    // call gpt-3-turbo — its response will include the annotation citation
                    Response nested = send(HttpMethod.POST,
                            "/openai/deployments/gpt-3-turbo/chat/completions", null,
                            """
                            {"model":"gpt-3-turbo","messages":[{"role":"user","content":"Summarize"}]}
                            """,
                            "api-key", apiKey,
                            "content-type", Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
                    assertEquals(200, nested.status());

                    // the annotation's attachment should now be auto-shared to this per-request key
                    Response fileResponse = send(HttpMethod.GET, "/v1/" + fileUrl, null, null, "api-key", apiKey);
                    verify(fileResponse, 200);

                    return new MockResponse().setResponseCode(200).setBody("""
                            {"id":"id2","object":"chat.completion","created":1,"model":"app",
                             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],
                             "usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}}
                            """);
                } catch (Throwable e) {
                    return new MockResponse().setResponseCode(500);
                }
            });

            // User sends a chat completion to the application
            Response response = send(HttpMethod.POST,
                    "/openai/deployments/applications/public/annot-test-app/chat/completions", null,
                    """
                    {"model":"annot-test-app","messages":[{"role":"user","content":"Summarize"}]}
                    """,
                    "content-type", Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
            verify(response, 200);
        }
    }

    private static CloseableHttpClient createHttpClient() {
        return HttpClientBuilder.create().disableAutomaticRetries().build();
    }

    private HttpUriRequest createHttpUriRequest() {
        String uri = "http://127.0.0.1:" + serverPort + "/openai/deployments/gpt-3-turbo/chat/completions";
        String requestBody = """
                {
                   "model": "gpt-3-turbo",
                   "stream": true,
                   "messages": [
                     {
                       "content": "how are you?",
                       "role": "user"
                     }
                   ]
                 }
                """;
        HttpUriRequest httpUriRequest = new HttpUriRequestBase(HttpMethod.POST.name(), URI.create(uri));
        httpUriRequest.setHeader("api-key", "proxyKey1");
        httpUriRequest.setHeader("content-type", "application/json");
        httpUriRequest.setEntity(new StringEntity(requestBody));
        return httpUriRequest;
    }
}
