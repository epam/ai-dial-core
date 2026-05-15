package com.epam.aidial.cli;

import com.fasterxml.jackson.annotation.JsonValue;

enum OutputFormat {
    JSON, YAML, TABLE;

    @JsonValue
    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
