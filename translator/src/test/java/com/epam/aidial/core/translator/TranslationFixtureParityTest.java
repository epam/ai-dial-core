package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays each fixture the Python translator recorded (input and expected output of one translation function)
 * against the Java port, and compares the output as JSON trees. The fixtures are copied verbatim from the
 * recorder, and every in-scope one must match.
 */
class TranslationFixtureParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> KINDS = List.of("request", "response", "stream");

    /**
     * Features no change of this series translates; a fixture with one is skipped.
     */
    private static final Set<String> OUT_OF_SCOPE_FEATURES = Set.of("image", "document", "search_result", "web_search",
            "web_search_call", "url_citation", "count_tokens", "json_schema", "input_image", "input_file");

    /**
     * Core sends an id it generates for the message, never the upstream's, so both sides get this one.
     */
    private static final String MESSAGE_ID = "msg_placeholder";

    private static final Path FIXTURES = resource("translation-fixtures");

    @SneakyThrows
    static Stream<String> fixtures() {
        List<String> fixtures = new ArrayList<>();
        for (String kind : KINDS) {
            try (Stream<Path> files = Files.list(FIXTURES.resolve(kind))) {
                files.map(file -> kind + "/" + file.getFileName()).sorted().forEach(fixtures::add);
            }
        }
        return fixtures.stream();
    }

    @Test
    @SneakyThrows
    void testEveryRecordedFixtureIsCopied() {
        JsonNode counts = MAPPER.readTree(FIXTURES.resolve("manifest.json").toFile()).path("counts");
        for (String kind : KINDS) {
            try (Stream<Path> files = Files.list(FIXTURES.resolve(kind))) {
                assertEquals(counts.path(kind).asLong(), files.count(), kind);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void testFixture(String fixture) {
        JsonNode recorded = read(FIXTURES.resolve(fixture));
        String outOfScope = outOfScopeFeature(recorded);
        if (outOfScope != null) {
            Assumptions.abort("out of scope: " + outOfScope);
        }
        String kind = recorded.path("kind").asText();
        JsonNode expected = normalise(kind, recorded.path("output").deepCopy());
        JsonNode actual;
        try {
            actual = normalise(kind, roundTrip(replay(recorded)));
        } catch (RuntimeException e) {
            // a throw is reported as a mismatch; an unsupported kind or call always throws
            actual = MAPPER.getNodeFactory().textNode(e.toString());
        }
        boolean matches = expected.equals(actual);

        JsonNode reported = actual;
        assertTrue(matches, () -> fixture + " does not match " + recorded.path("tests")
                + "\nexpected: " + pretty(expected) + "\nactual:   " + pretty(reported));
    }

    private static JsonNode replay(JsonNode recorded) {
        JsonNode input = recorded.path("input");
        return switch (recorded.path("kind").asText()) {
            case "request" -> replayRequest(input);
            case "response" -> replayResponse(input);
            case "stream" -> replayStream(input);
            default -> throw new UnsupportedOperationException("unknown fixture kind");
        };
    }

    private static JsonNode replayRequest(JsonNode input) {
        ObjectNode output = MAPPER.createObjectNode();
        try {
            output.set("body", MessagesToResponses.translateRequest(input.path("request"), input.path("model").asText(), new ToolNames()));
        } catch (UntranslatableRequestException e) {
            // Core answers it with a 400, and invalid_request_error is the Anthropic type of a 400
            output.putObject("error").put("status", 400).put("type", "invalid_request_error").put("message", e.getMessage());
            return output;
        }
        // echoed: every request fixture passes null and records null, or passes {} and records {}, so no recorded
        // map tells the echo from a real one; ToolNamesTest checks the aliases
        output.set("tool_names", input.get("tool_names"));
        return output;
    }

    private static JsonNode replayResponse(JsonNode input) {
        ObjectNode output = MAPPER.createObjectNode();
        try {
            output.set("message", ResponsesToMessages.translateResponse(input.path("response"), input.path("requested_model").asText(), MESSAGE_ID,
                    seededToolNames(input), input.path("omit_thinking").asBoolean()));
        } catch (UpstreamFailureException e) {
            output.putObject("error").put("status", e.getStatus()).put("type", e.getType()).put("message", e.getMessage());
        }
        return output;
    }

    private static JsonNode replayStream(JsonNode input) {
        ResponsesToMessagesStream stream = new ResponsesToMessagesStream(input.path("requested_model").asText(), MESSAGE_ID, seededToolNames(input),
                input.path("omit_thinking").asBoolean());
        ArrayNode output = MAPPER.createArrayNode();
        for (JsonNode call : input.path("calls")) {
            List<String> frames;
            if (call.has("handle")) {
                JsonNode handle = call.path("handle");
                ObjectNode data = handle.path("data").deepCopy();
                // Core dispatches on the data's type; a few recorded events carry it only beside the data
                if (!data.has("type")) {
                    data.put("type", handle.path("event_type").asText());
                }
                frames = stream.handle(data);
            } else if (call.has("finalize")) {
                frames = stream.finish();
            } else if (call.has("emit_error")) {
                JsonNode error = call.path("emit_error");
                frames = stream.emitError(error.path("type").asText(), error.path("message").asText());
            } else {
                throw new UnsupportedOperationException("no counterpart of " + call.fieldNames().next());
            }
            ArrayNode events = output.addArray();
            for (String frame : frames) {
                events.add(parseFrame(frame));
            }
        }
        return output;
    }

    /**
     * Seeded the way a request translation fills it, so the recorded aliases are checked too.
     */
    private static ToolNames seededToolNames(JsonNode input) {
        ToolNames toolNames = new ToolNames();
        input.path("tool_names").properties().forEach(entry ->
                assertEquals(entry.getValue().asText(), toolNames.alias(entry.getKey(), Set.of()), entry.getKey()));
        return toolNames;
    }

    @SneakyThrows
    private static ObjectNode parseFrame(String frame) {
        String[] lines = frame.split("\n");
        ObjectNode event = MAPPER.createObjectNode();
        event.put("event", lines[0].substring("event: ".length()));
        event.set("data", MAPPER.readTree(lines[1].substring("data: ".length())));
        return event;
    }

    private static JsonNode normalise(String kind, JsonNode output) {
        if ("response".equals(kind) && output.path("message").isObject()) {
            ((ObjectNode) output.path("message")).put("id", MESSAGE_ID);
        }
        if ("stream".equals(kind) && output.isArray()) {
            for (JsonNode events : output) {
                for (JsonNode event : events) {
                    JsonNode message = event.path("data").path("message");
                    if ("message_start".equals(event.path("event").asText()) && message.isObject()) {
                        ((ObjectNode) message).put("id", MESSAGE_ID);
                    }
                }
            }
        }
        return output;
    }

    private static String outOfScopeFeature(JsonNode recorded) {
        for (JsonNode feature : recorded.path("features")) {
            String name = feature.asText();
            if (OUT_OF_SCOPE_FEATURES.contains(name) || name.startsWith("web_search_")) {
                return name;
            }
        }
        return null;
    }

    @SneakyThrows
    private static JsonNode read(Path file) {
        return MAPPER.readTree(file.toFile());
    }

    /**
     * Through text and back, so a {@code long} the Java code wrote equals an {@code int} of the same value.
     */
    @SneakyThrows
    private static JsonNode roundTrip(JsonNode node) {
        return MAPPER.readTree(MAPPER.writeValueAsString(node));
    }

    @SneakyThrows
    private static String pretty(JsonNode node) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    @SneakyThrows
    private static Path resource(String name) {
        return Path.of(Objects.requireNonNull(TranslationFixtureParityTest.class.getClassLoader().getResource(name), name).toURI());
    }
}
