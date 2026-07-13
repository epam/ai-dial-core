package com.epam.aidial.core.storage.resource;

import com.epam.aidial.core.openapi.annotations.ApiSubType;
import com.epam.aidial.core.openapi.annotations.ApiSubTypes;

@ApiSubTypes(
        discriminatorProperty = "type",
        value = @ApiSubType(type = ResourceTypes.class, discriminatorValue = "DEFAULT")
)
public interface ResourceType {
    String name();

    String group();

    default String urlSegment() {
        return group();
    }

    boolean requireCompression();

    /**
     * @return TTL in milliseconds.
     */
    long ttl();
}
