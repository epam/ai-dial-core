package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.vertx.core.buffer.Buffer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * A JSON object body as received from the client: the parsed tree and, when the upstream reads exactly what the tree
 * holds, the bytes it was parsed from, so they can be forwarded without serializing the tree. It is built only by
 * {@link #parse} and {@link #of}, which keep the bytes and the model range consistent with the tree.
 */
public final class ReceivedBody {

    private static final String MODEL_NODE = "model";
    // a repeated key below the top level fails the read as a MismatchedInputException
    private static final ObjectReader STRICT_READER = ProxyUtil.MAPPER.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private final ObjectNode tree;
    // null when the bytes must not be forwarded as they are
    @Nullable
    private final Buffer bytes;
    // the top-level model string in bytes, quotes included; -1 when there is none
    private final int modelStart;
    private final int modelEnd;

    private ReceivedBody(ObjectNode tree, @Nullable Buffer bytes, int modelStart, int modelEnd) {
        this.tree = tree;
        this.bytes = bytes;
        this.modelStart = modelStart;
        this.modelEnd = modelEnd;
    }

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
        } catch (MismatchedInputException e) {
            // a repeated key below the top level
            return of(ProxyUtil.parseObject(body));
        }
    }

    private static ReceivedBody parseStrict(Buffer body) throws IOException {
        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode();
        boolean repeatedKey = false;
        int modelStart = -1;
        int modelEnd = -1;
        long rootStart;
        long rootEnd;
        try (JsonParser parser = ProxyUtil.MAPPER.createParser((InputStream) new ByteBufInputStream(body.getByteBuf()))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("Invalid json object");
            }
            // a byte offset is -1 for a body that is not UTF-8
            rootStart = parser.currentTokenLocation().getByteOffset();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                boolean stringModel = parser.nextToken() == JsonToken.VALUE_STRING && name.equals(MODEL_NODE);
                long start = parser.currentTokenLocation().getByteOffset();
                JsonNode value = STRICT_READER.readTree(parser);
                repeatedKey |= tree.replace(name, value) != null;
                if (stringModel) {
                    modelStart = (int) start;
                    modelEnd = (int) parser.currentLocation().getByteOffset();
                }
            }
            rootEnd = parser.currentLocation().getByteOffset();
        }
        boolean forwardable = !repeatedKey && rootStart >= 0 && isWhitespace(body, 0, rootStart) && isWhitespace(body, rootEnd, body.length())
                // the parser decodes some invalid UTF-8 leniently (e.g. an overlong or surrogate sequence), which the upstream may decode otherwise
                && ByteBufUtil.isText(body.getByteBuf(), StandardCharsets.UTF_8);
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

    public ObjectNode tree() {
        return tree;
    }

    @Nullable
    Buffer bytes() {
        return bytes;
    }

    boolean hasModel() {
        return modelStart >= 0;
    }

    /**
     * The received bytes with the model string replaced by {@code model}, if given.
     */
    Buffer withModel(@Nullable String model) throws JsonProcessingException {
        if (model == null) {
            return bytes;
        }
        byte[] value = ProxyUtil.MAPPER.writeValueAsBytes(model);
        return Buffer.buffer(bytes.length() - (modelEnd - modelStart) + value.length)
                .appendBuffer(bytes, 0, modelStart)
                .appendBytes(value)
                .appendBuffer(bytes, modelEnd, bytes.length() - modelEnd);
    }
}
