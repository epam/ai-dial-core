package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonBodyRequestTest {

    @Test
    void testSerialize_forwardsReceivedBytesWhenUnchanged() throws IOException {
        String body = "{ \"model\" : \"m\",\n  \"messages\": [{\"role\": \"user\", \"content\": \"Hello\"}] }";
        Buffer received = Buffer.buffer(body);
        RequestObject request = new ChatCompletionRequest(ReceivedBody.parse(received));

        assertFalse(request.clearInterceptorSettings());
        assertFalse(request.applyDefaults(Map.of()));
        // the received buffer itself, not a copy
        assertSame(received, request.serialize());
    }

    @Test
    void testSerialize_forwardsReceivedBytesForEveryApi() throws IOException {
        String body = "{\"model\": \"m\",  \"input\": \"Hi\", \"messages\": []}";
        for (RequestObject request : List.of(
                new ChatCompletionRequest(ReceivedBody.parse(Buffer.buffer(body))),
                new ResponsesApiRequest(ReceivedBody.parse(Buffer.buffer(body))),
                new MessagesApiRequest(ReceivedBody.parse(Buffer.buffer(body))))) {
            request.setModel("o");

            assertEquals("{\"model\": \"o\",  \"input\": \"Hi\", \"messages\": []}", serialize(request), request.getClass().getName());
        }
    }

    @Test
    void testSerialize_replacesOnlyTheModelInReceivedBytes() throws IOException {
        String body = "{\"messages\": [{\"model\": \"inner\"}],\n \"model\" : \"m\\u002d1\"}";
        RequestObject request = parse(body);
        assertEquals("m-1", request.getModel());

        request.setModel("over\"ride");

        assertEquals("{\"messages\": [{\"model\": \"inner\"}],\n \"model\" : \"over\\\"ride\"}", serialize(request));
    }

    @Test
    void testSerialize_replacesModelAfterMultiByteText() throws IOException {
        // byte offsets, not char offsets: the text before the model is longer in bytes than in chars
        String body = "{\"messages\": [{\"content\": \"Привет 😀 \\\"q\\\"\"}], \"model\": \"m\"}";
        RequestObject request = parse(body);

        request.setModel("é");

        assertEquals(body.replace("\"m\"", "\"é\""), serialize(request));
    }

    @Test
    void testSerialize_serializesTreeWhenBodyIsNotExactlyTheTree() throws IOException {
        // trailing content, a second object: the upstream could read another document than the tree
        for (String body : List.of(
                "{\"model\": \"b\"} garbage",
                "{\"model\": \"b\"}{\"model\": \"c\"}",
                "﻿{\"model\": \"b\"}")) {
            RequestObject request = parse(body);
            request.setModel("o");

            assertEquals("{\"model\":\"o\"}", serialize(request), body);
        }
    }

    @Test
    void testParse_lastRepeatedKeyWinsAndTreeIsSerialized() throws IOException {
        // the upstream could read the other value, so the bytes are not forwarded
        RequestObject request = parse("{\"model\": \"a\", \"stream\": false, \"model\": \"b\"}");

        assertEquals("b", request.getModel());
        assertEquals("{\"model\":\"b\",\"stream\":false}", serialize(request));
    }

    @Test
    void testParse_nestedRepeatedKeySerializesTree() throws IOException {
        RequestObject request = parse("{\"model\": \"m\", \"metadata\": {\"a\": 1, \"a\": 2}}");

        assertEquals("{\"model\":\"m\",\"metadata\":{\"a\":2}}", serialize(request));
    }

    @Test
    void testParse_invalidUtf8SerializesTree() throws IOException {
        // an overlong '/', which the parser decodes as '/' and a strict upstream rejects or reads otherwise
        byte[] prefix = "{\"model\": \"m\", \"text\": \"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        Buffer body = Buffer.buffer().appendBytes(prefix).appendBytes(new byte[] {(byte) 0xC0, (byte) 0xAF}).appendBytes(suffix);
        RequestObject request = new ChatCompletionRequest(ReceivedBody.parse(body));

        assertEquals("m", ProxyUtil.MAPPER.readTree(request.serialize().getBytes()).get("model").asText());
        assertNull(ReceivedBody.parse(body).bytes());
    }

    @Test
    void testParse_invalidUtf8PastTheFirstChunkSerializesTree() throws IOException {
        // an overlong '/' far into the body, after a multi-byte text
        String prefix = "{\"model\": \"m\", \"text\": \"" + "Привет 😀 ".repeat(1000);

        assertNotNull(ReceivedBody.parse(Buffer.buffer(prefix + "\"}")).bytes());
        assertNull(ReceivedBody.parse(Buffer.buffer(prefix).appendBytes(new byte[] {(byte) 0xC0, (byte) 0xAF}).appendString("\"}")).bytes());
    }

    @Test
    void testParse_malformedJsonFails() {
        assertThrows(JsonParseException.class, () -> ReceivedBody.parse(Buffer.buffer("{\"model\": ")));
        assertThrows(JsonParseException.class, () -> ReceivedBody.parse(Buffer.buffer("{\"a\": {\"b\": }}")));
    }

    @Test
    void testSerialize_serializesTreeForNonUtf8OrModellessBody() throws IOException {
        for (Buffer body : List.of(
                Buffer.buffer("{\"model\": \"b\"}".getBytes(StandardCharsets.UTF_16BE)),
                Buffer.buffer("{\"stream\": true}"))) {
            RequestObject request = new ChatCompletionRequest(ReceivedBody.parse(body));
            request.setModel("o");

            assertEquals("o", ProxyUtil.MAPPER.readTree(request.serialize().getBytes()).get("model").asText());
        }
    }

    @Test
    void testSerialize_forwardsReceivedBytesWithSurroundingWhitespace() throws IOException {
        RequestObject request = parse(" \r\n\t{\"model\": \"m\"}\n");
        request.setModel("o");

        assertEquals(" \r\n\t{\"model\": \"o\"}\n", serialize(request));
    }

    @Test
    void testSerialize_serializesTreeWhenEmptyCustomFieldsRemoved() throws IOException {
        RequestObject request = parse("{\"model\": \"m\", \"custom_fields\": {}}");

        request.clearInterceptorSettings();

        assertEquals("{\"model\":\"m\"}", serialize(request));
    }

    @Test
    void testSerialize_serializesTreeForNonStringModel() throws IOException {
        RequestObject request = parse("{\"model\": 1, \"stream\": true}");

        request.setModel("m");

        assertEquals("{\"model\":\"m\",\"stream\":true}", serialize(request));
    }

    @Test
    void testSerialize_serializesTreeWhenDefaultsApplied() throws IOException {
        RequestObject request = parse("{\"model\": \"m\"}");

        assertTrue(request.applyDefaults(Map.of("max_tokens", 10)));
        request.setModel("o");

        assertEquals("{\"model\":\"o\",\"max_tokens\":10}", serialize(request));
    }

    @Test
    void testSerialize_forwardsReceivedBytesWhenDefaultsAlreadyPresent() throws IOException {
        String body = "{\"model\": \"m\", \"max_tokens\": 5, \"metadata\": {\"a\": 1}}";
        RequestObject request = parse(body);

        assertFalse(request.applyDefaults(Map.of("max_tokens", 10, "metadata", Map.of("a", 2))));

        assertEquals(body, serialize(request));
    }

    @Test
    void testSerialize_serializesTreeWhenDefaultReplacesValueOfAnotherType() throws IOException {
        RequestObject request = parse("{\"model\": \"m\", \"max_tokens\": \"5\"}");

        request.applyDefaults(Map.of("max_tokens", 10));

        assertEquals("{\"model\":\"m\",\"max_tokens\":10}", serialize(request));
    }

    @Test
    void testSerialize_serializesTreeWhenNestedDefaultApplied() throws IOException {
        RequestObject request = parse("{\"model\": \"m\", \"metadata\": {\"a\": 1}}");

        request.applyDefaults(Map.of("metadata", Map.of("b", 2)));

        assertEquals("{\"model\":\"m\",\"metadata\":{\"a\":1,\"b\":2}}", serialize(request));
    }

    @Test
    void testSerialize_serializesTreeWhenInterceptorSettingsRemoved() throws IOException {
        RequestObject request = parse("{\"model\": \"m\", \"custom_fields\": {\"interceptor_configuration\": {}, \"configuration\": {}}}");

        assertTrue(request.clearInterceptorSettings());

        assertEquals("{\"model\":\"m\",\"custom_fields\":{\"configuration\":{}}}", serialize(request));
    }

    @Test
    void testParse_rejectsNonObject() {
        assertThrows(IllegalArgumentException.class, () -> ReceivedBody.parse(Buffer.buffer("[1]")));
        assertThrows(IllegalArgumentException.class, () -> ReceivedBody.parse(Buffer.buffer("")));
    }

    private static RequestObject parse(String body) throws IOException {
        return new ChatCompletionRequest(ReceivedBody.parse(Buffer.buffer(body)));
    }

    private static String serialize(RequestObject request) throws IOException {
        return request.serialize().toString(StandardCharsets.UTF_8);
    }
}
