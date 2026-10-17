package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesToMessagesStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResponsesToMessagesStream stream = new ResponsesToMessagesStream("requested", "msg_dial");
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

    @Test
    void testUnknownEventsAreIgnored() throws Exception {
        feed("{\"type\":\"response.output_item.added\",\"item\":{\"type\":\"message\"}}",
                "{\"type\":\"response.output_text.done\",\"text\":\"x\"}");

        assertEquals(List.of(), types());
    }
}
