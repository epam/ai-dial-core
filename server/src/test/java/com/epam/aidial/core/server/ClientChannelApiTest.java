package com.epam.aidial.core.server;

import com.epam.aidial.core.server.jsonrpc.domain.RpcRequest;
import com.epam.aidial.core.server.jsonrpc.domain.RpcResponse;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClientChannelApiTest extends ResourceBaseTest {

    private ClientSeeEventListener clientSeeEventListener;
    private ApplicationSseEventListener applicationSseEventListener;

    private class ClientSeeEventListener implements SimpleSseClient.SseEventListener {

        final CountDownLatch channelIdLatch = new CountDownLatch(1);
        final AtomicReference<String> channelIdRef = new AtomicReference<>();

        @SneakyThrows
        @Override
        public void onEvent(SimpleSseClient.SseEvent event) {
            System.out.println("Client.Received data: " + event);
            RpcRequest request = ProxyUtil.convertToObject(event.data(), RpcRequest.class);
            assertNotNull(request);
            String id = request.getId().asText();
            assertNotNull(request);
            String rpcResponse;
            if (request.getId().asText().endsWith("1")) {
                // simulate work
                Thread.sleep(300);
                rpcResponse = """
                            {"jsonrpc":"2.0","result":"success","id":"%s"}
                            """.formatted(id);
            } else {
                // simulate work
                Thread.sleep(100);
                rpcResponse = """
                            {"jsonrpc":"2.0","result":"denied","id":"%s"}
                            """.formatted(id);
            }
            var response = ClientChannelApiTest.this.send(HttpMethod.POST, "/v1/ops/client-channel/report", null, rpcResponse, Proxy.HEADER_CLIENT_CHANNEL_ID, channelIdRef.get());
            ClientChannelApiTest.verify(response, 200);
        }

        @Override
        public void onError(Throwable t) {
            System.err.println("SSE error: " + t.getMessage());
        }

        @Override
        public void onClosed() {
            System.out.println("SSE connection closed.");
        }

        @SneakyThrows
        @Override
        public void onConnect(HttpResponse<?> response) {
            channelIdRef.set(response.headers().firstValue(Proxy.HEADER_CLIENT_CHANNEL_ID).orElse(null));
            channelIdLatch.countDown();
        }

        @SneakyThrows
        String subscribe() {
            assertTrue(channelIdLatch.await(1, TimeUnit.SECONDS));
            return channelIdRef.get();
        }
    }

    private static class ApplicationSseEventListener implements SimpleSseClient.SseEventListener {

        final CountDownLatch responseLatch = new CountDownLatch(1);

        @Override
        public void onEvent(SimpleSseClient.SseEvent event) {
            System.out.println("App. Received data: " + event);
            TypeReference<List<RpcResponse>> responseRef = new TypeReference<>() {
                @Override
                public Type getType() {
                    return super.getType();
                }
            };
            List<RpcResponse> responses = ProxyUtil.convertToObject(event.data(), responseRef);
            assertNotNull(responses);
            assertEquals(2, responses.size());
            responses.sort(Comparator.comparing(a -> a.getId().asText()));
            assertEquals("1", responses.getFirst().getId().asText());
            assertEquals("success", responses.getFirst().getResult());
            assertEquals("2", responses.get(1).getId().asText());
            assertEquals("denied", responses.get(1).getResult());
            responseLatch.countDown();
        }

        @Override
        public void onError(Throwable t) {
            System.err.println("SSE error: " + t.getMessage());
        }

        @Override
        public void onClosed() {
            System.out.println("SSE connection closed.");
        }

        @SneakyThrows
        @Override
        public void onConnect(HttpResponse<?> response) {
            assertEquals(200, response.statusCode());
        }

        @SneakyThrows
        boolean waitForResponse() {
            return responseLatch.await(2, TimeUnit.SECONDS);
        }
    }

    private SimpleSseClient createChatClient() {
        String subscribeUrl = "http://localhost:" + this.serverPort + "/v1/ops/client-channel/subscribe";
        clientSeeEventListener = new ClientSeeEventListener();
        Map<String, String> clientHeaders = Map.of("api-key", "proxyKey1");
        return new SimpleSseClient(subscribeUrl, clientHeaders, clientSeeEventListener);
    }

    private SimpleSseClient createAppClient(String channelId) {
        final String rpcBatchRequest = """
                    [
                      {"jsonrpc": "2.0", "method": "toolset/signin", "params": {"toolsetId": "toolsets/public/toolset2"}, "id": "1"},
                      {"jsonrpc": "2.0", "method": "toolset/signin", "params": {"toolsetId": "toolsets/public/my-toolset"}, "id": "2"}
                    ]
                    """;
        String interactUrl = "http://localhost:" + this.serverPort + "/v1/ops/client-channel/interact";
        Map<String, String> appHeaders = Map.of("api-key", "proxyKey1",
                Proxy.HEADER_CLIENT_CHANNEL_ID, channelId);
        applicationSseEventListener = new ApplicationSseEventListener();
        return new SimpleSseClient(interactUrl, appHeaders, rpcBatchRequest, applicationSseEventListener);
    }

    @Test
    public void testSendBatchRequest() {

        SimpleSseClient client = createChatClient();

        SimpleSseClient app = null;
        try {
            // subscribe client to a channel
            client.start();
            String channelId = clientSeeEventListener.subscribe();
            // send RPC batch request
            app = createAppClient(channelId);
            app.start();
            // wait for response
            assertTrue(applicationSseEventListener.waitForResponse());
            // unsubscribe client
            var response = send(HttpMethod.POST, "/v1/ops/client-channel/unsubscribe",
                    null, null, Proxy.HEADER_CLIENT_CHANNEL_ID, channelId);
            ClientChannelApiTest.verify(response, 200);
        } finally {
            if (app != null) {
                app.stop();
            }
            client.stop();
        }
    }


}
