package com.epam.aidial.cli;

import com.fasterxml.jackson.annotation.JsonValue;

enum ConfigSource {
    /** API-managed entries — read from {@code /v1/{type}/{bucket}/{name}}. Default. */
    API,
    /** File-sourced entries — read from {@code /v1/admin/config/file/{type}[/{name}]}. */
    FILE;

    @JsonValue
    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
