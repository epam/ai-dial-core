package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The deployment type an {@link Alias} is listed and called as.
 */
public enum AliasPresentation {
    MODEL("model"),
    APPLICATION("application"),
    TOOLSET("toolset");

    private final String value;

    AliasPresentation(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AliasPresentation of(String value) {
        for (AliasPresentation presentation : values()) {
            if (presentation.value.equalsIgnoreCase(value)) {
                return presentation;
            }
        }
        throw new IllegalArgumentException("Unknown presentAs: " + value);
    }
}
