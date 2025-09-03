package com.epam.aidial.core.storage.data;

import com.epam.aidial.core.config.ResourceAccessType;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class SharedWithMetadata {
    /**
     * Display name or project name of the user with whom the resource is shared.
     */
    private String user;
    /**
     * A set of access types the user has for this resource.
     */
    private Set<ResourceAccessType> permissions;
}
