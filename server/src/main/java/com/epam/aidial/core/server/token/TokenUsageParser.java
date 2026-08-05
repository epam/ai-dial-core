package com.epam.aidial.core.server.token;

import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.buffer.Buffer;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

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

    private TokenUsage parseUsage(Buffer body) {
        int index = findUsage(body);
        if (index < 0) {
            return null;
        }

        Buffer slice = body.slice(index, body.length());

        return ProxyUtil.convertToObject(slice, TokenUsage.class);
    }

    /**
     * Finds the deployment's own top-level {@code usage} object by scanning backward for the last
     * match, same as before. A deployment's response may also carry {@code statistics.usage_per_model},
     * whose entries are {@code {index, model, usage: {...}}} - every element has its own nested
     * {@code "usage"} key, which would otherwise be found first by the backward scan and misparsed as
     * the deployment's own usage. Byte ranges of every top-level {@code "statistics"} value are
     * located first and excluded from the scan so nested {@code usage} keys can never match.
     */
    private int findUsage(Buffer body) {
        List<int[]> excludedRanges = findStatisticsRanges(body);
        String token = "\"usage\"";

        search:
        for (int i = body.length() - token.length(); i >= 0; i--) {
            if (isExcluded(i, excludedRanges)) {
                continue;
            }

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
     * Locates every {@code "statistics": { ... }} value in the buffer (there may be more than one
     * across concatenated SSE chunks) and returns their {@code [start, end)} byte ranges, {@code start}
     * being the value's opening {@code {} and {@code end} the index right after its matching {@code }}.
     * Bounded to the size of the statistics blocks themselves, not the whole body.
     */
    private List<int[]> findStatisticsRanges(Buffer body) {
        String token = "\"statistics\"";
        List<int[]> ranges = new ArrayList<>();

        search:
        for (int i = 0; i <= body.length() - token.length(); i++) {
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

            int valueStart = -1;
            for (; j < body.length(); j++) {
                byte b = body.getByte(j);
                if (b == '{') {
                    valueStart = j;
                    break;
                }

                if (!isWhiteSpace(b)) {
                    continue search;
                }
            }
            if (valueStart < 0) {
                continue;
            }

            int valueEnd = findMatchingBrace(body, valueStart);
            if (valueEnd > valueStart) {
                ranges.add(new int[] {valueStart, valueEnd});
            }
        }

        return ranges;
    }

    /**
     * Forward brace-matching scan from an opening {@code {}, string-literal aware (braces inside a
     * JSON string, e.g. a model name, don't affect the depth count). Returns the index right after
     * the matching closing {@code }}, or -1 if the buffer ends before it's found (malformed/truncated
     * JSON - callers treat that as "no exclusion" rather than guessing a boundary).
     */
    private int findMatchingBrace(Buffer body, int openBraceIndex) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openBraceIndex; i < body.length(); i++) {
            byte b = body.getByte(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    inString = false;
                }
                continue;
            }

            if (b == '"') {
                inString = true;
            } else if (b == '{') {
                depth++;
            } else if (b == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }

        return -1;
    }

    private boolean isExcluded(int index, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (index >= range[0] && index < range[1]) {
                return true;
            }
        }
        return false;
    }

    private boolean isWhiteSpace(byte b) {
        return switch (b) {
            case ' ', '\n', '\t', '\r' -> true;
            default -> false;
        };
    }

}