package com.epam.aidial.core.server.token;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.buffer.Buffer;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
@UtilityClass
public class TokenUsageParser {

    /**
     * For a caller holding only the raw body: scans the bytes backwards for the last {@code "usage"} object and
     * parses that alone, so a large body is never fully parsed. Use {@link #parse(JsonNode)} instead when the
     * response has already been parsed for another reason.
     */
    public TokenUsage parse(Buffer body) {
        try {
            return parseUsage(body);
        } catch (Throwable e) {
            log.warn("Can't parse token usage: {}", e.getMessage());
            return null;
        }
    }

    /**
     * For a caller that already parsed the response: finds the same {@code usage} object as
     * {@link #findUsage(Buffer)}, or returns {@code null} when the response carries none.
     */
    public TokenUsage parse(JsonNode response) {
        JsonNode usage = findUsage(response);
        if (usage == null) {
            return null;
        }
        try {
            return ProxyUtil.MAPPER.treeToValue(usage, TokenUsage.class);
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

    /**
     * The {@link JsonNode} equivalent of {@link #findUsage(Buffer)}: both select whichever field literally
     * named {@code "usage"} with an object value occurs LAST in document order - at any nesting depth, not
     * only at the top level, and not the first one found. {@link #findUsage(Buffer)} scans the raw bytes
     * right to left and returns on the first (i.e. rightmost) {@code "usage":{...}} it sees; a parsed tree
     * preserves field insertion order exactly as the source text had it, so walking it depth-first while
     * always keeping the most-recently-seen candidate reaches the very same field, without re-parsing or
     * re-serializing anything. A response with both a top-level {@code usage} and a nested one (for example
     * under {@code custom_fields.upstream_usage}, which is serialized after the top-level field) resolves to
     * the nested one here exactly as it does for {@link #findUsage(Buffer)}.
     */
    private JsonNode findUsage(JsonNode response) {
        return findLastUsage(response, null);
    }

    private JsonNode findLastUsage(JsonNode node, JsonNode last) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                if (entry.getKey().equals("usage") && entry.getValue().isObject()) {
                    last = entry.getValue();
                }
                last = findLastUsage(entry.getValue(), last);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                last = findLastUsage(element, last);
            }
        }
        return last;
    }

    private boolean isWhiteSpace(byte b) {
        return switch (b) {
            case ' ', '\n', '\t', '\r' -> true;
            default -> false;
        };
    }

}