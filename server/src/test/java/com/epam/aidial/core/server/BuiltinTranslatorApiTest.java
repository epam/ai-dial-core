package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.service.AdminManagedFieldsWriteMode;
import com.epam.aidial.core.server.util.EncryptedContentAffinityUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /anthropic/v1/messages} for a model whose {@code anthropicMessages} interface is served by a
 * builtin translator: one pass, sent to the model's own {@code openaiResponses} interface.
 */
public class BuiltinTranslatorApiTest extends ResourceBaseTest {

    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String COUNT_TOKENS_PATH = "/anthropic/v1/messages/count_tokens";
    private static final String RESPONSES_PATH = "/openai/v1/responses";
    private static final String TWO_UPSTREAMS = "gpt-builtin-two-upstreams";
    private static final String CREATED = "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5-upstream\"}}";
    private static final String COMPLETED = "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":8}}}";

    private static String requestBody(boolean stream) {
        return "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":" + stream
                + ",\"system\":\"Be brief\",\"messages\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}";
    }

    /**
     * Every request the origin receives, mapped or not.
     */
    private final AtomicInteger originRequests = new AtomicInteger();

    @Test
    public void testStreamingIsTranslatedInOnePass() throws IOException, InterruptedException {
        String sse = readFixture("responses-sse.txt");
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = origin(request -> {
            captured.set(request);
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {

            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1",
                    "anthropic-version", "2023-06-01");

            assertEquals(200, response.status());
            assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta",
                    "content_block_delta", "content_block_delta", "content_block_delta", "content_block_stop",
                    "message_delta", "message_stop"), eventNames(response.body()));
            assertEquals("Who’s there?", deltaText(response.body()));
            // the client gets a DIAL id for the message, never the provider's own response id
            assertFalse(response.body().contains("resp_075fcfcf"), response.body());

            assertEquals(1, originRequests.get());
            RecordedRequest upstream = captured.get();
            assertEquals(RESPONSES_PATH, upstream.getPath());
            JsonNode body = ProxyUtil.MAPPER.readTree(upstream.getBody().readUtf8());
            assertEquals("gpt-5-upstream", body.path("model").asText());
            assertFalse(body.path("store").asBoolean(true));
            assertEquals(ProxyUtil.MAPPER.readTree("[\"reasoning.encrypted_content\"]"), body.path("include"));
            assertTrue(body.path("stream").asBoolean());
            assertEquals("Be brief", body.path("instructions").asText());
            assertEquals("Knock, knock", body.path("input").path(0).path("content").path(0).path("text").asText());
            assertEquals(100, body.path("max_output_tokens").asInt());

            assertNotNull(upstream.getHeader("api-key"));
            assertNotEquals("proxyKey1", upstream.getHeader("api-key"));
            assertEquals("http://responses-upstream/v1/responses", upstream.getHeader("X-UPSTREAM-ENDPOINT"));
            assertEquals("upstreamKey", upstream.getHeader("X-UPSTREAM-KEY"));
            assertNull(upstream.getHeader("anthropic-version"));
            assertNull(upstream.getHeader("X-DIAL-DEPLOYMENT-ID"));

            Thread.sleep(1000); // wait for core to save usage
            // the Responses usage (10 in, 8 out) reaches the limits once, through the Anthropic usage
            assertUsed("gpt-builtin", 18);
        }
    }

    @Test
    public void testBlocksOnceRefusedAreTranslated() throws IOException {
        String sse = readFixture("responses-sse.txt");
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = origin(request -> {
            captured.set(request);
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {
            String body = """
                    {"model":"gpt-builtin","max_tokens":100,"stream":true,"messages":[
                      {"role":"user","content":"hi"},
                      {"role":"assistant","content":[{"type":"compaction","content":"Earlier: we fixed X."}]},
                      {"role":"system","content":[{"type":"text","text":"a"},
                        {"type":"mid_conv_system","content":[{"type":"text","text":"b"}]}]},
                      {"role":"user","content":"continue"}]}""";

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            JsonNode input = ProxyUtil.MAPPER.readTree(captured.get().getBody().readUtf8()).path("input");
            assertEquals(ProxyUtil.MAPPER.readTree("""
                    [{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                     {"type":"message","role":"assistant","content":"Earlier: we fixed X."},
                     {"type":"message","role":"system","content":[{"type":"input_text","text":"a"},{"type":"input_text","text":"b"}]},
                     {"type":"message","role":"user","content":[{"type":"input_text","text":"continue"}]}]"""), input);
        }
    }

    @Test
    public void testToolHistoryIsTranslated() throws IOException {
        String sse = readFixture("responses-sse.txt");
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = origin(request -> {
            captured.set(request);
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {
            String body = """
                    {"model":"gpt-builtin","max_tokens":100,"stream":true,"messages":[
                      {"role":"user","content":"Read a.txt"},
                      {"role":"assistant","content":[{"type":"text","text":"Reading it."},
                        {"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"a.txt"}},
                        {"type":"tool_use","id":"toolu_2","name":"Read","input":{}},
                        {"type":"text","text":"Done."}]},
                      {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"text","text":"42"}]},
                        {"type":"tool_result","tool_use_id":"toolu_2","content":"file not found","is_error":true},
                        {"type":"text","text":"and now?"}]}]}""";

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertEquals("Who’s there?", deltaText(response.body()));
            JsonNode input = ProxyUtil.MAPPER.readTree(captured.get().getBody().readUtf8()).path("input");
            assertEquals(ProxyUtil.MAPPER.readTree("""
                    [{"type":"message","role":"user","content":[{"type":"input_text","text":"Read a.txt"}]},
                     {"type":"message","role":"assistant","content":"Reading it."},
                     {"type":"function_call","call_id":"toolu_1","name":"Read","arguments":"{\\"file_path\\": \\"a.txt\\"}"},
                     {"type":"function_call","call_id":"toolu_2","name":"Read","arguments":"{}"},
                     {"type":"message","role":"assistant","content":"Done."},
                     {"type":"function_call_output","call_id":"toolu_1","output":"42"},
                     {"type":"function_call_output","call_id":"toolu_2","output":"Error: file not found"},
                     {"type":"message","role":"user","content":[{"type":"input_text","text":"and now?"}]}]"""), input);
        }
    }

    @Test
    public void testClaudeCodeToolRequestIsTranslated() throws IOException, InterruptedException {
        String sse = sseEvents(
                "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5-upstream\"}}",
                "{\"type\":\"response.content_part.added\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\"}}",
                "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Reading the file.\"}",
                "{\"type\":\"response.content_part.done\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\"}}",
                "{\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"Read\"}}",
                "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"delta\":\"{\\\"file_path\\\":\"}",
                "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"delta\":\" \\\"a.txt\\\"}\"}",
                "{\"type\":\"response.function_call_arguments.done\",\"output_index\":1,\"arguments\":\"{\\\"file_path\\\": \\\"a.txt\\\"}\"}",
                "{\"type\":\"response.output_item.done\",\"output_index\":1,"
                        + "\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"Read\",\"arguments\":\"{\\\"file_path\\\": \\\"a.txt\\\"}\"}}",
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":8}}}");
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = origin(request -> {
            captured.set(request);
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {
            String body = """
                    {"model":"gpt-builtin","max_tokens":100,"stream":true,
                     "tools":[
                       {"name":"Read","description":"Read a file","input_schema":{"$schema":"http://json-schema.org/draft-07/schema#",
                         "type":"object","properties":{"file_path":{"type":"string"}}}},
                       {"name":"Bash","input_schema":{"type":"object"},"strict":true},
                       {"type":"bash_20250124","name":"bash"},
                       {"type":"mcp_toolset","mcp_server_name":"github"}],
                     "tool_choice":{"type":"tool","name":"Read","disable_parallel_tool_use":true},
                     "messages":[{"role":"user","content":"Read a.txt"}]}""";

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            JsonNode upstream = ProxyUtil.MAPPER.readTree(captured.get().getBody().readUtf8());
            assertEquals(ProxyUtil.MAPPER.readTree("""
                    [{"type":"function","name":"Read","description":"Read a file",
                      "parameters":{"type":"object","properties":{"file_path":{"type":"string"}}},"strict":false},
                     {"type":"function","name":"Bash","parameters":{"type":"object"},"strict":true},
                     {"type":"bash_20250124","name":"bash"},
                     {"type":"mcp_toolset","mcp_server_name":"github"}]"""), upstream.path("tools"));
            assertEquals(ProxyUtil.MAPPER.readTree("{\"type\":\"function\",\"name\":\"Read\"}"), upstream.path("tool_choice"));
            assertFalse(upstream.path("parallel_tool_calls").asBoolean(true));

            assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta", "content_block_stop",
                    "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",
                    "message_delta", "message_stop"), eventNames(response.body()));
            List<JsonNode> events = eventData(response.body());
            assertEquals("Reading the file.", events.get(3).path("delta").path("text").asText());
            assertEquals(ProxyUtil.MAPPER.readTree("{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"Read\",\"input\":{}}"),
                    events.get(5).path("content_block"));
            assertEquals("{\"file_path\":", events.get(6).path("delta").path("partial_json").asText());
            assertEquals(" \"a.txt\"}", events.get(7).path("delta").path("partial_json").asText());
            assertEquals("tool_use", events.get(9).path("delta").path("stop_reason").asText());

            Thread.sleep(1000); // wait for core to save usage
            // a turn that ends on a tool call is charged as a text answer is
            assertUsed("gpt-builtin", 18);
        }
    }

    @Test
    public void testLongToolNameIsAliasedAndRestored() throws IOException {
        String longName = "mcp__dial-knowledge-base__search_documents_by_semantic_similarity_v2";
        String alias = "mcp__dial-knowledge-base__search_documents_by_semantic__7bc2a2fa";
        String sse = sseEvents(
                "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5-upstream\"}}",
                "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                        + "\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"" + alias + "\",\"arguments\":\"\"}}",
                "{\"type\":\"response.function_call_arguments.done\",\"output_index\":0,\"arguments\":\"{\\\"query\\\": \\\"incident code\\\"}\"}",
                "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\","
                        + "\"name\":\"" + alias + "\",\"arguments\":\"{\\\"query\\\": \\\"incident code\\\"}\"}}",
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":8}}}");
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = origin(request -> {
            captured.set(request);
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {
            String body = """
                    {"model":"gpt-builtin","max_tokens":100,"stream":true,
                     "tools":[{"name":"Read"},{"name":"%1$s"},{"name":"a"},{"name":"a_ca978112"}],
                     "tool_choice":{"type":"tool","name":"%1$s"},
                     "messages":[{"role":"user","content":"Look it up"},
                       {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"%1$s","input":{"query":"x"}}]},
                       {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"nothing"}]}]}"""
                    .formatted(longName);

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            JsonNode upstream = ProxyUtil.MAPPER.readTree(captured.get().getBody().readUtf8());
            List<String> toolNames = new ArrayList<>();
            upstream.path("tools").forEach(tool -> toolNames.add(tool.path("name").asText()));
            assertEquals(List.of("Read", alias, "a_ca978112_1", "a_ca978112"), toolNames);
            assertEquals(alias, upstream.path("tool_choice").path("name").asText());
            assertEquals(alias, upstream.path("input").path(1).path("name").asText());
            assertFalse(upstream.toString().contains(longName), upstream.toString());

            // a call alone is the whole answer: no empty text block next to it
            assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta", "content_block_stop",
                    "message_delta", "message_stop"), eventNames(response.body()));
            List<JsonNode> events = eventData(response.body());
            assertEquals(longName, events.get(2).path("content_block").path("name").asText());
            // the arguments only the done event carried, sent once, not again at output_item.done
            assertEquals("{\"query\": \"incident code\"}", events.get(3).path("delta").path("partial_json").asText());
            assertFalse(response.body().contains(alias), response.body());
        }
    }

    @Test
    public void testOutOfScopeToolContentIsRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String webSearch = requestBody(true).replace("\"messages\"",
                    "\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}],\"messages\"");
            Response response = post(client, MESSAGES_PATH, webSearch, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("web_search_20250305"), response.body());

            String responsesWebSearch = requestBody(true).replace("\"messages\"", "\"tools\":[{\"type\":\"web_search\"}],\"messages\"");
            response = post(client, MESSAGES_PATH, responsesWebSearch, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("'web_search'"), response.body());

            String imageResult = """
                    {"model":"gpt-builtin","max_tokens":100,"stream":true,"messages":[{"role":"user","content":[
                      {"type":"tool_result","tool_use_id":"toolu_1","content":[
                        {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}}]}]}]}""";
            response = post(client, MESSAGES_PATH, imageResult, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("'image'"), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testMalformedConversationIsRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String unknownRole = "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":true,\"messages\":[{\"role\":\"tool\",\"content\":\"x\"}]}";
            Response response = post(client, MESSAGES_PATH, unknownRole, "api-key", "proxyKey1");

            assertEquals("Unknown message role: 'tool'", assertAnthropicError(response, 400, "invalid_request_error"));

            String notAnArray = "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":true,\"messages\":\"hi\"}";
            response = post(client, MESSAGES_PATH, notAnArray, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("'messages'"), response.body());

            String stringToolChoice = requestBody(true).replace("\"messages\"", "\"tool_choice\":\"auto\",\"messages\"");
            response = post(client, MESSAGES_PATH, stringToolChoice, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("'tool_choice'"), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    static Stream<Arguments> reasoningRequests() {
        String hi = "[{\"role\":\"user\",\"content\":\"hi\"}]";
        String carrier = quoted("{\"dial_translator_reasoning_wrapper\":{\"version\":2,\"id\":\"rs_1\",\"encrypted_content\":\"cipher-xyz\"}}");
        String redactedCarrier = quoted("{\"dial_translator_reasoning_wrapper\":{\"version\":2,\"encrypted_content\":\"cipher-xyz\"}}");
        String olderCarrier = quoted("{\"dial_translator_reasoning_wrapper\":{\"version\":1,\"encrypted_content\":\"cipher-xyz\"}}");
        return Stream.of(
                Arguments.of("Adaptive thinking with an effort", "\"thinking\":{\"type\":\"adaptive\"},\"output_config\":{\"effort\":\"high\"}", hi,
                        "{\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"}}"),
                Arguments.of("A token budget names no effort", "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":32000}", hi,
                        "{\"reasoning\":{\"summary\":\"auto\"}}"),
                Arguments.of("Omitted display asks for no summary, with an effort",
                        "\"thinking\":{\"type\":\"adaptive\",\"display\":\"omitted\"},\"output_config\":{\"effort\":\"high\"}", hi,
                        "{\"reasoning\":{\"effort\":\"high\"}}"),
                Arguments.of("Omitted display asks for no summary, without an effort", "\"thinking\":{\"type\":\"adaptive\",\"display\":\"omitted\"}", hi,
                        "{\"reasoning\":null}"),
                Arguments.of("A visible system message's effort wins", "\"output_config\":{\"effort\":\"low\"}", """
                        [{"role":"user","content":"hi"},
                         {"role":"system","content":"be thorough","clear_at":"next_user_message","output_config":{"effort":"max"}}]""",
                        "{\"reasoning\":{\"effort\":\"max\"}}"),
                Arguments.of("Nothing named", "\"temperature\":0.5", hi, "{\"reasoning\":null}"),
                Arguments.of("Disabled thinking outranks an effort", "\"thinking\":{\"type\":\"disabled\"},\"output_config\":{\"effort\":\"high\"}", hi,
                        "{\"reasoning\":{\"effort\":\"none\"}}"),
                Arguments.of("Unknown effort under disabled thinking", "\"thinking\":{\"type\":\"disabled\"},\"output_config\":{\"effort\":\"xmax\"}", hi,
                        "{\"reasoning\":{\"effort\":\"none\"}}"),
                Arguments.of("A thinking block between two texts", "\"temperature\":0.5", """
                        [{"role":"user","content":"hi"},
                         {"role":"assistant","content":[{"type":"text","text":"before"},
                           {"type":"thinking","thinking":"plan","signature":%s},{"type":"text","text":"after"}]},
                         {"role":"user","content":"next"}]""".formatted(carrier), """
                        {"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                          {"type":"message","role":"assistant","content":"before"},
                          {"type":"reasoning","id":"rs_1","encrypted_content":"cipher-xyz","summary":[{"type":"summary_text","text":"plan"}]},
                          {"type":"message","role":"assistant","content":"after"},
                          {"type":"message","role":"user","content":[{"type":"input_text","text":"next"}]}]}"""),
                Arguments.of("A redacted thinking block", "\"temperature\":0.5", """
                        [{"role":"user","content":"hi"},
                         {"role":"assistant","content":[{"type":"redacted_thinking","data":%s}]},
                         {"role":"user","content":"next"}]""".formatted(redactedCarrier), """
                        {"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                          {"type":"reasoning","encrypted_content":"cipher-xyz","summary":[]},
                          {"type":"message","role":"user","content":[{"type":"input_text","text":"next"}]}]}"""),
                Arguments.of("A Claude-signed thinking block", "\"temperature\":0.5", """
                        [{"role":"user","content":"hi"},
                         {"role":"assistant","content":[{"type":"text","text":"before"},
                           {"type":"thinking","thinking":"plan","signature":"EqQBCkgIARABGAIiQDNmTx"},{"type":"text","text":"after"}]},
                         {"role":"user","content":"next"}]""", """
                        {"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                          {"type":"message","role":"assistant","content":"before\\nafter"},
                          {"type":"message","role":"user","content":[{"type":"input_text","text":"next"}]}]}"""),
                Arguments.of("An older carrier version", "\"temperature\":0.5", """
                        [{"role":"user","content":"hi"},
                         {"role":"assistant","content":[{"type":"redacted_thinking","data":%s}]},
                         {"role":"user","content":"next"}]""".formatted(olderCarrier), """
                        {"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                          {"type":"message","role":"user","content":[{"type":"input_text","text":"next"}]}]}"""),
                Arguments.of("Structured output format dropped",
                        "\"output_config\":{\"effort\":\"high\",\"format\":{\"type\":\"json_schema\",\"schema\":{\"type\":\"object\"}}}", hi,
                        "{\"reasoning\":{\"effort\":\"high\"},\"text\":null}"));
    }

    /**
     * {@code expected} holds the upstream body's fields to compare; a null one must be absent.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reasoningRequests")
    public void testReasoningRequest(String scenario, String fields, String messages, String expected) throws IOException {
        String sse = readFixture("responses-sse.txt");
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = origin(request -> {
            captured.set(request);
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {
            String body = "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":true," + fields + ",\"messages\":" + messages + "}";

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            JsonNode upstream = ProxyUtil.MAPPER.readTree(captured.get().getBody().readUtf8());
            ProxyUtil.MAPPER.readTree(expected).properties().forEach(field -> {
                if (field.getValue().isNull()) {
                    assertFalse(upstream.has(field.getKey()), scenario + ": " + upstream);
                } else {
                    assertEquals(field.getValue(), upstream.get(field.getKey()), scenario);
                }
            });
        }
    }

    static Stream<Arguments> reasoningStreams() {
        String carrier = carrier("gpt-builtin", "responses-upstream", "cipher-xyz");
        String omitted = "\"thinking\":{\"type\":\"adaptive\",\"display\":\"omitted\"},";
        return Stream.of(
                Arguments.of("Summary streamed, then the carrier", "",
                        List.of(CREATED, summaryDelta("Let me"), summaryDelta(" think"), reasoningDone("cipher-xyz", "Let me think"),
                                textAdded(1), textDelta(1, "Hi"), textDone(1), COMPLETED),
                        List.of(started(0, thinkingBlock("", "")), delta(0, "thinking_delta", "thinking", "Let me"),
                                delta(0, "thinking_delta", "thinking", " think"), delta(0, "signature_delta", "signature", carrier), stopped(0),
                                started(1, textBlock()), delta(1, "text_delta", "text", "Hi"), stopped(1))),
                Arguments.of("Summary and raw reasoning text in one item", "",
                        List.of(CREATED, summaryDelta("summary"),
                                "{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"detail\"}",
                                reasoningDone(null, "summary"), COMPLETED),
                        List.of(started(0, thinkingBlock("", "")), delta(0, "thinking_delta", "thinking", "summary"),
                                delta(0, "thinking_delta", "thinking", "\n\ndetail"), stopped(0))),
                Arguments.of("Unencrypted reasoning", "",
                        List.of(CREATED, summaryDelta("plan"), reasoningDone(null, "plan"), COMPLETED),
                        List.of(started(0, thinkingBlock("", "")), delta(0, "thinking_delta", "thinking", "plan"), stopped(0))),
                Arguments.of("Encrypted reasoning with no summary", "",
                        List.of(CREATED, reasoningDone("cipher-xyz", null), COMPLETED),
                        List.of(started(0, redactedBlock(carrier)), stopped(0))),
                Arguments.of("A summary only in the completed item", "",
                        List.of(CREATED, reasoningDone("cipher-xyz", "plan"), COMPLETED),
                        List.of(started(0, thinkingBlock("plan", carrier)), stopped(0))),
                Arguments.of("A carrier during a tool call", "",
                        List.of(CREATED, summaryDelta("plan"), callAdded(1), reasoningDone("cipher-xyz", "plan"),
                                "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"delta\":\"{}\"}", callDone(1), COMPLETED),
                        List.of(started(0, thinkingBlock("", "")), delta(0, "thinking_delta", "thinking", "plan"), stopped(0),
                                started(1, toolUseBlock()), delta(1, "input_json_delta", "partial_json", "{}"), stopped(1),
                                started(2, redactedBlock(carrier)), stopped(2))),
                Arguments.of("A carrier after its thinking block closed", "",
                        List.of(CREATED, summaryDelta("plan"), textAdded(1), textDelta(1, "hi"), textDone(1), reasoningDone("cipher-xyz", "plan"),
                                callAdded(2), callDone(2), COMPLETED),
                        List.of(started(0, thinkingBlock("", "")), delta(0, "thinking_delta", "thinking", "plan"), stopped(0),
                                started(1, textBlock()), delta(1, "text_delta", "text", "hi"), stopped(1),
                                started(2, redactedBlock(carrier)), stopped(2),
                                started(3, toolUseBlock()), delta(3, "input_json_delta", "partial_json", "{}"), stopped(3))),
                Arguments.of("A carrier only in the terminal event", "",
                        List.of(CREATED, completed(reasoningItem("cipher-xyz", null))),
                        List.of(started(0, redactedBlock(carrier)), stopped(0))),
                Arguments.of("An unencrypted item-done, then an encrypted terminal copy", "",
                        List.of(CREATED, summaryDelta("plan"), reasoningDone(null, "plan"), textAdded(1), textDelta(1, "hi"), textDone(1),
                                completed(reasoningItem("late", "plan"))),
                        List.of(started(0, thinkingBlock("", "")), delta(0, "thinking_delta", "thinking", "plan"), stopped(0),
                                started(1, textBlock()), delta(1, "text_delta", "text", "hi"), stopped(1),
                                started(2, redactedBlock(carrier("gpt-builtin", "responses-upstream", "late"))), stopped(2))),
                Arguments.of("No second carrier from the terminal copy", "",
                        List.of(CREATED, textAdded(1), textDelta(1, "hi"), reasoningDone("first", null), completed(reasoningItem("second", null))),
                        List.of(started(0, textBlock()), delta(0, "text_delta", "text", "hi"), stopped(0),
                                started(1, redactedBlock(carrier("gpt-builtin", "responses-upstream", "first"))), stopped(1))),
                Arguments.of("The carrier is kept without text", omitted,
                        List.of(CREATED, summaryDelta("plan"), reasoningDone("cipher-xyz", "plan"), COMPLETED),
                        List.of(started(0, thinkingBlock("", carrier)), stopped(0))),
                Arguments.of("Unencrypted reasoning is not shown", omitted,
                        List.of(CREATED, summaryDelta("plan"), reasoningDone(null, "plan"), textAdded(1), textDelta(1, "hi"), textDone(1), COMPLETED),
                        List.of(started(0, textBlock()), delta(0, "text_delta", "text", "hi"), stopped(0))));
    }

    /**
     * {@code expected} is every {@code content_block_*} event the client gets, in order; {@code message_delta} and
     * {@code message_stop} follow them.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reasoningStreams")
    public void testReasoningStream(String scenario, String thinking, List<String> upstreamEvents, List<JsonNode> expected) throws IOException {
        String sse = sseEvents(upstreamEvents.toArray(String[]::new));
        try (TestWebServer server = origin(request -> eventStream(sse)); CloseableHttpClient client = newClient()) {
            String body = "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":true," + thinking + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            List<JsonNode> blocks = eventData(response.body()).stream()
                    .filter(data -> data.path("type").asText().startsWith("content_block_"))
                    .toList();
            assertEquals(expected, blocks, scenario);
            List<String> names = eventNames(response.body());
            assertEquals(List.of("message_delta", "message_stop"), names.subList(names.size() - 2, names.size()), scenario);
        }
    }

    @Test
    public void testCarrierHoldsStampedValues() throws IOException {
        String sse = sseEvents(CREATED, reasoningDone("cipher-xyz", null), COMPLETED);
        AtomicReference<String> upstreamKey = new AtomicReference<>();
        try (TestWebServer server = origin(request -> {
            upstreamKey.set(request.getHeader("X-UPSTREAM-KEY"));
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, twoUpstreamsBody("[{\"role\":\"user\",\"content\":\"hi\"}]"), "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            String upstreamId = "keyA".equals(upstreamKey.get()) ? "up-a" : "up-b";
            assertEquals(ProxyUtil.MAPPER.readTree(carrier(TWO_UPSTREAMS, upstreamId, "cipher-xyz")), ProxyUtil.MAPPER.readTree(carriers(response.body()).get(0)));
        }
    }

    @Test
    public void testReplayedReasoningKeepsItsUpstream() throws IOException {
        String sse = sseEvents(CREATED, summaryDelta("plan"), reasoningDone("cipher-xyz", "plan"), textAdded(1), textDelta(1, "Hi"), textDone(1), COMPLETED);
        List<UpstreamCall> calls = new CopyOnWriteArrayList<>();
        try (TestWebServer server = origin(request -> {
            calls.add(UpstreamCall.of(request));
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {
            Response first = post(client, MESSAGES_PATH, twoUpstreamsBody("[{\"role\":\"user\",\"content\":\"hi\"}]"), "api-key", "proxyKey1");
            assertEquals(200, first.status(), first.body());
            String carrier = carriers(first.body()).get(0);

            for (int i = 0; i < 5; i++) {
                Response next = post(client, MESSAGES_PATH, replayBody(carrier), "api-key", "proxyKey1");
                assertEquals(200, next.status(), next.body());
            }

            assertEquals(6, calls.size());
            for (UpstreamCall call : calls.subList(1, 6)) {
                assertEquals(calls.get(0).endpoint(), call.endpoint());
                assertEquals(calls.get(0).key(), call.key());
                assertReplayedItem(call.body());
            }
        }
    }

    @Test
    public void testTranslatorServiceCarrierReplays() throws IOException {
        List<UpstreamCall> calls = new CopyOnWriteArrayList<>();
        String sse = readFixture("responses-sse.txt");
        try (TestWebServer server = origin(request -> {
            calls.add(UpstreamCall.of(request));
            return eventStream(sse);
        }); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, replayBody(carrier(TWO_UPSTREAMS, "up-b", "cipher-xyz")), "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertEquals(1, calls.size());
            assertEquals("http://upstream-b/v1/responses", calls.get(0).endpoint());
            assertEquals("keyB", calls.get(0).key());
            assertReplayedItem(calls.get(0).body());
        }
    }

    @Test
    public void testCarrierOfGoneUpstreamIsRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, replayBody(carrier(TWO_UPSTREAMS, "up-gone", "cipher-xyz")), "api-key", "proxyKey1");

            assertEquals("Upstream 'up-gone' referenced by encrypted content is no longer available.", assertAnthropicError(response, 409, "api_error"));
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testCarriersFromTwoUpstreamsAreRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = replayBody(carrier(TWO_UPSTREAMS, "up-a", "cipher-1"), carrier(TWO_UPSTREAMS, "up-b", "cipher-2"));

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals("Conflicting encrypted content affinity across input items.", assertAnthropicError(response, 400, "invalid_request_error"));
            assertEquals(0, originRequests.get());
        }
    }

    /**
     * What a test reads back from one upstream request: its body can be read only once.
     */
    private record UpstreamCall(String endpoint, String key, JsonNode body) {
        @SneakyThrows
        static UpstreamCall of(RecordedRequest request) {
            return new UpstreamCall(request.getHeader("X-UPSTREAM-ENDPOINT"), request.getHeader("X-UPSTREAM-KEY"),
                    ProxyUtil.MAPPER.readTree(request.getBody().readUtf8()));
        }
    }

    private static String twoUpstreamsBody(String messages) {
        return "{\"model\":\"" + TWO_UPSTREAMS + "\",\"max_tokens\":100,\"stream\":true,\"messages\":" + messages + "}";
    }

    /**
     * Turn 2 of a conversation whose assistant turn holds a thinking block for each of {@code carriers}.
     */
    private static String replayBody(String... carriers) {
        StringBuilder blocks = new StringBuilder();
        for (String carrier : carriers) {
            blocks.append("{\"type\":\"thinking\",\"thinking\":\"plan\",\"signature\":").append(quoted(carrier)).append("},");
        }
        return twoUpstreamsBody("[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":[" + blocks
                + "{\"type\":\"text\",\"text\":\"Hi\"}]},{\"role\":\"user\",\"content\":\"more\"}]");
    }

    private static void assertReplayedItem(JsonNode upstreamBody) {
        JsonNode item = upstreamBody.path("input").path(1);
        assertEquals("reasoning", item.path("type").asText(), upstreamBody.toString());
        assertEquals("rs_1", item.path("id").asText());
        assertEquals("cipher-xyz", item.path("encrypted_content").asText());
    }

    /**
     * Every carrier the client got, from {@code signature_delta} events and whole thinking blocks.
     */
    private static List<String> carriers(String sse) {
        List<String> carriers = new ArrayList<>();
        for (JsonNode data : eventData(sse)) {
            for (JsonNode value : List.of(data.path("delta").path("signature"), data.path("content_block").path("signature"),
                    data.path("content_block").path("data"))) {
                if (!value.asText("").isEmpty()) {
                    carriers.add(value.asText());
                }
            }
        }
        return carriers;
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"xmax\"", "5"})
    public void testUnknownEffortIsRefused(String effort) throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("\"messages\"", "\"output_config\":{\"effort\":" + effort + "},\"messages\"");

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals("Unsupported 'output_config.effort': '" + effort.replace("\"", "")
                    + "'. Expected one of: none, minimal, low, medium, high, xhigh, max", assertAnthropicError(response, 400, "invalid_request_error"));
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testThinkingBlockInUserTurnIsRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = """
                    {"model":"gpt-builtin","max_tokens":100,"stream":true,"messages":[
                      {"role":"user","content":[{"type":"thinking","thinking":"plan","signature":"x"}]}]}""";

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertTrue(response.body().contains("'thinking' in the user turn"), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testLimitIsCheckedBeforeTheUpstream() throws IOException, InterruptedException {
        String sse = readFixture("responses-sse.txt");
        try (TestWebServer server = origin(request -> eventStream(sse)); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("gpt-builtin", "gpt-builtin-limited");

            Response first = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");
            assertEquals(200, first.status(), first.body());
            Thread.sleep(1000); // wait for core to save usage: 18 tokens, over the role's limit of 10

            Response second = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");
            assertEquals(429, second.status(), second.body());
            assertEquals(1, originRequests.get());
        }
    }

    @Test
    public void testApplicationIsNotServedByBuiltinTranslator() throws IOException {
        // registered here rather than in the shared test config, whose application listings other tests compare whole
        putPublicApplication("app-builtin", ProxyUtil.convertToObject("""
                {"interfaces": {
                  "openaiResponses": {"base_url": "http://localhost:4848"},
                  "anthropicMessages": {"mode": "translator", "translator": "builtinResponses"}}}""", Application.class));
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("gpt-builtin", "applications/public/app-builtin");

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertEquals(503, response.status(), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testPerRequestKeyClosedOnTheTranslatedInterfaceIsRefused() {
        Application app = new Application();
        app.setEndpoint("http://localhost:4848/app");
        putPublicApplication("builtin-caller", app);
        AtomicReference<Response> callBack = new AtomicReference<>();
        try (TestWebServer server = refusingOrigin()) {
            // the application calls Core back with the per-request key it received
            server.map(HttpMethod.POST, "/app", request -> {
                callBack.set(send(HttpMethod.POST, MESSAGES_PATH, null, requestBody(true).replace("gpt-builtin", "gpt-builtin-closed"),
                        "api-key", request.getHeader(Proxy.HEADER_API_KEY), "content-type", Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON));
                return TestWebServer.createResponse(200, """
                        {"id":"id1","object":"chat.completion","created":1,"model":"builtin-caller",
                         "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],
                         "usage":{"completion_tokens":1,"prompt_tokens":1,"total_tokens":2}}""");
            });

            Response response = send(HttpMethod.POST, "/openai/deployments/applications/public/builtin-caller/chat/completions", null,
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "content-type", Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);

            verify(response, 200);
            assertEquals(403, callBack.get().status(), callBack.get().body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testNonStreamingIsTranslatedInOnePass() throws IOException, InterruptedException {
        AtomicReference<RecordedRequest> captured = new AtomicReference<>();
        try (TestWebServer server = origin(request -> {
            captured.set(request);
            return json(200, responsesObject("completed", "{\"type\":\"message\",\"role\":\"assistant\","
                    + "\"content\":[{\"type\":\"output_text\",\"text\":\"Who’s there?\",\"annotations\":[]}]}", "10", "8"));
        }); CloseableHttpClient client = newClient()) {

            // uncompressed, so Content-Length is the length of the body read
            Response response = post(client, MESSAGES_PATH, requestBody(false), "api-key", "proxyKey1", "anthropic-version", "2023-06-01",
                    "Accept-Encoding", "identity");

            assertEquals(200, response.status(), response.body());
            // the measurement of the one-pass claim: one upstream request, one charge
            assertEquals(1, originRequests.get());
            assertEquals(RESPONSES_PATH, captured.get().getPath());
            JsonNode upstream = ProxyUtil.MAPPER.readTree(captured.get().getBody().readUtf8());
            assertFalse(upstream.path("stream").asBoolean(true), upstream.toString());
            assertTrue(upstream.path("stream").isBoolean(), upstream.toString());
            assertFalse(upstream.path("store").asBoolean(true), upstream.toString());

            // Answer headers
            assertEquals("application/json", header(response, "Content-Type"));
            assertEquals("1", header(response, Proxy.HEADER_UPSTREAM_ATTEMPTS));
            assertEquals(String.valueOf(response.body().getBytes(StandardCharsets.UTF_8).length), header(response, "Content-Length"));

            JsonNode message = ProxyUtil.MAPPER.readTree(response.body());
            assertEquals("message", message.path("type").asText());
            assertEquals("assistant", message.path("role").asText());
            assertEquals(ProxyUtil.MAPPER.readTree("[{\"type\":\"text\",\"text\":\"Who’s there?\",\"citations\":null}]"), message.path("content"));
            assertEquals("end_turn", message.path("stop_reason").asText());
            // DIAL id and upstream model; the test harness stubs the 32-hex generator with a counter
            assertTrue(message.path("id").asText().matches("dial_gpt-builtin_[0-9a-f]+"), message.path("id").asText());
            assertFalse(response.body().contains("resp_075fcfcf"), response.body());
            assertEquals("gpt-5-upstream", message.path("model").asText());
            // Null-valued fields
            for (String field : List.of("container", "stop_details", "stop_sequence")) {
                assertTrue(message.has(field) && message.get(field).isNull(), field);
            }
            for (String field : List.of("cache_creation", "inference_geo", "server_tool_use", "service_tier")) {
                assertTrue(message.path("usage").has(field) && message.path("usage").get(field).isNull(), field);
            }

            Thread.sleep(1000); // wait for core to save usage
            assertUsed("gpt-builtin", 18);
        }
    }

    @Test
    public void testRequestWithoutStreamFieldIsNonStreaming() throws IOException {
        List<JsonNode> upstreamBodies = new CopyOnWriteArrayList<>();
        try (TestWebServer server = origin(request -> {
            upstreamBodies.add(UpstreamCall.of(request).body());
            return json(200, responsesObject("completed", "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}", "1", "1"));
        }); CloseableHttpClient client = newClient()) {
            Response explicit = post(client, MESSAGES_PATH, requestBody(false), "api-key", "proxyKey1");
            Response absent = post(client, MESSAGES_PATH, requestBody(false).replace("\"stream\":false,", ""), "api-key", "proxyKey1");

            assertEquals(200, explicit.status(), explicit.body());
            assertEquals(200, absent.status(), absent.body());
            assertEquals(2, upstreamBodies.size());
            for (JsonNode body : upstreamBodies) {
                assertTrue(body.path("stream").isBoolean() && !body.path("stream").booleanValue(), body.toString());
                assertEquals("Knock, knock", body.path("input").path(0).path("content").path(0).path("text").asText());
            }
        }
    }

    @Test
    public void testNonStreamingReasoningIsStampedForAffinity() throws IOException {
        String output = reasoningItem("cipher-xyz", null) + ",{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}";
        MockResponse answer = json(200, responsesObject("completed", output, "1", "1"));
        try (TestWebServer server = origin(request -> answer); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            JsonNode block = ProxyUtil.MAPPER.readTree(response.body()).path("content").path(0);
            assertEquals("redacted_thinking", block.path("type").asText(), response.body());
            // the values a POST /openai/v1/responses to the same model answers for the item from its one upstream
            assertEquals(ProxyUtil.MAPPER.readTree(carrier("gpt-builtin", "responses-upstream", "cipher-xyz")),
                    ProxyUtil.MAPPER.readTree(block.path("data").asText()));
        }
    }

    @Test
    public void testFailedNonStreamingAnswerIsAnAnthropicError() throws IOException, InterruptedException {
        String failed = "{\"id\":\"resp_1\",\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"upstream exploded\"},\"output\":[],"
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":7}}";
        try (TestWebServer server = origin(request -> json(200, failed)); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals(502, response.status());
            assertEquals("application/json", header(response, "Content-Type"));
            assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"upstream exploded\"}}", response.body());

            Thread.sleep(1000); // wait for core to save usage
            // charged the usage the failed answer reports, as the Responses pass of a translator service charges it
            assertUsed("gpt-builtin", 19);
        }
    }

    @Test
    public void testFailureInsideTwoHundredKeepsItsStatusAndType() throws IOException {
        // 504 timeout_error tells the failure's own status and type apart from a fixed 502 and from typeForStatus (api_error)
        String failed = "{\"id\":\"resp_1\",\"status\":\"failed\",\"error\":{\"code\":\"vector_store_timeout\",\"message\":\"late\"},\"output\":[]}";
        try (TestWebServer server = origin(request -> json(200, failed)); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals("late", assertAnthropicError(response, 504, "timeout_error"));
        }
    }

    @Test
    public void testNonJsonAnswerIsAnApiError() throws IOException, InterruptedException {
        try (TestWebServer server = origin(request -> json(200, "not a Responses object")); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals("The upstream answer is not a Responses object", assertAnthropicError(response, 502, "api_error"));

            Thread.sleep(1000); // wait for core to save usage
            assertUsed("gpt-builtin", 0);
        }
    }

    @Test
    public void testUpstream500IsAnAnthropicError() throws IOException, InterruptedException {
        MockResponse answer = json(500, "{\"error\":{\"message\":\"boom\",\"type\":\"server_error\"}}");
        try (TestWebServer server = origin(request -> answer); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals(500, response.status());
            assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}", response.body());
            assertEquals("1", header(response, Proxy.HEADER_UPSTREAM_ATTEMPTS));

            Thread.sleep(1000); // wait for core to save usage
            assertUsed("gpt-builtin", 0);
        }
    }

    @Test
    public void testUpstream400ToStreamingRequestIsJson() throws IOException {
        MockResponse answer = json(400, "{\"error\":{\"message\":\"Invalid value\",\"code\":\"invalid_value\"}}");
        try (TestWebServer server = origin(request -> answer); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1");

            assertEquals(400, response.status());
            assertEquals("application/json", header(response, "Content-Type"));
            assertNull(header(response, "Transfer-Encoding"));
            assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Invalid value\"}}", response.body());
        }
    }

    @Test
    public void testPlainTextUpstreamErrorBody() throws IOException {
        MockResponse answer = TestWebServer.createResponse(404, "Not Found", "Content-Type", "text/plain");
        try (TestWebServer server = origin(request -> answer); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals("Not Found", assertAnthropicError(response, 404, "not_found_error"));
        }
    }

    private static MockResponse json(int status, String body) {
        return TestWebServer.createResponse(status, body, "Content-Type", "application/json");
    }

    /**
     * A non-streaming Responses object of {@code status} holding {@code output} (items, comma-separated).
     */
    private static String responsesObject(String status, String output, String inputTokens, String outputTokens) {
        return "{\"id\":\"resp_075fcfcf\",\"object\":\"response\",\"model\":\"gpt-5-upstream\",\"status\":\"" + status + "\",\"output\":[" + output + "],"
                + "\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":" + outputTokens + "}}";
    }

    @Test
    public void testUntranslatableContentIsRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = "{\"model\":\"gpt-builtin\",\"max_tokens\":100,\"stream\":true,\"messages\":[{\"role\":\"user\","
                    + "\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"AA==\"}}]}]}";

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertTrue(assertAnthropicError(response, 400, "invalid_request_error").contains("'image'"), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testStreamWithoutTerminalEventEndsInError() throws IOException {
        String sse = readFixture("responses-sse.txt");
        String truncated = sse.substring(0, sse.indexOf("event: response.completed"));
        try (TestWebServer server = origin(request -> eventStream(truncated)); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            List<String> events = eventNames(response.body());
            assertEquals("error", events.get(events.size() - 1), response.body());
            assertFalse(events.contains("message_stop"), response.body());
            assertErrorEvent(response.body(), "api_error", "The upstream stream ended before the response completed");
        }
    }

    @Test
    public void testMidStreamFailureBecomesAnErrorEvent() throws IOException, InterruptedException {
        String sse = sseEvents(CREATED, textAdded(0), textDelta(0, "Who"),
                "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"upstream exploded\"},"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":8}}}");
        try (TestWebServer server = origin(request -> eventStream(sse)); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            assertEquals("Who", deltaText(response.body()));
            assertFalse(eventNames(response.body()).contains("message_stop"), response.body());
            assertErrorEvent(response.body(), "api_error", "upstream exploded");

            Thread.sleep(1000); // wait for core to save usage
            // charged the usage the failed response reports, as the Responses pass of a translator service charges it
            assertUsed("gpt-builtin", 18);
        }
    }

    @Test
    public void testUpstreamDisconnectMidStreamEndsWithErrorEvent() throws IOException {
        // the mock writes the first half of the body and drops the connection; the padding keeps the text delta in that half
        String sse = sseEvents(CREATED, textAdded(0), textDelta(0, "Who"),
                "{\"type\":\"response.output_text.annotation.added\",\"padding\":\"" + "x".repeat(4000) + "\"}", COMPLETED);
        MockResponse dropped = eventStream(sse).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
        try (TestWebServer server = origin(request -> dropped); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1");

            assertEquals(200, response.status());
            assertEquals("Who", deltaText(response.body()));
            assertFalse(eventNames(response.body()).contains("message_stop"), response.body());
            assertErrorEvent(response.body(), "api_error", "The upstream connection failed before the response completed");
        }
    }

    /**
     * Asserts an Anthropic error answer of {@code status} and {@code type}, and returns its message.
     */
    private static String assertAnthropicError(Response response, int status, String type) throws IOException {
        assertEquals(status, response.status(), response.body());
        assertEquals("application/json", header(response, "Content-Type"), response.headers().toString());
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("error", body.path("type").asText(), response.body());
        assertEquals(type, body.path("error").path("type").asText(), response.body());
        assertEquals(2, body.size(), response.body());
        return body.path("error").path("message").asText();
    }

    private static String header(Response response, String name) {
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * The client's last event is an {@code error} of {@code type} and {@code message}.
     */
    private static void assertErrorEvent(String sse, String type, String message) {
        List<String> names = eventNames(sse);
        assertEquals("error", names.get(names.size() - 1), sse);
        JsonNode error = eventData(sse).get(names.size() - 1).path("error");
        assertEquals(type, error.path("type").asText(), sse);
        assertEquals(message, error.path("message").asText(), sse);
    }

    @Test
    public void testDeploymentWithInterceptorsIsRefused() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("gpt-builtin", "gpt-builtin-intercepted");

            Response response = post(client, MESSAGES_PATH, body, "api-key", "proxyKey1");

            assertTrue(assertAnthropicError(response, 503, "overloaded_error").contains("interceptors"), response.body());
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testConsentOfTheTranslatedInterfaceIsRequired() throws IOException {
        // a translator service's pass back to Core is held to the openaiResponses consent, so this pass is too
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("gpt-builtin", "gpt-builtin-consent");

            Response response = post(client, MESSAGES_PATH, body, "Authorization", "user");

            assertAnthropicError(response, 403, "permission_error");
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testConsentOfTheMessagesInterfaceIsAnAnthropicError() throws IOException {
        try (TestWebServer server = refusingOrigin(); CloseableHttpClient client = newClient()) {
            String body = requestBody(true).replace("gpt-builtin", "gpt-builtin-messages-consent");

            Response response = post(client, MESSAGES_PATH, body, "Authorization", "user");

            assertAnthropicError(response, 403, "permission_error");
            assertEquals(0, originRequests.get());
        }
    }

    @Test
    public void testConnectFailureIsAnAnthropicError() throws IOException {
        // nothing listens on the model's openaiResponses endpoint
        try (CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1");

            assertEquals("Failed to connect to upstream server", assertAnthropicError(response, 502, "api_error"));
        }
    }

    @Test
    public void testEveryUpstreamBusyKeepsRetryAfter() throws IOException {
        // the model's one upstream, with the default maxRetryAttempts of 1
        MockResponse busy = TestWebServer.createResponse(429, "{\"error\":{\"message\":\"busy\"}}", "Retry-After", "5");
        try (TestWebServer server = origin(request -> busy); CloseableHttpClient client = newClient()) {
            Response response = post(client, MESSAGES_PATH, requestBody(true), "api-key", "proxyKey1");

            assertEquals("Service is not available", assertAnthropicError(response, 503, "overloaded_error"));
            long retryAfter = Long.parseLong(header(response, "Retry-After"));
            assertTrue(retryAfter >= 0 && retryAfter <= 5, String.valueOf(retryAfter));
        }
    }

    @Test
    public void testCountTokensIsNotServedYet() throws IOException {
        try (CloseableHttpClient client = newClient()) {
            Response response = post(client, COUNT_TOKENS_PATH, requestBody(false), "api-key", "proxyKey1");

            assertEquals(503, response.status());
        }
    }

    private void putPublicApplication(String name, Application application) {
        dial.getProxy().getApplicationService().putApplication(ResourceDescriptorFactory.fromPublicUrl("applications/public/" + name),
                EtagHeader.ANY, null, application, false, AdminManagedFieldsWriteMode.INHERIT_ONLY);
    }

    /**
     * The origin at 4848: {@code POST /openai/v1/responses} answered by {@code responses}, anything else by a
     * 500, and every request counted in {@link #originRequests}.
     */
    private TestWebServer origin(TestWebServer.Handler responses) {
        TestWebServer server = new TestWebServer(4848, request -> {
            originRequests.incrementAndGet();
            return TestWebServer.createResponse(500, "unexpected");
        });
        server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
            originRequests.incrementAndGet();
            return responses.map(request);
        });
        return server;
    }

    private TestWebServer refusingOrigin() {
        return origin(request -> TestWebServer.createResponse(500, "unexpected"));
    }

    private static MockResponse eventStream(String sse) {
        MockResponse response = new MockResponse();
        response.setResponseCode(200);
        response.setHeader("Content-Type", "text/event-stream");
        response.setChunkedBody(sse, 200);
        return response;
    }

    @SneakyThrows
    private static String quoted(String text) {
        return ProxyUtil.MAPPER.writeValueAsString(text);
    }

    /**
     * The carrier the client gets for reasoning item {@code rs_1} with {@code cipher}, in the translator service's
     * format, holding the values a Responses client gets for the item from {@code upstreamId}.
     */
    private static String carrier(String deployment, String upstreamId, String cipher) {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode().put("type", "reasoning").put("id", "rs_1").put("encrypted_content", cipher);
        EncryptedContentAffinityUtil.wrapOutputItem(item, upstreamId, deployment);
        ObjectNode payload = ProxyUtil.MAPPER.createObjectNode().put("version", 2);
        payload.set("id", item.get("id"));
        payload.set("encrypted_content", item.get("encrypted_content"));
        return "{\"dial_translator_reasoning_wrapper\":" + payload + "}";
    }

    private static String summaryDelta(String delta) {
        return "{\"type\":\"response.reasoning_summary_text.delta\",\"item_id\":\"rs_1\",\"output_index\":0,\"summary_index\":0,\"delta\":" + quoted(delta) + "}";
    }

    /**
     * Reasoning item {@code rs_1}, encrypted unless {@code cipher} is null, with a summary unless {@code summary} is.
     */
    private static String reasoningItem(String cipher, String summary) {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode().put("type", "reasoning").put("id", "rs_1");
        ArrayNode parts = item.putArray("summary");
        if (summary != null) {
            parts.addObject().put("type", "summary_text").put("text", summary);
        }
        if (cipher != null) {
            item.put("encrypted_content", cipher);
        }
        return item.toString();
    }

    private static String reasoningDone(String cipher, String summary) {
        return "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":" + reasoningItem(cipher, summary) + "}";
    }

    private static String completed(String outputItem) {
        return "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" + outputItem + "],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":8}}}";
    }

    private static String textAdded(int index) {
        return "{\"type\":\"response.content_part.added\",\"output_index\":" + index + ",\"content_index\":0,\"part\":{\"type\":\"output_text\"}}";
    }

    private static String textDelta(int index, String delta) {
        return "{\"type\":\"response.output_text.delta\",\"output_index\":" + index + ",\"content_index\":0,\"delta\":" + quoted(delta) + "}";
    }

    private static String textDone(int index) {
        return "{\"type\":\"response.content_part.done\",\"output_index\":" + index + ",\"content_index\":0,\"part\":{\"type\":\"output_text\"}}";
    }

    private static String callAdded(int index) {
        return "{\"type\":\"response.output_item.added\",\"output_index\":" + index
                + ",\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"Read\",\"arguments\":\"\"}}";
    }

    private static String callDone(int index) {
        return "{\"type\":\"response.output_item.done\",\"output_index\":" + index
                + ",\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"Read\",\"arguments\":\"{}\"}}";
    }

    private static JsonNode started(int index, ObjectNode block) {
        ObjectNode event = ProxyUtil.MAPPER.createObjectNode().put("type", "content_block_start").put("index", index);
        event.set("content_block", block);
        return event;
    }

    private static JsonNode delta(int index, String type, String field, String value) {
        ObjectNode event = ProxyUtil.MAPPER.createObjectNode().put("type", "content_block_delta").put("index", index);
        event.putObject("delta").put("type", type).put(field, value);
        return event;
    }

    private static JsonNode stopped(int index) {
        return ProxyUtil.MAPPER.createObjectNode().put("type", "content_block_stop").put("index", index);
    }

    private static ObjectNode thinkingBlock(String thinking, String signature) {
        return ProxyUtil.MAPPER.createObjectNode().put("type", "thinking").put("thinking", thinking).put("signature", signature);
    }

    private static ObjectNode redactedBlock(String data) {
        return ProxyUtil.MAPPER.createObjectNode().put("type", "redacted_thinking").put("data", data);
    }

    private static ObjectNode textBlock() {
        return ProxyUtil.MAPPER.createObjectNode().put("type", "text").put("text", "");
    }

    private static ObjectNode toolUseBlock() {
        ObjectNode block = ProxyUtil.MAPPER.createObjectNode().put("type", "tool_use").put("id", "call_1").put("name", "Read");
        block.putObject("input");
        return block;
    }

    private static String deltaText(String sse) {
        return eventData(sse).stream().map(data -> data.path("delta").path("text").asText("")).collect(Collectors.joining());
    }

    /**
     * An upstream Responses event stream holding {@code events}, one data object each.
     */
    @SneakyThrows
    private static String sseEvents(String... events) {
        StringBuilder sse = new StringBuilder();
        for (String event : events) {
            String type = ProxyUtil.MAPPER.readTree(event).path("type").asText();
            sse.append("event: ").append(type).append("\ndata: ").append(event).append("\n\n");
        }
        return sse.toString();
    }

    @SneakyThrows
    private static List<JsonNode> eventData(String sse) {
        List<JsonNode> events = new ArrayList<>();
        for (String line : sse.lines().filter(line -> line.startsWith("data: ")).toList()) {
            events.add(ProxyUtil.MAPPER.readTree(line.substring("data: ".length())));
        }
        return events;
    }

    private static List<String> eventNames(String sse) {
        return sse.lines()
                .filter(line -> line.startsWith("event: "))
                .map(line -> line.substring("event: ".length()))
                .toList();
    }

    private void assertUsed(String deployment, long expected) {
        Response response = send(HttpMethod.GET, "/v1/deployments/" + deployment + "/limits", null, null);
        verify(response, 200);
        LimitStats stats = ProxyUtil.convertToObject(response.body(), LimitStats.class);
        assertNotNull(stats);
        assertEquals(expected, stats.getDayTokenStats().getUsed());
    }

    private Response post(CloseableHttpClient client, String path, String body, String... headers) throws IOException {
        HttpUriRequestBase request = new HttpUriRequestBase(HttpMethod.POST.name(), URI.create("http://127.0.0.1:" + serverPort + path));
        request.setHeader("content-type", "application/json");
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        request.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        return client.execute(request, ResourceBaseTest::toResponse);
    }

    private static CloseableHttpClient newClient() {
        return HttpClientBuilder.create().disableAutomaticRetries().build();
    }

    private static String readFixture(String name) throws IOException {
        try (InputStream stream = BuiltinTranslatorApiTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
