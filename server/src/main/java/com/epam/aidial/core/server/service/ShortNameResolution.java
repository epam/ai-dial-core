package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.List;
import javax.annotation.Nullable;

/**
 * The result of resolving one short name.
 *
 * @param outcome      what was found
 * @param type         the type of the effective entity, when there is one
 * @param name         the short name that was resolved
 * @param canonicalId  {@code <segment>/platform/<name>} for a {@link Origin#PLATFORM} entity; {@code null} otherwise
 * @param origin       where the effective entity came from; {@code null} when it is not established
 * @param hasShadowedEntity  the entity of the other source with the same type and name (a platform blob under an effective file entity, or a file
 *                           entity under an effective platform one) exists and is hidden by the effective one
 * @param skipped      entities of that name that were skipped, with the reason
 */
public record ShortNameResolution(Outcome outcome, @Nullable ResourceTypes type, String name,
                                  @Nullable String canonicalId, @Nullable Origin origin, boolean hasShadowedEntity,
                                  List<Skipped> skipped) {

    public enum Outcome {
        FOUND, SKIPPED, NOT_FOUND, ORIGIN_UNKNOWN
    }

    public enum Origin {
        FILE, PLATFORM
    }

    /**
     * A skipped entity.
     *
     * @param type        its type
     * @param canonicalId its canonical id, always {@code <segment>/platform/<name>}
     * @param source      where it came from, as the store records it ({@code api} or {@code file})
     * @param reason      why it was skipped
     */
    public record Skipped(ResourceTypes type, String canonicalId, String source, String reason) {
    }
}
