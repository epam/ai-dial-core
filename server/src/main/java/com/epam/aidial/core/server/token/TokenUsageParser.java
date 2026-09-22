package com.epam.aidial.core.server.token;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.buffer.Buffer;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

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

    /**
     * For a caller that already parsed the body into a tree for its own purposes - reads the top-level
     * {@code usage} field directly instead of reparsing the same bytes. Equivalent to {@link #parse(Buffer)}
     * for a complete, well-formed response body; only the byte-scan's leniency for a body that doesn't fully
     * parse (or a streamed body with more than one {@code usage} occurrence) is not needed here, since every
     * caller of this overload already has a single successfully-parsed JSON object.
     */
    public TokenUsage parse(JsonNode tree) {
        try {
            JsonNode usage = tree.get("usage");
            if (usage == null || !usage.isObject()) {
                return null;
            }
            return ProxyUtil.MAPPER.convertValue(usage, TokenUsage.class);
        } catch (Throwable e) {
            log.warn("Can't parse token usage: {}", e.getMessage());
            return null;
        }
    }

    private TokenUsage parseUsage(Buffer body) {
        int index = findUsage(body);
        if (index < 0) {
            return null;
        }

        Buffer slice = body.slice(index, body.length());

        return ProxyUtil.convertToObject(slice, TokenUsage.class);
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

}