package com.epam.aidial.core;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
public class ListingTest extends ResourceBaseTest {

    void checkListing(Vertx vertx, VertxTestContext context, String uri, String id, String field, Object expected) {
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

            Object actual = field == null ? foundElem : foundElem.getValue(field);
            assertEquals(expected, actual);
        };
        checkResponse(vertx, context, uri, checker);
    }

    @Test
    void testDisplayVersion(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/models", "test-model-v1", "display_version", "1.0");
    }

    @Test
    void testFeaturesEmbedding(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/models", "embedding-ada", "features", new JsonObject("""
                    { "rate": false, "tokenize": false, "truncate_prompt": false
                    , "system_prompt": true, "tools": false, "seed": false
                    , "url_attachments": false, "folder_attachments": false
                    , "configuration": false
                    }
                """));
    }

    @Test
    void testFeaturesModel(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/models", "chat-gpt-35-turbo", "features", new JsonObject("""
                    { "rate": true, "tokenize": true, "truncate_prompt": true
                    , "system_prompt": true, "tools": true, "seed": true
                    , "url_attachments": true, "folder_attachments": false
                    , "configuration": false
                    }
                """));
    }

    @Test
    void testFeaturesApplication(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/applications", "app", "features", new JsonObject("""
                    { "rate": true, "tokenize": false, "truncate_prompt": false
                    , "system_prompt": false, "tools": false, "seed": false
                    , "url_attachments": false, "folder_attachments": false
                    , "configuration": true
                    }
                """));
    }

    @Test
    void testFeaturesAssistant(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/assistants", "search-assistant", "features", new JsonObject("""
                    { "rate": true, "tokenize": false, "truncate_prompt": false
                    , "system_prompt": true, "tools": false, "seed": false
                    , "url_attachments": false,  "folder_attachments": false
                    , "configuration": false
                    }
                """));
    }

    void checkResponse(Vertx vertx, VertxTestContext context, String uri, Consumer<String> checker) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", uri)
                .putHeader("Api-key", "proxyKey2")
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
