package com.epam.aidial.core.server.openapi;

public enum ParameterIn {
    QUERY,
    HEADER,
    PATH,
    COOKIE;

    public String openApiValue() {
        return name().toLowerCase();
    }
}