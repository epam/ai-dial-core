package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PolicyKind {
    BUDGET("budget");

    private final String value;

    PolicyKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
