package com.epam.aidial.core;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
public class FeaturesApiTest {

    private static AiDial dial;
    private static int serverPort;

    private static Path testDir;

    @BeforeAll
    public static void init() throws Exception {
        // initialize server
        dial = new AiDial();
        testDir = FileUtil.baseTestPath(FeaturesApiTest.class);
        dial.setStorage(FileUtil.buildFsBlobStorage(testDir));
        dial.start();
        serverPort = dial.getServer().actualPort();
    }

    @AfterAll
    public static void destroy() {
        // stop server
        dial.stop();
    }

    private static String generateJwtToken(String user) {
        Algorithm algorithm = Algorithm.HMAC256("secret_key");
        return JWT.create().withClaim("sub", user).sign(algorithm);
    }

    @BeforeEach
    public void setUp() {
        // prepare test directory
        FileUtil.createDir(testDir.resolve("test"));
    }

    @AfterEach
    public void clean() {
        // clean test directory
        FileUtil.deleteDir(testDir);
    }

    @Test
    void testRateEndpointModel(Vertx vertx, VertxTestContext context) {
        String inboundPath = "/v1/chat-gpt-35-turbo/rate";
        String upstream = "http://localhost:7001/upstream/v1/deployments/gpt-35-turbo/rate_response";
        testUpstreamEndpoint(vertx, context, inboundPath, upstream);
    }

    @Test
    void testRateEndpointApplication(Vertx vertx, VertxTestContext context) {
        String inboundPath = "/v1/app/rate";
        String upstream = "http://localhost:7001/openai/deployments/10k/rate_response";
        testUpstreamEndpoint(vertx, context, inboundPath, upstream);
    }

    @Test
    void testRateEndpointAssistant(Vertx vertx, VertxTestContext context) {
        String inboundPath = "/v1/search-assistant/rate";
        String upstream = "http://localhost:7001/openai/deployments/search_assistant/rate_response";
        testUpstreamEndpoint(vertx, context, inboundPath, upstream);
    }

    @Test
    void testRateEndpointAssistantDefaultResponse(Vertx vertx, VertxTestContext context) {
        // The rate endpoint is unset. Checking the default empty response.
        String inboundPath = "/v1/assistant/rate";
        checkResponse(vertx, context, inboundPath, null);
    }

    @Test
    void testTokenizeEndpoint(Vertx vertx, VertxTestContext context) {
        String inboundPath = "/v1/deployments/chat-gpt-35-turbo/tokenize";
        String upstream = "http://localhost:7001/upstream/v1/deployments/gpt-35-turbo/tokenizer";
        testUpstreamEndpoint(vertx, context, inboundPath, upstream);
    }

    @Test
    void testTruncatePromptEndpoint(Vertx vertx, VertxTestContext context) {
        String inboundPath = "/v1/deployments/chat-gpt-35-turbo/truncate_prompt";
        String upstream = "http://localhost:7001/upstream/v1/deployments/gpt-35-turbo/trim_history";
        testUpstreamEndpoint(vertx, context, inboundPath, upstream);
    }

    @SneakyThrows
    void testUpstreamEndpoint(Vertx vertx, VertxTestContext context, String inboundPath, String upstream) {
        URI upstreamUri = new URI(upstream);

        String response = "PONG";

        HttpServerOptions options = new HttpServerOptions()
                .setHost(upstreamUri.getHost())
                .setPort(upstreamUri.getPort());

        vertx.createHttpServer(options)
                .requestHandler(req -> {
                    if (req.path().equals(upstreamUri.getPath())) {
                        req.response().end(response);
                    } else {
                        req.response().setStatusCode(500).end("ERROR");
                    }
                })
                .listen().onSuccess(server ->
                    checkResponse(vertx, context, inboundPath, response));
    }

    void checkResponse(Vertx vertx, VertxTestContext context, String uri, String expectedResponse) {
        WebClient client = WebClient.create(vertx);
        client.post(serverPort, "localhost", uri)
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.string())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        assertEquals(expectedResponse, response.body());
                        context.completeNow();
                    });
                }));
    }
}
