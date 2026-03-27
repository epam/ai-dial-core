package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ResponsesApiTest extends ResourceBaseTest {

    @Test
    public void testResponsesApi() throws IOException, InterruptedException {
        String responseBody = getResponseBody();
        try (TestWebServer server = new TestWebServer(4848)) {
            try (CloseableHttpClient client = HttpClientBuilder.create().disableAutomaticRetries().build()) {
                TestWebServer.Handler handler = request -> {
                    MockResponse response = new MockResponse();
                    response.setResponseCode(200);
                    response.setChunkedBody(responseBody, 200);
                    return response;
                };
                server.map(HttpMethod.POST, "/openai/v1/responses", handler);
                HttpUriRequest httpUriRequest = createHttpUriRequest();
                String result = client.execute(
                        httpUriRequest,
                        response -> new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8));
                assertLinesMatch(responseBody.lines(), result.lines());
                // wait for core to save usage
                Thread.sleep(1000);

                Response response = send(HttpMethod.GET, "/v1/deployments/gpt-3-turbo/limits", null, null);

                verify(response, 200);
                LimitStats limitStats = ProxyUtil.convertToObject(response.body(), LimitStats.class);
                assertNotNull(limitStats);
                assertEquals(18, limitStats.getMinuteTokenStats().getUsed());
                assertEquals(18, limitStats.getDayTokenStats().getUsed());
                assertEquals(18, limitStats.getWeekTokenStats().getUsed());
                assertEquals(18, limitStats.getMonthTokenStats().getUsed());
            }
        }
    }

    private HttpUriRequest createHttpUriRequest() {
        String uri = "http://127.0.0.1:" + serverPort + "/openai/v1/responses";
        String requestBody = """
                {
                   "model": "gpt-3-turbo",
                   "stream": true,
                   "input": "Knock, knock"
                 }
                """;
        HttpUriRequest httpUriRequest = new HttpUriRequestBase(HttpMethod.POST.name(), URI.create(uri));
        httpUriRequest.setHeader("Authorization", "Bearer proxyKey1");
        httpUriRequest.setHeader("content-type", "application/json");
        httpUriRequest.setEntity(new StringEntity(requestBody));
        return httpUriRequest;
    }

    private static String getResponseBody() throws IOException {
        try (InputStream stream = ResponsesApiTest.class.getClassLoader().getResourceAsStream("responses-sse.txt")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
