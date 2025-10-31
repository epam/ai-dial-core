package com.epam.aidial.core.server.data.permission;

import com.epam.aidial.core.config.ResourceAccessType;

import java.util.Set;

public record PerRequestSharedData(Set<ResourceAccessType> permissions) {
}
