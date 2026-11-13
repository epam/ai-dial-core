package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;

import javax.annotation.Nullable;

/**
 * A reference of one resource to another. An edge never holds the raw value. A {@code null} status marks a candidate for inclusion;
 * the traversal still has to authorize and read it.
 *
 * @param pointer RFC 6901 pointer into the body of the resource that holds the reference
 * @param status  why the reference is not followed, or {@code null} for a candidate
 * @param target  the resource that the reference ends in, when it was identified
 */
public record DiscoveredEdge(String pointer, @Nullable EdgeStatus status, @Nullable ResourceDescriptor target) {

    /**
     * What discovery and traversal found out about an edge; the export logs every outcome but the candidate.
     */
    public enum EdgeStatus {
        MISSING, UNSUPPORTED, EXTERNAL, UNRESOLVED_SHORT_NAME, ORIGIN_UNKNOWN, SCHEMA_UNAVAILABLE
    }
}
