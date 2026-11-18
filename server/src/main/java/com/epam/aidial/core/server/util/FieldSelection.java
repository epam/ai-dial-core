package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * A selection of response fields read from text such as {@code display_name,features(rate,tokenize),interface_configs(*(features))}:
 * a name keeps that member whole, {@code name(...)} selects inside it, and {@code *} matches every member at its level.
 */
public final class FieldSelection {

    static final int MAX_LENGTH = 1024;
    static final int MAX_DEPTH = 5;

    private static final String FILTER_ID = "fieldSelection";
    private static final String WILDCARD = "*";
    private static final Node WHOLE = new Node(true);
    private static final ObjectMapper FILTERING_MAPPER = ProxyUtil.MAPPER.copy().addMixIn(Object.class, Filtered.class);

    private final Node root;
    private final Set<String> alwaysKept;

    private FieldSelection(Node root, Set<String> alwaysKept) {
        this.root = root;
        this.alwaysKept = alwaysKept;
    }

    /**
     * Returns null for null or blank text. The length limit is in UTF-8 bytes.
     *
     * @param alwaysKept the top-level members that stay when not selected
     * @throws IllegalArgumentException when the text is not a selection or is over the length or depth limit
     */
    @Nullable
    public static FieldSelection parse(@Nullable String text, Set<String> alwaysKept) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid fields parameter: longer than " + MAX_LENGTH + " bytes");
        }
        return new FieldSelection(new Parser(text).parse(), Set.copyOf(alwaysKept));
    }

    /**
     * Members written by a custom serializer, such as a localized value, are not narrowed.
     */
    @SneakyThrows
    public String serialize(Object value) {
        return FILTERING_MAPPER.writer(new SimpleFilterProvider().addFilter(FILTER_ID, new MemberFilter())).writeValueAsString(value);
    }

    private boolean selects(JsonStreamContext object, String name) {
        Node node = nodeOf(object);
        if (node == null) {
            return false;
        }
        if (node.whole || node.select(name) != null) {
            return true;
        }
        return enclosingObject(object.getParent()) == null && alwaysKept.contains(name);
    }

    @Nullable
    private Node nodeOf(JsonStreamContext object) {
        JsonStreamContext outer = enclosingObject(object.getParent());
        if (outer == null) {
            return root;
        }
        Node outerNode = nodeOf(outer);
        if (outerNode == null) {
            return null;
        }
        return outerNode.whole ? WHOLE : outerNode.select(outer.getCurrentName());
    }

    @Nullable
    private static JsonStreamContext enclosingObject(@Nullable JsonStreamContext context) {
        while (context != null && !context.inObject()) {
            context = context.getParent();
        }
        return context;
    }

    private static Node union(Node first, Node second) {
        if (first.whole || second.whole) {
            return WHOLE;
        }
        Node union = new Node(false);
        union.members.putAll(first.members);
        second.members.forEach((name, member) -> union.members.merge(name, member, FieldSelection::union));
        return union;
    }

    @JsonFilter(FILTER_ID)
    private abstract static class Filtered {
    }

    private final class MemberFilter extends SimpleBeanPropertyFilter {
        @Override
        public void serializeAsField(Object pojo, JsonGenerator generator, SerializerProvider provider, PropertyWriter writer)
                throws Exception {
            if (selects(generator.getOutputContext(), writer.getName())) {
                writer.serializeAsField(pojo, generator, provider);
            } else if (!generator.canOmitFields()) {
                writer.serializeAsOmittedField(pojo, generator, provider);
            }
        }
    }

    private static final class Node {
        private final boolean whole;
        private final Map<String, Node> members = new LinkedHashMap<>();
        private final Map<String, Node> unions = new ConcurrentHashMap<>();

        private Node(boolean whole) {
            this.whole = whole;
        }

        @Nullable
        private Node select(String name) {
            Node named = members.get(name);
            Node any = members.get(WILDCARD);
            if (named == null) {
                return any;
            }
            return any == null ? named : unions.computeIfAbsent(name, key -> union(named, any));
        }
    }

    private static final class Parser {
        private final String text;
        private int position;

        private Parser(String text) {
            this.text = text;
        }

        private Node parse() {
            Node root = parseSelection(1);
            if (position < text.length()) {
                throw error("unexpected '" + text.charAt(position) + "'");
            }
            return root;
        }

        private Node parseSelection(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested deeper than " + MAX_DEPTH + " levels");
            }
            Node selection = new Node(false);
            do {
                String name = readName();
                if (name.isEmpty()) {
                    throw error("expected a field name");
                }
                Node member = WHOLE;
                if (consume('(')) {
                    member = parseSelection(depth + 1);
                    if (!consume(')')) {
                        throw error(position < text.length() ? "unexpected '" + text.charAt(position) + "'" : "missing ')'");
                    }
                    skipWhitespace();
                }
                selection.members.merge(name, member, FieldSelection::union);
            } while (consume(','));
            return selection;
        }

        private String readName() {
            int start = position;
            while (position < text.length() && ",()".indexOf(text.charAt(position)) < 0) {
                position++;
            }
            return text.substring(start, position).strip();
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private boolean consume(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private IllegalArgumentException error(String reason) {
            return new IllegalArgumentException("Invalid fields parameter: " + reason + " at position " + (position + 1));
        }
    }
}
