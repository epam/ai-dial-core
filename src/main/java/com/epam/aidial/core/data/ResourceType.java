package com.epam.aidial.core.data;

import lombok.Getter;

@Getter
public enum ResourceType {
    FILE("files");

    private final String resourceGroup;

    ResourceType(String resourceGroup) {
        this.resourceGroup = resourceGroup;
    }

}
