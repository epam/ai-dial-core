package com.epam.aidial.core.server.sse;

import io.netty.util.IllegalReferenceCountException;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SseParserTest {

    private static class TestSseEventListener implements SseEventListener {

        StringBuilder response = new StringBuilder();

        @Override
        public void onEvent(SseEvent event) {
            response.append(event.toString());
        }

        @Override
        public void onComment(String comment) {
            response.append(':').append(comment).append('\n');
        }

        @Override
        public void onComplete() {
            // do nothing
        }
    }

    @Test
    public void testParse_00() {
        TestSseEventListener listener = new TestSseEventListener();
        SseParser parser = new SseParser(128, listener);

        parser.parse(Buffer.buffer("data"));
        parser.parse(Buffer.buffer(": {\"name\": \"assds"));
        parser.parse(Buffer.buffer("dsdsa 你好。답답해 123 "));
        parser.parse(Buffer.buffer("\"}\r"));
        parser.parse(Buffer.buffer("\n\rda"));
        parser.parse(Buffer.buffer("ta: {\"value\": 56,    \"text\": \"[DONE]\"}\n\r"));
        parser.parse(Buffer.buffer("data: [DONE]\r\r"));
        parser.finish();

        String response = """
                data: {"name": "assdsdsdsa 你好。답답해 123 "}
                
                data: {"value": 56,    "text": "[DONE]"}
                
                data: [DONE]
                
                """;
        assertEquals(response, listener.response.toString());
    }

    @Test
    public void testParse_01() {
        TestSseEventListener listener = new TestSseEventListener();
        SseParser parser = new SseParser(128, listener);

        parser.parse(Buffer.buffer("data"));
        parser.parse(Buffer.buffer(": {\"name\": \"assds"));
        parser.parse(Buffer.buffer("dsdsa 你好。답답해 123 "));
        parser.parse(Buffer.buffer("\"}\r"));
        parser.parse(Buffer.buffer("\n\nda"));
        parser.parse(Buffer.buffer("ta: {\"value\": 56,    \"text\": \"[DONE]\"}\n\r"));
        parser.parse(Buffer.buffer("data: [D"));
        parser.parse(Buffer.buffer("ONE]"));
        parser.finish();

        String response = """
                data: {"name": "assdsdsdsa 你好。답답해 123 "}
                
                data: {"value": 56,    "text": "[DONE]"}
                
                data: [DONE]
                
                """;
        assertEquals(response, listener.response.toString());
    }

    @Test
    public void testParse_02() {
        TestSseEventListener listener = new TestSseEventListener();
        SseParser parser = new SseParser(128, listener);

        //BOM
        parser.parse(Buffer.buffer(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}));

        parser.parse(Buffer.buffer("data"));
        parser.parse(Buffer.buffer(": {\"name\": \"assds"));
        parser.parse(Buffer.buffer("dsdsa 你好。답답해 123 "));
        parser.parse(Buffer.buffer("\"}\r"));
        parser.parse(Buffer.buffer("\n\nda"));
        parser.parse(Buffer.buffer("ta: {\"value\": 56,    \"text\": \"[DONE]\"}\n\r"));
        parser.parse(Buffer.buffer("data: [D"));
        parser.parse(Buffer.buffer("ONE]"));
        parser.finish();

        String response = """
                data: {"name": "assdsdsdsa 你好。답답해 123 "}
                
                data: {"value": 56,    "text": "[DONE]"}
                
                data: [DONE]
                
                """;
        assertEquals(response, listener.response.toString());
    }

    @Test
    public void testParse_03() {
        TestSseEventListener listener = new TestSseEventListener();
        SseParser parser = new SseParser(128, listener);

        parser.parse(Buffer.buffer("data"));
        parser.parse(Buffer.buffer(": hi\r"));
        parser.parse(Buffer.buffer("event: greeting"));
        parser.parse(Buffer.buffer("\r"));
        parser.parse(Buffer.buffer("\nda"));
        parser.parse(Buffer.buffer("ta: 1 + 1 = ?\r"));
        parser.parse(Buffer.buffer("event: ques"));
        parser.parse(Buffer.buffer("tion\n\r"));
        parser.finish();

        String response = """
                event: greeting
                data: hi
                
                event: question
                data: 1 + 1 = ?
                
                """;
        assertEquals(response, listener.response.toString());
    }

    @Test
    public void testClose_releasesChunkBuffer() {
        TestSseEventListener listener = new TestSseEventListener();
        SseParser parser = new SseParser(128, listener);

        parser.parse(Buffer.buffer("data: hi\n\n"));
        parser.finish();
        parser.close();

        assertEquals(0, parser.chunkBufferRefCnt());
        assertThrows(IllegalReferenceCountException.class, parser::close);
    }
}
