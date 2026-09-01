package com.epam.aidial.core.server;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void testEmbeddingDimensions(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/models", "embedding-ada", "embedding_dimensions", 1536);
    }

    @Test
    void testFeaturesEmbedding(Vertx vertx, VertxTestContext context) {
        // an embedding model is not a chat-completions one, whatever its single pre-interfaces endpoint
        // also happens to serve
        checkListing(vertx, context, "/openai/models", "embedding-ada", "features", new JsonObject("""
                    { "rate": false, "tokenize": false, "truncate_prompt": false
                    , "system_prompt": true, "tools": false, "seed": false
                    , "url_attachments": false, "folder_attachments": false
                    , "configuration": false, "allow_resume": true, "accessible_by_per_request_key": true,
                    "content_parts": false, "temperature" : true, "cache" : false,
                    "auto_caching" : false, "parallel_tool_calls": true,
                    "assistant_attachments_in_request": false, "mcp" : false,
                    "chat_completion": false, "responses_api": false,
                    "max_tokens_supported": true, "max_completion_tokens_supported": false,
                    "custom_temperature_supported": true, "reasoning_efforts": []
                    }
                """));
    }

    @Test
    void testFeaturesModel(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/models", "chat-gpt-35-turbo", "features", new JsonObject("""
                    { "rate": true, "tokenize": true, "truncate_prompt": true
                    , "system_prompt": true, "tools": true, "seed": true
                    , "url_attachments": true, "folder_attachments": false
                    , "configuration": true, "allow_resume": true, "accessible_by_per_request_key": true,
                    "content_parts": false, "temperature" : true, "cache" : false,
                    "auto_caching" : false, "parallel_tool_calls": true,
                    "assistant_attachments_in_request": false, "mcp" : false,
                    "chat_completion": true, "responses_api": false,
                    "max_tokens_supported": true, "max_completion_tokens_supported": false,
                    "custom_temperature_supported": true, "reasoning_efforts": []
                    }
                """));
    }

    @Test
    void testFeaturesApplication(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/applications", "app", "features", new JsonObject("""
                    { "rate": true, "tokenize": false, "truncate_prompt": false
                    , "system_prompt": false, "tools": false, "seed": false
                    , "url_attachments": false, "folder_attachments": false
                    , "configuration": true, "allow_resume": true, "accessible_by_per_request_key": true,
                    "content_parts": false, "temperature" : true, "cache" : false,
                    "auto_caching" : false, "parallel_tool_calls": true,
                    "assistant_attachments_in_request": false, "mcp" : false,
                    "chat_completion": true, "responses_api": false,
                    "max_tokens_supported": true, "max_completion_tokens_supported": false,
                    "custom_temperature_supported": true, "reasoning_efforts": []
                    }
                """));
    }

    @Test
    void testFeaturesModelResponsesApi(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/models", "gpt-3-turbo", "features", new JsonObject("""
                    { "rate": false, "tokenize": false, "truncate_prompt": false
                    , "system_prompt": true, "tools": false, "seed": false
                    , "url_attachments": false, "folder_attachments": false
                    , "configuration": false, "allow_resume": true, "accessible_by_per_request_key": true,
                    "content_parts": false, "temperature" : true, "cache" : false,
                    "auto_caching" : false, "parallel_tool_calls": true,
                    "assistant_attachments_in_request": false, "mcp" : false,
                    "chat_completion": true, "responses_api": true,
                    "max_tokens_supported": true, "max_completion_tokens_supported": false,
                    "custom_temperature_supported": true, "reasoning_efforts": []
                    }
                """));
    }

    @Test
    void testFeaturesApplicationResponsesApi(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/applications", "app-responses", "features", new JsonObject("""
                    { "rate": false, "tokenize": false, "truncate_prompt": false
                    , "system_prompt": true, "tools": false, "seed": false
                    , "url_attachments": false, "folder_attachments": false
                    , "configuration": false, "allow_resume": true, "accessible_by_per_request_key": true,
                    "content_parts": false, "temperature" : true, "cache" : false,
                    "auto_caching" : false, "parallel_tool_calls": true,
                    "assistant_attachments_in_request": false, "mcp" : false,
                    "chat_completion": true, "responses_api": true,
                    "max_tokens_supported": true, "max_completion_tokens_supported": false,
                    "custom_temperature_supported": true, "reasoning_efforts": []
                    }
                """));
    }

    @Test
    void testFeaturesModelInterfacesOnly(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/models", "model-iface-only", "features", new JsonObject("""
                    { "rate": false, "tokenize": false, "truncate_prompt": false
                    , "system_prompt": true, "tools": false, "seed": false
                    , "url_attachments": false, "folder_attachments": false
                    , "configuration": false, "allow_resume": true, "accessible_by_per_request_key": true,
                    "content_parts": false, "temperature" : true, "cache" : false,
                    "auto_caching" : false, "parallel_tool_calls": true,
                    "assistant_attachments_in_request": false, "mcp" : false,
                    "chat_completion": true, "responses_api": true,
                    "max_tokens_supported": true, "max_completion_tokens_supported": false,
                    "custom_temperature_supported": true, "reasoning_efforts": []
                    }
                """));
    }

    @Test
    void testFeaturesModelAnthropicInterfaceOnly(Vertx vertx, VertxTestContext context) {
        checkListing(vertx, context, "/openai/models", "claude-ns", "features", new JsonObject("""
                    { "rate": false, "tokenize": false, "truncate_prompt": false
                    , "system_prompt": true, "tools": false, "seed": false
                    , "url_attachments": false, "folder_attachments": false
                    , "configuration": false, "allow_resume": true, "accessible_by_per_request_key": true,
                    "content_parts": false, "temperature" : true, "cache" : false,
                    "auto_caching" : false, "parallel_tool_calls": true,
                    "assistant_attachments_in_request": false, "mcp" : false,
                    "chat_completion": false, "responses_api": false,
                    "max_tokens_supported": true, "max_completion_tokens_supported": false,
                    "custom_temperature_supported": true, "reasoning_efforts": []
                    }
                """));
    }

    @Test
    void testAnthropicModelsListing_includesOnlyAnthropicServableModels(Vertx vertx, VertxTestContext context) {
        Consumer<String> checker = (str) -> {
            JsonObject json = new JsonObject(str);
            boolean foundClaude = false;
            for (Object item : json.getJsonArray("data")) {
                JsonObject elem = (JsonObject) item;
                String id = elem.getString("id");
                assertEquals("model", elem.getString("type"));
                assertEquals(id, elem.getString("display_name"));
                assertEquals(1672534800, Instant.parse(elem.getString("created_at")).getEpochSecond());
                if (id.equals("claude-ns")) {
                    foundClaude = true;
                }
                // an openai-only model must not be advertised under the anthropic listing
                assertFalse(id.equals("test-model-v1"));
            }
            assertTrue(foundClaude);
        };
        checkResponse(vertx, context, "/anthropic/v1/models", checker);
    }

    @Test
    void testAnthropicModel_notFoundForOpenAiOnlyModel(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", "/anthropic/v1/models/test-model-v1")
                .putHeader("Api-key", "proxyKey2")
                .send(context.succeeding(response -> context.verify(() -> {
                    assertEquals(404, response.statusCode());
                    context.completeNow();
                })));
    }

    @Test
    void testAnthropicModel_okForAnthropicModel(Vertx vertx, VertxTestContext context) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", "/anthropic/v1/models/claude-ns")
                .putHeader("Api-key", "proxyKey2")
                .as(BodyCodec.jsonObject())
                .send(context.succeeding(response -> context.verify(() -> {
                    assertEquals(200, response.statusCode());
                    assertEquals("claude-ns", response.body().getString("id"));
                    assertEquals("model", response.body().getString("type"));
                    context.completeNow();
                })));
    }

    @Test
    void testAnthropicModelsListing_limitTruncatesAndSetsHasMore(Vertx vertx, VertxTestContext context) {
        Consumer<String> checker = (str) -> {
            JsonObject json = new JsonObject(str);
            assertEquals(1, json.getJsonArray("data").size());
            String id = json.getJsonArray("data").getJsonObject(0).getString("id");
            assertEquals("default-headers-model", id);
            assertEquals(id, json.getString("first_id"));
            assertEquals(id, json.getString("last_id"));
            assertTrue(json.getBoolean("has_more"));
        };
        checkResponse(vertx, context, "/anthropic/v1/models?limit=1", checker);
    }

    @Test
    void testAnthropicModelsListing_afterIdPaginatesForward(Vertx vertx, VertxTestContext context) {
        Consumer<String> checker = (str) -> {
            JsonObject json = new JsonObject(str);
            assertEquals(2, json.getJsonArray("data").size());
            assertEquals("claude-ns", json.getJsonArray("data").getJsonObject(0).getString("id"));
            assertEquals("claude-stream", json.getJsonArray("data").getJsonObject(1).getString("id"));
            assertEquals("claude-ns", json.getString("first_id"));
            assertEquals("claude-stream", json.getString("last_id"));
            assertTrue(json.getBoolean("has_more"));
        };
        checkResponse(vertx, context, "/anthropic/v1/models?after_id=default-headers-model&limit=2", checker);
    }

    @Test
    void testAnthropicModelsListing_beforeIdPaginatesBackward(Vertx vertx, VertxTestContext context) {
        Consumer<String> checker = (str) -> {
            JsonObject json = new JsonObject(str);
            assertEquals(2, json.getJsonArray("data").size());
            assertEquals("default-headers-model", json.getJsonArray("data").getJsonObject(0).getString("id"));
            assertEquals("claude-ns", json.getJsonArray("data").getJsonObject(1).getString("id"));
            assertFalse(json.getBoolean("has_more"));
        };
        checkResponse(vertx, context, "/anthropic/v1/models?before_id=claude-stream&limit=2", checker);
    }

    @Test
    void testAnthropicModelsListing_invalidLimitReturns400(Vertx vertx, VertxTestContext context) {
        checkBadRequest(vertx, context, "/anthropic/v1/models?limit=abc");
    }

    @Test
    void testAnthropicModelsListing_limitOutOfRangeReturns400(Vertx vertx, VertxTestContext context) {
        checkBadRequest(vertx, context, "/anthropic/v1/models?limit=0");
    }

    @Test
    void testAnthropicModelsListing_bothCursorsReturns400(Vertx vertx, VertxTestContext context) {
        checkBadRequest(vertx, context, "/anthropic/v1/models?after_id=claude-ns&before_id=claude-stream");
    }

    @Test
    void testAnthropicModelsListing_unknownCursorReturns400(Vertx vertx, VertxTestContext context) {
        checkBadRequest(vertx, context, "/anthropic/v1/models?after_id=does-not-exist");
    }

    @Test
    void testAnthropicModelsListing_mapsMaxInputTokensAndMaxTokens(Vertx vertx, VertxTestContext context) {
        Consumer<String> checker = (str) -> {
            JsonObject json = new JsonObject(str);
            JsonObject limited = null;
            JsonObject unlimited = null;
            for (Object item : json.getJsonArray("data")) {
                JsonObject elem = (JsonObject) item;
                if (elem.getString("id").equals("claude-limited")) {
                    limited = elem;
                } else if (elem.getString("id").equals("claude-ns")) {
                    unlimited = elem;
                }
            }
            assertEquals(100000, limited.getInteger("max_input_tokens"));
            assertEquals(8192, limited.getInteger("max_tokens"));
            assertFalse(unlimited.containsKey("max_input_tokens"));
            assertFalse(unlimited.containsKey("max_tokens"));
        };
        checkResponse(vertx, context, "/anthropic/v1/models", checker);
    }

    void checkBadRequest(Vertx vertx, VertxTestContext context, String uri) {
        WebClient client = WebClient.create(vertx);
        client.get(serverPort, "localhost", uri)
                .putHeader("Api-key", "proxyKey2")
                .send(context.succeeding(response -> context.verify(() -> {
                    assertEquals(400, response.statusCode());
                    context.completeNow();
                })));
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
