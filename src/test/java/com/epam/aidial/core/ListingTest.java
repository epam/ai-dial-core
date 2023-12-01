package com.epam.aidial.core;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
public class ListingTest {

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

    void checkListing(Vertx vertx, VertxTestContext context, String uri, String id, String field, String expected) {
        Consumer<String> checker = (str) -> {
            JsonObject json = new JsonObject(str);
            JsonObject foundElem = null;
            for (Object item : json.getJsonArray("data")) {
                JsonObject elem = (JsonObject) item;
                if (elem.getString("id").equals(id)) {
                    if (foundElem != null) {
                        throw new AssertionError("Multiple elements with id " + id);
                    }
                    foundElem = (JsonObject) item;
                }
            }

            if (foundElem == null) {
                throw new AssertionError("Element with id " + id + " not found");
            }

            JsonObject expectedJson = expected == null ? null : new JsonObject(expected);
            assertEquals(expectedJson, foundElem.getValue(field));
        };
        checkResponse(vertx, context, uri, checker);
    }

    @Test
    void testFeaturesModel(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/models", "chat-gpt-35-turbo", "features", """
                    { "rate": true, "tokenize": true, "truncate_prompt": true }
                """);
    }

    @Test
    void testFeaturesApplication(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/applications", "app", "features", """
                    { "rate": true, "tokenize": false, "truncate_prompt": false }
                """);
    }

    @Test
    void testFeaturesAssistant(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/assistants", "search-assistant", "features", """
                    { "rate": true, "tokenize": false, "truncate_prompt": false }
                """);
    }

    void checkResponse(Vertx vertx, VertxTestContext context, String uri, Consumer<String> checker) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", uri)
                .putHeader("Api-key", "proxyKey2")
                .bearerTokenAuthentication(generateJwtToken("User1"))
                .as(BodyCodec.string())
                .send(context.succeeding(response -> {
                    context.verify(() -> {
                        assertEquals(200, response.statusCode());
                        checker.accept(response.body());
                        context.completeNow();
                    });
                }));
    }
}
