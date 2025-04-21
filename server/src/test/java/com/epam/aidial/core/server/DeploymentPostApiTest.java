package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.http.HttpMethod;
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
