package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.storage.resource.ResourceTypes;

import javax.annotation.Nullable;

/**
 * The result of resolving one short name.
 *
 * @param origin where the effective entity came from; {@code null} when it is not established
 */
record ShortNameResolution(Outcome outcome, @Nullable ResourceTypes type, String name, @Nullable Origin origin) {

    enum Outcome {
        FOUND, SKIPPED, NOT_FOUND, ORIGIN_UNKNOWN
    }

    enum Origin {
        FILE, PLATFORM
    }
}
