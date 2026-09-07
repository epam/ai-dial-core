package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything that is allowed to differ between the two runs, stated in one place: wall-clock stamps, randomly
 * generated identifiers, and two headers.
 *
 * <p>The list is deliberately short, and each entry says why it is there. A normaliser that quietly ignores
 * fields is worse than no comparison at all — the whole instrument is only worth what this list is.
 */
@UtilityClass
public class ResponseNormalizer {

    /**
     * Headers dropped before comparison:
     * <ul>
     *   <li>{@code date} — wall-clock, one per run.</li>
     *   <li>{@code content-length} — a function of the body, which is compared directly; keeping it would
     *       report every body difference twice.</li>
     * </ul>
     */
    private static final Set<String> VOLATILE_HEADERS = Set.of("date", "content-length");

    /**
     * Fields stamped from the wall clock at write time. {@code AiDial.setClock} does not reach them —
     * {@code ResourceService} calls {@link System#currentTimeMillis()} directly — so they differ between any
     * two runs, of the same layout as much as of different ones.
     */
    private static final Set<String> WALL_CLOCK_FIELDS = Set.of("createdAt", "updatedAt", "expireAt");

    private static final String ELIDED = "<elided>";

    /**
     * Values short enough that replacing them everywhere they appear would corrupt unrelated text.
     */
    private static final int MIN_SUBSTITUTABLE_LENGTH = 8;

    /**
     * @param variables what the scenario captured, by name. Their values are randomly generated — an
     *                  invitation id is not derived from anything the two runs share — so they are replaced by
     *                  their own name. That compares them by the role they play rather than by a value that
     *                  was never going to match.
     */
    public static RecordedResponse normalize(RecordedResponse response, Map<String, String> variables) {
        String body = detokenize(canonicalBody(response.body()), variables);
        return new RecordedResponse(response.status(), body, stableHeaders(response.headers(), variables));
    }

    private static String detokenize(String text, Map<String, String> variables) {
        if (text == null) {
            return null;
        }

        String detokenized = text;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            if (variable.getValue().length() >= MIN_SUBSTITUTABLE_LENGTH) {
                detokenized = detokenized.replace(variable.getValue(), "${" + variable.getKey() + "}");
            }
        }
        return detokenized;
    }

    private static Map<String, String> stableHeaders(Map<String, String> headers, Map<String, String> variables) {
        Map<String, String> stable = new TreeMap<>();
        headers.forEach((name, value) -> {
            if (!VOLATILE_HEADERS.contains(name)) {
                stable.put(name, detokenize(value, variables));
            }
        });
        return stable;
    }

    /**
     * JSON bodies are re-serialised with object keys sorted. Field order carries no meaning and some responses
     * are built from hash-ordered maps, so raw text comparison would flag ordering as a divergence. Array order
     * is left alone — listing order is observable behaviour and a difference there is a real finding.
     */
    private static String canonicalBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }

        try {
            return ProxyUtil.MAPPER.writeValueAsString(sortKeys(ProxyUtil.MAPPER.readTree(body)));
        } catch (Exception e) {
            return body;
        }
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);

            ObjectNode sorted = ProxyUtil.MAPPER.createObjectNode();
            names.forEach(name -> sorted.set(name, WALL_CLOCK_FIELDS.contains(name)
                    ? TextNode.valueOf(ELIDED)
                    : sortKeys(node.get(name))));
            return sorted;
        }

        if (node.isArray()) {
            ArrayNode sorted = ProxyUtil.MAPPER.createArrayNode();
            node.forEach(element -> sorted.add(sortKeys(element)));
            return sorted;
        }

        return node;
    }
}
