package com.epam.aidial.core.server.data.cache;

import lombok.experimental.UtilityClass;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Grammar for upstream-cache prefix paths. Two related forms share the same {@code prefix.body.<node>}
 * root:
 * <ul>
 *     <li><b>Node designator</b>, as used in {@code fieldsHashingOrder} config and the interfaces'
 *     built-in orders: {@code prefix.body.tools}.</li>
 *     <li><b>Concrete path</b>, as emitted by {@link com.epam.aidial.core.server.function.request.CacheKeyBuilder}
 *     and echoed back by adapters: {@code prefix.body.messages[1]} or {@code prefix.body.messages[1].content[2]}.</li>
 * </ul>
 * Today's forms ({@code prefix.body.tools}, {@code prefix.body.messages[3]}) remain valid verbatim —
 * this only adds the new node names and the optional {@code .content[j]} segment.
 */
@UtilityClass
public class CachePrefixPath {

    private static final Pattern NODE_DESIGNATOR =
            Pattern.compile("^prefix\\.body\\.(?<node>tools|messages|system|input|instructions)$");

    /**
     * Parses a node designator (e.g. {@code prefix.body.system}) and returns the node name, or
     * {@code null} if {@code designator} is not a valid node designator.
     */
    @Nullable
    public String parseNode(String designator) {
        Matcher matcher = NODE_DESIGNATOR.matcher(designator);
        return matcher.matches() ? matcher.group("node") : null;
    }

    /**
     * Formats the concrete path for an element of the given node, e.g. {@code node("messages", 2)} ->
     * {@code prefix.body.messages[2]}.
     */
    public String node(String node, int index) {
        return "prefix.body." + node + "[" + index + "]";
    }

    /**
     * Formats the concrete path for a content block nested under an element of the given node, e.g.
     * {@code contentBlock("messages", 2, 1)} -> {@code prefix.body.messages[2].content[1]}.
     */
    public String contentBlock(String node, int index, int contentIndex) {
        return node(node, index) + ".content[" + contentIndex + "]";
    }
}
