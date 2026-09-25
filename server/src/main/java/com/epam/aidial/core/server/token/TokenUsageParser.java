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
     * @param parsedResponse the body already parsed by a caller that needed the tree for its own reasons (e.g.
     *                       tracing), or null/{@code MissingNode} when there is none. It may also be a reduced
     *                       representation of the response built for tracing rather than the full body, so when
     *                       it doesn't carry usage, this falls back to the byte-scan {@link #parse(Buffer)} over
     *                       the original body instead of giving up.
     */
    public TokenUsage parse(Buffer body, JsonNode parsedResponse) {
        JsonNode usage = parsedResponse == null ? null : parsedResponse.get("usage");

        if (usage != null && !usage.isMissingNode()) {
            try {
                return ProxyUtil.MAPPER.treeToValue(usage, TokenUsage.class);
            } catch (Throwable e) {
                log.warn("Can't parse token usage: {}", e.getMessage());
                return null;
            }
        }

        return parse(body);
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