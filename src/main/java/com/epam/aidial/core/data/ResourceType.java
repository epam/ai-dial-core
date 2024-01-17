package com.epam.aidial.core.data;

import lombok.Getter;

@Getter
public enum ResourceType {
    FILE("files");

    private final String group;

    ResourceType(String group) {
        this.group = group;
    }

}
