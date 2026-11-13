package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.resource.ResourceTypes;

import javax.annotation.Nullable;

/**
 * The result of resolving one short name.
 *
 * @param origin where the effective entity came from; {@code null} when it is not established
 */
public record ShortNameResolution(Outcome outcome, @Nullable ResourceTypes type, String name, @Nullable Origin origin) {

    public enum Outcome {
        FOUND, SKIPPED, NOT_FOUND, ORIGIN_UNKNOWN
    }

    public enum Origin {
        FILE, PLATFORM
    }
}
