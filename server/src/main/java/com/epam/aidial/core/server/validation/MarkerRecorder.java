package com.epam.aidial.core.server.validation;

import com.epam.aidial.core.server.validation.MarkerCollection.MarkerReference;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.JsonNodePath;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Opt-in record of the marker keywords' evaluations. A caller that wants the location of every marked value registers an instance in
 * the {@link CollectorContext} under {@link #KEY} before it validates; the keywords write to it only then, so request-time collection
 * neither allocates nor behaves differently.
 *
 * <p>The same pointer and value visited more than once, as when several {@code oneOf} branches evaluate one location, is recorded
 * once. The same value at two pointers is two references.
 */
public final class MarkerRecorder {

    public static final String KEY = "marker_references";

    private final Set<MarkerReference> references = new LinkedHashSet<>();

    /** Records a marked value if a recorder is registered in the context; otherwise does nothing. */
    public static void record(CollectorContext context, JsonNodePath location, String value) {
        Object recorder = context.getCollectorMap().get(KEY);
        if (recorder instanceof MarkerRecorder markerRecorder) {
            markerRecorder.references.add(new MarkerReference(toPointer(location), value));
        }
    }

    public List<MarkerReference> listReferences() {
        return new ArrayList<>(references);
    }

    /**
     * Converts a library location to an RFC 6901 pointer: {@code ~} becomes {@code ~0} and {@code /} becomes {@code ~1}. The library's own
     * rendering ({@code $.a[0]}) is not a pointer. The root is the empty pointer.
     */
    public static String toPointer(JsonNodePath location) {
        StringBuilder pointer = new StringBuilder();
        for (int i = 0; i < location.getNameCount(); i++) {
            String segment = String.valueOf(location.getElement(i));
            pointer.append('/').append(segment.replace("~", "~0").replace("/", "~1"));
        }
        return pointer.toString();
    }
}
