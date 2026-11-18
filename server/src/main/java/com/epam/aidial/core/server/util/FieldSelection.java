package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A selection of response fields read from text such as {@code display_name,features(rate,tokenize),routes(*(paths))}:
 * a name keeps that member whole, {@code name(...)} selects inside it, and {@code *} matches every member at its level.
 */
public final class FieldSelection {

    static final int MAX_LENGTH = 1024;
    static final int MAX_DEPTH = 5;

    private static final String WILDCARD = "*";
    private static final Node WHOLE = new Node(true);
    private static final List<Node> KEEP_ALL = List.of(WHOLE);

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
     * The result shares the kept values with the row.
     */
    public ObjectNode project(ObjectNode row) {
        return pruneObject(List.of(root), row, true);
    }

    private JsonNode prune(List<Node> selections, JsonNode value) {
        if (value instanceof ObjectNode object) {
            return pruneObject(selections, object, false);
        }
        if (value instanceof ArrayNode array) {
            ArrayNode pruned = array.arrayNode();
            for (JsonNode element : array) {
                pruned.add(element instanceof ObjectNode object ? pruneObject(selections, object, false) : element);
            }
            return pruned;
        }
        return value;
    }

    private ObjectNode pruneObject(List<Node> selections, ObjectNode object, boolean topLevel) {
        ObjectNode pruned = object.objectNode();
        for (Map.Entry<String, JsonNode> member : object.properties()) {
            String name = member.getKey();
            List<Node> memberSelections = select(selections, name);
            if (memberSelections == KEEP_ALL) {
                pruned.set(name, member.getValue());
            } else if (memberSelections != null) {
                pruned.set(name, prune(memberSelections, member.getValue()));
            } else if (topLevel && alwaysKept.contains(name)) {
                pruned.set(name, member.getValue());
            }
        }
        return pruned;
    }

    @Nullable
    private static List<Node> select(List<Node> selections, String name) {
        List<Node> matched = null;
        for (Node selection : selections) {
            Node named = selection.members.get(name);
            Node any = selection.members.get(WILDCARD);
            if (named != null) {
                if (named.whole) {
                    return KEEP_ALL;
                }
                matched = add(matched, named);
            }
            if (any != null && any != named) {
                if (any.whole) {
                    return KEEP_ALL;
                }
                matched = add(matched, any);
            }
        }
        return matched;
    }

    private static List<Node> add(@Nullable List<Node> nodes, Node node) {
        List<Node> result = nodes == null ? new ArrayList<>(2) : nodes;
        result.add(node);
        return result;
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

        private Node(boolean whole) {
            this.whole = whole;
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
            boolean hasMore;
            do {
                String name = readName();
                if (name.isEmpty()) {
                    throw error("expected a field name");
                }
                Node member = WHOLE;
                if (isNext('(')) {
                    position++;
                    member = parseSelection(depth + 1);
                    if (!isNext(')')) {
                        throw error(position < text.length() ? "unexpected '" + text.charAt(position) + "'" : "missing ')'");
                    }
                    position++;
                    skipWhitespace();
                }
                selection.members.merge(name, member, FieldSelection::union);
                hasMore = isNext(',');
                if (hasMore) {
                    position++;
                }
            } while (hasMore);
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

        private boolean isNext(char expected) {
            return position < text.length() && text.charAt(position) == expected;
        }

        private IllegalArgumentException error(String reason) {
            return new IllegalArgumentException("Invalid fields parameter: " + reason + " at position " + (position + 1));
        }
    }
}
