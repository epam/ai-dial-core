package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * A JSON object body as received from the client: the parsed tree and, when the upstream reads exactly what the tree
 * holds, the bytes it was parsed from, so they can be forwarded without serializing the tree.
 *
 * @param tree the parsed body
 * @param bytes the received bytes, or {@code null} when they must not be forwarded as they are
 * @param modelStart start of the top-level model string in {@code bytes}, quote included; -1 when there is none
 * @param modelEnd end of that string, quote included
 */
public record ReceivedBody(ObjectNode tree, @Nullable Buffer bytes, int modelStart, int modelEnd) {

    private static final String MODEL_NODE = "model";

    public static ReceivedBody of(ObjectNode tree) {
        return new ReceivedBody(tree, null, -1, -1);
    }

    /**
     * Parses the body. The received bytes are kept only when they are valid UTF-8 holding one object, with nothing but
     * whitespace around it and no repeated key at any level, so that the upstream cannot read another value than DIAL
     * does. Otherwise the tree is parsed as before (a repeated key: the last value wins) and will be serialized.
     *
     * @throws IllegalArgumentException if the body is not a JSON object
     */
    public static ReceivedBody parse(Buffer body) throws IOException {
        try {
            return parseStrict(body);
        } catch (JsonProcessingException e) {
            // a repeated key, or malformed JSON that fails again here
            return of(ProxyUtil.parseObject(body));
        }
    }

    private static ReceivedBody parseStrict(Buffer body) throws IOException {
        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode();
        int modelStart = -1;
        int modelEnd = -1;
        long rootStart;
        long rootEnd;
        try (JsonParser parser = ProxyUtil.MAPPER.createParser((InputStream) new ByteBufInputStream(body.getByteBuf()))) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Invalid json object");
            }
            // a byte offset is -1 for a body that is not UTF-8
            rootStart = parser.currentTokenLocation().getByteOffset();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                boolean stringModel = parser.nextToken() == JsonToken.VALUE_STRING && name.equals(MODEL_NODE);
                long start = parser.currentTokenLocation().getByteOffset();
                tree.set(name, ProxyUtil.MAPPER.readTree(parser));
                if (stringModel) {
                    modelStart = (int) start;
                    modelEnd = (int) parser.currentLocation().getByteOffset();
                }
            }
            rootEnd = parser.currentLocation().getByteOffset();
        }
        boolean forwardable = rootStart >= 0 && isWhitespace(body, 0, rootStart) && isWhitespace(body, rootEnd, body.length())
                && isUtf8(body);
        return forwardable ? new ReceivedBody(tree, body, modelStart, modelEnd) : of(tree);
    }

    private static boolean isWhitespace(Buffer body, long from, long to) {
        for (int i = (int) from; i < to; i++) {
            byte b = body.getByte(i);
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                return false;
            }
        }
        return true;
    }

    /**
     * The parser decodes some invalid UTF-8 leniently (e.g. an overlong or surrogate sequence), which the upstream may
     * decode otherwise.
     */
    private static boolean isUtf8(Buffer body) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        ByteBuffer in = body.getByteBuf().nioBuffer();
        CharBuffer out = CharBuffer.allocate(8192);
        while (true) {
            CoderResult result = decoder.decode(in, out, true);
            if (result.isError()) {
                return false;
            }
            if (result.isUnderflow()) {
                return true;
            }
            out.clear();
        }
    }

    /**
     * The received bytes with the model string replaced by {@code model}, if given.
     */
    byte[] withModel(@Nullable String model) throws JsonProcessingException {
        if (model == null) {
            return bytes.getBytes();
        }
        byte[] value = ProxyUtil.MAPPER.writeValueAsBytes(model);
        return Buffer.buffer(bytes.length() - (modelEnd - modelStart) + value.length)
                .appendBuffer(bytes, 0, modelStart)
                .appendBytes(value)
                .appendBuffer(bytes, modelEnd, bytes.length() - modelEnd)
                .getBytes();
    }
}
