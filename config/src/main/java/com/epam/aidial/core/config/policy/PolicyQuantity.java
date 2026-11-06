package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.annotation.JsonValue;

public enum PolicyQuantity {
    COST("cost"),
    TOKENS("tokens"),
    REQUESTS("requests");

    private final String value;

    PolicyQuantity(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
