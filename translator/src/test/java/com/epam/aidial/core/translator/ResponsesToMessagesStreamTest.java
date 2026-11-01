package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesToMessagesStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CALL_ADDED = """
            {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"call_1","name":"Read","arguments":""}}""";
    private static final String CALL_DONE = """
            {"type":"response.output_item.done","output_index":1,
             "item":{"type":"function_call","call_id":"call_1","name":"Read","arguments":"{\\"file_path\\":\\"a.txt\\"}"}}""";
    private static final String COMPLETED = "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}";

    private final ToolNames toolNames = new ToolNames();
    private final ResponsesToMessagesStream stream = new ResponsesToMessagesStream("requested", "msg_dial", toolNames, false);
    private final List<JsonNode> sent = new ArrayList<>();

    private void feed(String... events) throws Exception {
        for (String event : events) {
            collect(stream.handle(MAPPER.readTree(event)));
        }
    }

    private void collect(List<String> frames) throws Exception {
        for (String frame : frames) {
            String[] lines = frame.split("\n");
            JsonNode data = MAPPER.readTree(lines[1].substring("data: ".length()));
            // the event line and the type field must agree: the SDKs read the latter
            assertEquals(lines[0].substring("event: ".length()), data.path("type").asText());
            assertTrue(frame.endsWith("\n\n"));
            sent.add(data);
        }
    }

    private List<String> types() {
        return sent.stream().map(data -> data.path("type").asText()).toList();
    }

    @Test
    void testTextStream() throws Exception {
        feed("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"model\":\"upstream\"}}",
                "{\"type\":\"response.in_progress\",\"response\":{\"id\":\"resp_1\"}}",
                "{\"type\":\"response.content_part.added\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\"}}",
                "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hel\"}",
                "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"lo\"}",
                "{\"type\":\"response.content_part.done\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\"}}",
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":7,\"output_tokens\":2}}}");
        collect(stream.finish());

        assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta", "content_block_delta",
                "content_block_stop", "message_delta", "message_stop"), types());
        JsonNode message = sent.get(0).path("message");
        // the upstream's own response id never reaches the client
        assertEquals("msg_dial", message.path("id").asText());
        assertEquals("upstream", message.path("model").asText());
        assertEquals("Hello", sent.get(3).path("delta").path("text").asText() + sent.get(4).path("delta").path("text").asText());
        JsonNode delta = sent.get(6);
        assertEquals("end_turn", delta.path("delta").path("stop_reason").asText());
        assertEquals(7, delta.path("usage").path("input_tokens").asInt());
        assertEquals(2, delta.path("usage").path("output_tokens").asInt());
    }

    @Test
    void testRefusalOpensLazilyAndSetsTheStopReason() throws Exception {
        feed("{\"type\":\"response.refusal.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"No\"}",
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");

        assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta", "content_block_stop",
                "message_delta", "message_stop"), types());
        // the message falls back to the requested model when the upstream never said which one answered
        assertEquals("requested", sent.get(0).path("message").path("model").asText());
        assertEquals("refusal", sent.get(5).path("delta").path("stop_reason").asText());
    }

    @Test
    void testNewCoordinateClosesTheOpenBlockFirst() throws Exception {
        feed("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"a\"}",
                "{\"type\":\"response.output_text.delta\",\"output_index\":1,\"content_index\":0,\"delta\":\"b\"}",
                "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");

        assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop"), types());
        assertEquals(1, sent.get(5).path("index").asInt());
    }

    @Test
    void testEmptyAnswerStillHasOneBlock() throws Exception {
        feed("{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}");

        assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_stop", "message_delta", "message_stop"), types());
        assertEquals("max_tokens", sent.get(4).path("delta").path("stop_reason").asText());
    }

    @Test
    void testSummaryPartsOfOneItemAreSeparated() throws Exception {
        feed("{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"summary_index\":0,\"delta\":\"one\"}",
                "{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"summary_index\":0,\"delta\":\" more\"}",
                "{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"summary_index\":1,\"delta\":\"two\"}",
                COMPLETED);

        assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta", "content_block_delta",
                "content_block_delta", "content_block_stop", "message_delta", "message_stop"), types());
        assertEquals(List.of("one", " more", "\n\ntwo"), sent.subList(3, 6).stream().map(data -> data.path("delta").path("thinking").asText()).toList());
    }

    @Test
    void testStreamWithoutTerminalEventEndsInError() throws Exception {
        feed("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"cut\"}");
        collect(stream.finish());

        assertEquals("error", types().get(types().size() - 1));
        assertEquals("api_error", sent.get(sent.size() - 1).path("error").path("type").asText());
    }

    @Test
    void testFailedResponseIsAnErrorAndNothingFollowsIt() throws Exception {
        feed("{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"slow down\"}}}",
                "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"late\"}");
        collect(stream.finish());

        assertEquals(List.of("error"), types());
        assertEquals("rate_limit_error", sent.get(0).path("error").path("type").asText());
        assertEquals("slow down", sent.get(0).path("error").path("message").asText());
    }

    @Test
    void testErrorEventCarriesItsOwnShape() throws Exception {
        feed("{\"type\":\"error\",\"code\":\"invalid_request_error\",\"message\":\"bad\"}");

        assertEquals("invalid_request_error", sent.get(0).path("error").path("type").asText());
        assertEquals("bad", sent.get(0).path("error").path("message").asText());
    }

    private static String argumentsDelta(int outputIndex, String delta) {
        return "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":" + outputIndex + ",\"delta\":\"" + delta + "\"}";
    }

    private List<String> partialJson() {
        return sent.stream()
                .filter(data -> "input_json_delta".equals(data.path("delta").path("type").asText()))
                .map(data -> data.path("delta").path("partial_json").asText())
                .toList();
    }

    @Test
    void testFunctionCallOpensToolUseBlockAfterClosingText() throws Exception {
        feed("{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Reading\"}",
                CALL_ADDED,
                argumentsDelta(1, "{\\\"file_path\\\":"),
                argumentsDelta(1, ""),
                argumentsDelta(1, " \\\"a.txt\\\"}"),
                CALL_DONE,
                COMPLETED);

        assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_delta", "content_block_delta", "content_block_stop",
                "message_delta", "message_stop"), types());
        assertEquals(MAPPER.readTree("""
                {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_1","name":"Read","input":{}}}"""),
                sent.get(5));
        // each delta as it came, the empty one included, and output_item.done sends no second copy
        assertEquals(List.of("{\"file_path\":", "", " \"a.txt\"}"), partialJson());
        assertEquals(1, sent.get(9).path("index").asInt());
        assertEquals("tool_use", sent.get(10).path("delta").path("stop_reason").asText());
    }

    @Test
    void testArgumentsFromDoneEventWhenNoDeltaCame() throws Exception {
        feed(CALL_ADDED,
                argumentsDelta(1, ""),
                "{\"type\":\"response.function_call_arguments.done\",\"output_index\":1,\"arguments\":\"{\\\"query\\\":\\\"incident code\\\"}\"}",
                CALL_DONE,
                COMPLETED);

        assertEquals(List.of("", "{\"query\":\"incident code\"}"), partialJson());
        // a tool call alone is the whole answer: no empty text block next to it
        assertEquals(List.of("message_start", "ping", "content_block_start", "content_block_delta", "content_block_delta",
                "content_block_stop", "message_delta", "message_stop"), types());
    }

    @Test
    void testArgumentsFromOutputItemDoneWhenNothingElseCarriedThem() throws Exception {
        feed(CALL_ADDED, CALL_DONE, COMPLETED);

        assertEquals(List.of("{\"file_path\":\"a.txt\"}"), partialJson());
    }

    @Test
    void testDeltaForCallWhoseBlockIsNotOpenIsDropped() throws Exception {
        feed(CALL_ADDED,
                argumentsDelta(2, "{}"),
                "{\"type\":\"response.function_call_arguments.done\",\"output_index\":2,\"arguments\":\"{}\"}",
                "{\"type\":\"response.output_item.done\",\"output_index\":2,\"item\":{\"type\":\"function_call\",\"arguments\":\"{}\"}}");

        assertEquals(List.of("message_start", "ping", "content_block_start"), types());
    }

    @Test
    void testCutOffToolCallStopsOnMaxTokens() throws Exception {
        feed(CALL_ADDED, argumentsDelta(1, "{\\\"file"),
                "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}");

        assertEquals("content_block_stop", types().get(4));
        assertEquals("max_tokens", sent.get(5).path("delta").path("stop_reason").asText());
    }

    @Test
    void testAliasedNameIsRestored() throws Exception {
        String original = "mcp__dial-knowledge-base__search_documents_by_semantic_similarity_v2";
        String alias = toolNames.alias(original, Set.of());

        feed(CALL_ADDED.replace("\"Read\"", "\"" + alias + "\""), CALL_ADDED.replace("\"Read\"", "\"Unknown\"").replace(":1", ":2"));

        assertEquals(original, sent.get(2).path("content_block").path("name").asText());
        assertEquals("Unknown", sent.get(4).path("content_block").path("name").asText());
    }

    @Test
    void testUnknownEventsAreIgnored() throws Exception {
        feed("{\"type\":\"response.output_item.added\",\"item\":{\"type\":\"message\"}}",
                "{\"type\":\"response.output_text.done\",\"text\":\"x\"}");

        assertEquals(List.of(), types());
    }
}
