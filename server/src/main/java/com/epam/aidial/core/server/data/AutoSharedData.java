package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAccessType;

import java.util.Set;

public record AutoSharedData(Set<ResourceAccessType> accessTypes) {
}
