package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A selection of the fields of a JSON response, read from a query parameter such as
 * {@code display_name,features(rate,tokenize),interface_configs(*(features))}.
 *
 * <p>A name keeps the whole value of that member. A name followed by a parenthesised selection applies it to the
 * value: to the members of an object and to each object element of an array. A value with no members is left as it
 * is. The name {@code *} matches every member at its level. A name no member has selects nothing.
 *
 * <p>The caller names the top-level members that are kept whether selected or not. A selection caches what it resolves
 * while projecting, so one instance is not for concurrent use.
 */
public final class FieldSelection {

    static final int MAX_LENGTH = 1024;
    static final int MAX_DEPTH = 5;

    private static final String WILDCARD = "*";
    private static final Node WHOLE = new Node(true);

    private final Node root;
    private final Set<String> alwaysKept;

    private FieldSelection(Node root, Set<String> alwaysKept) {
        this.root = root;
        this.alwaysKept = alwaysKept;
    }

    /**
     * Returns null when the text is null or blank, which means no selection.
     *
     * @param alwaysKept the names of the top-level members that {@link #project} keeps when they are not selected
     *
     * @throws IllegalArgumentException when the text is longer or nested deeper than the limits or is not a selection
     */
    @Nullable
    public static FieldSelection parse(@Nullable String text, Set<String> alwaysKept) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid fields parameter: longer than " + MAX_LENGTH + " characters");
        }
        return new FieldSelection(new Parser(text).parse(), Set.copyOf(alwaysKept));
    }

    /**
     * Keeps the selected members of the row, and the always-kept ones whether selected or not.
     * The result shares the kept values with the row.
     */
    public ObjectNode project(ObjectNode row) {
        return pruneObject(root, row, true);
    }

    private JsonNode prune(Node selection, JsonNode value) {
        if (selection.whole) {
            return value;
        }
        if (value instanceof ObjectNode object) {
            return pruneObject(selection, object, false);
        }
        if (value instanceof ArrayNode array) {
            ArrayNode pruned = array.arrayNode();
            for (JsonNode element : array) {
                pruned.add(element instanceof ObjectNode object ? pruneObject(selection, object, false) : element);
            }
            return pruned;
        }
        return value;
    }

    private ObjectNode pruneObject(Node selection, ObjectNode object, boolean topLevel) {
        ObjectNode pruned = object.objectNode();
        for (Map.Entry<String, JsonNode> member : object.properties()) {
            String name = member.getKey();
            Node memberSelection = selection.select(name);
            if (memberSelection != null) {
                pruned.set(name, prune(memberSelection, member.getValue()));
            } else if (topLevel && alwaysKept.contains(name)) {
                pruned.set(name, member.getValue());
            }
        }
        return pruned;
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

    private static final class Node {
        private final boolean whole;
        private final Map<String, Node> members = new LinkedHashMap<>();
        private final Map<String, Node> unions = new HashMap<>();

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
