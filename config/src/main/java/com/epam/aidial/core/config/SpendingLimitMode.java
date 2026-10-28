package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum SpendingLimitMode {
    OFF,
    SHADOW,
    ENFORCE;

    @JsonValue
    public String toValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    static SpendingLimitMode fromValue(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be one of off, shadow, enforce");
        }
    }
}
