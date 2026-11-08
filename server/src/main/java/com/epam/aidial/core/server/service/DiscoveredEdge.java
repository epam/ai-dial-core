package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolution.Skipped;
import com.epam.aidial.core.server.service.ShortNameResolver.ReferenceForm;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.List;
import javax.annotation.Nullable;

/**
 * A reference of one resource to another, of any type. An edge never holds the raw value. A {@code null} status marks a
 * candidate for inclusion; the traversal still has to authorize and read it.
 *
 * @param pointer   RFC 6901 pointer into the body of the resource that holds the reference
 * @param valueKind how the value was classified; set when no target could be identified
 * @param skipped   entities of the referenced name that were skipped, with their reasons
 */
public record DiscoveredEdge(String pointer, @Nullable EdgeStatus status, @Nullable ReferenceForm valueKind, @Nullable EdgeTarget target,
                             List<Skipped> skipped) {

    /**
     * What discovery and traversal found out about an edge. These are internal outcomes, not the statuses of the registry: the registry
     * shows {@code unsupported}, {@code external} and {@code unresolved}, and {@code included} for an edge that ends in a resource of
     * the package. Every outcome but {@code UNSUPPORTED} and {@code EXTERNAL} is {@code unresolved} there. The traversal can also turn
     * a candidate into {@code MISSING}. A limit of {@link ExportLimits} fails the export and is not an outcome.
     */
    public enum EdgeStatus {
        MISSING, UNSUPPORTED, EXTERNAL, UNRESOLVED_SHORT_NAME, ORIGIN_UNKNOWN, SCHEMA_UNAVAILABLE
    }

    /**
     * The resource that an edge points at, of any type.
     *
     * @param descriptor set for a URL-form reference and for a platform entity found by short name
     * @param origin     set only when the target was found by short name
     */
    public record EdgeTarget(ResourceTypes type, String name, @Nullable ResourceDescriptor descriptor, @Nullable Origin origin,
                             boolean hasShadowedEntity) {
    }
}
