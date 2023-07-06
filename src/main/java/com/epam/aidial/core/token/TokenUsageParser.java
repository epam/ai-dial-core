package com.epam.aidial.core.token;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.buffer.Buffer;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Arrays;

@Slf4j
@UtilityClass
public class TokenUsageParser {

    public TokenUsage parse(Buffer body) {
        try {
            return parseUsage(body);
        } catch (Throwable e) {
            log.warn("Can't parse token usage: {}", e.getMessage());
            return null;
        }
    }

    private TokenUsage parseUsage(Buffer body) throws Exception {
        int index = findUsage(body);
        if (index < 0) {
            return null;
        }

        ByteBuf slice = body.slice(index, body.length()).getByteBuf();
        JsonFactory factory = new JsonFactory();

        try (InputStream stream = new ByteBufInputStream(slice); JsonParser parser = factory.createParser(stream)) {
            TokenUsage usage = new TokenUsage();
            verify(parser.nextToken(), JsonToken.START_OBJECT);

            while (true) {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_OBJECT) {
                    return usage;
                }

                verify(token, JsonToken.FIELD_NAME);
                String name = parser.getCurrentName();

                token = parser.nextValue();
                verify(token,
                        JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT,
                        JsonToken.VALUE_STRING, JsonToken.VALUE_NULL,
                        JsonToken.VALUE_FALSE, JsonToken.VALUE_TRUE);

                switch (name) {
                    case "completion_tokens" -> usage.setCompletionTokens(parser.getLongValue());
                    case "prompt_tokens" -> usage.setPromptTokens(parser.getLongValue());
                    case "total_tokens" -> usage.setTotalTokens(parser.getLongValue());
                    default -> {
                        // ignore
                    }
                }
            }
        }
    }

    private int findUsage(Buffer body) {
        String token = "\"usage\"";

        search:
        for (int i = body.length() - token.length(); i >= 0; i--) {
            int j = i;

            for (int k = 0; k < token.length(); k++, j++) {
                if (body.getByte(j) != token.charAt(k)) {
                    continue search;
                }
            }

            while (j < body.length()) {
                byte b = body.getByte(j++);
                if (b == ':') {
                    break;
                }

                if (!isWhiteSpace(b)) {
                    continue search;
                }
            }

            for (; j < body.length(); j++) {
                byte b = body.getByte(j);
                if (b == '{') {
                    return j;
                }

                if (!isWhiteSpace(b)) {
                    continue search;
                }
            }
        }

        return -1;
    }

    private boolean isWhiteSpace(byte b) {
        return switch (b) {
            case ' ', '\n', '\t', '\r' -> true;
            default -> false;
        };
    }

    private void verify(JsonToken actual, JsonToken... expected) {
        for (JsonToken candidate : expected) {
            if (candidate == actual) {
                return;
            }
        }

        throw new IllegalArgumentException("Actual: " + actual + "Expected: " + Arrays.toString(expected));
    }
}