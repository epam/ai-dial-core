package com.epam.aidial.core.server.data;

import com.epam.aidial.core.resourceservice.data.ResourceAccessType;

import java.util.Set;

public record AutoSharedData(Set<ResourceAccessType> accessTypes) {
}
