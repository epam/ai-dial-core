package com.epam.aidial.core.server.util;

import com.epam.aidial.core.server.function.BaseResponseFunction;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EventStreamParserTest {

    private final List<String> responses = new ArrayList<>();

    private final BaseResponseFunction fn = new BaseResponseFunction(null, null) {
        @Override
        public Future<Void> apply(ObjectNode json) {
            responses.add(json.toString());
            return Future.succeededFuture();
        }
    };

    @BeforeEach
    public void beforeEach() {
        responses.clear();
    }

    @Test
    public void testHandleChunk_00() {
        EventStreamParser parser = new EventStreamParser(20, fn);
        parser.parse(Buffer.buffer("data"));
        parser.parse(Buffer.buffer(": {\"name\": \"assds"));
        parser.parse(Buffer.buffer("dsdsa 你好。답답해 123 "));
        parser.parse(Buffer.buffer("\"}\r"));
        parser.parse(Buffer.buffer("\nda"));
        parser.parse(Buffer.buffer("ta: {\"value\": 56,    \"text\": \"[DONE]\"}\n"));
        parser.parse(Buffer.buffer("data: [DONE]\r"));
        parser.parse(Buffer.buffer("\ndata: ops one more data\n"));
        assertEquals(List.of("{\"name\":\"assdsdsdsa 你好。답답해 123 \"}", "{\"value\":56,\"text\":\"[DONE]\"}"), responses);
    }

    @Test
    public void testHandleChunk_01() {
        EventStreamParser parser = new EventStreamParser(100, fn);
        parser.parse(Buffer.buffer("data"));
        parser.parse(Buffer.buffer(": {\"name\": \"assds"));
        parser.parse(Buffer.buffer("dsdsa 你好。답답해 123 "));
        parser.parse(Buffer.buffer("\"}\r"));
        parser.parse(Buffer.buffer("\nda"));
        parser.parse(Buffer.buffer("ta: {\"value\": 56,    \"text\": \"[DONE]\"}\n"));
        parser.parse(Buffer.buffer("data: [D"));
        parser.parse(Buffer.buffer("ONE]\r"));
        parser.parse(Buffer.buffer("\ndata: ops one more data\n"));
        assertEquals(List.of("{\"name\":\"assdsdsdsa 你好。답답해 123 \"}", "{\"value\":56,\"text\":\"[DONE]\"}"), responses);
    }

    @Test
    public void testHandleChunkWithBom() {
        EventStreamParser parser = new EventStreamParser(100, fn);
        parser.parse(Buffer.buffer(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}).appendBuffer(Buffer.buffer("data")));
        parser.parse(Buffer.buffer(": {\"name\": \"assds"));
        parser.parse(Buffer.buffer("dsdsa 你好。답답해 123 "));
        parser.parse(Buffer.buffer("\"}\r"));
        parser.parse(Buffer.buffer("\nda"));
        parser.parse(Buffer.buffer("ta: {\"value\": 56,    \"text\": \"[DONE]\"}\n"));
        parser.parse(Buffer.buffer("data: [DONE]\r"));
        parser.parse(Buffer.buffer("\ndata: ops one more data\n"));
        assertEquals(List.of("{\"name\":\"assdsdsdsa 你好。답답해 123 \"}", "{\"value\":56,\"text\":\"[DONE]\"}"), responses);
    }
}
