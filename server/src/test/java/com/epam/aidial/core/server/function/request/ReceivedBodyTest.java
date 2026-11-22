package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceivedBodyTest {

    @Test
    void testWithModel_splicesTheModelAndLeavesTheOriginalAsItWas() throws IOException {
        Buffer bytes = Buffer.buffer("{ \"model\" : \"m\", \"stream\": true }");
        ReceivedBody received = ReceivedBody.parse(bytes);

        ReceivedBody renamed = received.withModel("other");

        assertTrue(renamed.hasBytes());
        assertSame(received.tree(), renamed.tree());
        assertEquals("{ \"model\" : \"other\", \"stream\": true }", renamed.serialize().toString());
        assertSame(bytes, received.serialize());
    }

    @Test
    void testWithModel_dropsTheBytesForNullModel() throws IOException {
        ReceivedBody received = ReceivedBody.parse(Buffer.buffer("{\"model\": \"m\"}"));

        received.tree().putNull("model");
        ReceivedBody renamed = received.withModel(null);

        assertFalse(renamed.hasBytes());
        assertEquals("{\"model\":null}", renamed.serialize().toString());
    }

    @Test
    void testWithModel_dropsTheBytesWithoutModelString() throws IOException {
        ReceivedBody received = ReceivedBody.parse(Buffer.buffer("{\"stream\": true}"));
        assertTrue(received.hasBytes());

        received.tree().put("model", "m");
        ReceivedBody renamed = received.withModel("m");

        assertFalse(renamed.hasBytes());
        assertEquals("{\"stream\":true,\"model\":\"m\"}", renamed.serialize().toString());
    }

    @Test
    void testWithoutBytes_serializesTheTree() throws IOException {
        ReceivedBody received = ReceivedBody.parse(Buffer.buffer("{\"model\": \"m\"}"));

        received.tree().put("temperature", 1);
        ReceivedBody changed = received.withoutBytes();

        assertFalse(changed.hasBytes());
        assertEquals("{\"model\":\"m\",\"temperature\":1}", changed.serialize().toString());
        assertSame(changed, changed.withoutBytes());
    }

    @Test
    void testOf_isTheTreeSerialized() throws IOException {
        ReceivedBody body = ReceivedBody.of(ProxyUtil.MAPPER.createObjectNode().put("model", "m"));

        assertFalse(body.hasBytes());
        assertFalse(body.withModel("o").hasBytes());
        assertEquals("{\"model\":\"m\"}", body.serialize().toString());
    }

    @Test
    void testParse_modelPastTheParserBufferWithMultiByteTextAcrossItsEdge() throws IOException {
        // the parser reads 8000 bytes at a time; a 4-byte char straddles that edge for three of the four paddings
        for (int padding = 0; padding < 4; padding++) {
            String text = "x".repeat(padding) + "😀".repeat(5000);
            String body = "{\"messages\": [{\"content\": \"" + text + "\"}], \"model\": \"m\"}";
            ReceivedBody received = ReceivedBody.parse(Buffer.buffer(body));

            assertTrue(received.hasBytes(), "padding " + padding);
            assertEquals(body.replace("\"m\"}", "\"é\"}"), received.withModel("é").serialize().toString(StandardCharsets.UTF_8), "padding " + padding);
        }
    }

    @Test
    void testToString_neverShowsTheBody() throws IOException {
        assertEquals("ReceivedBody[bytes=21]", ReceivedBody.parse(Buffer.buffer("{\"model\": \"secret\"}  ")).toString());
        assertEquals("ReceivedBody[bytes=none]", ReceivedBody.of(ProxyUtil.MAPPER.createObjectNode().put("p", "secret")).toString());
    }
}
