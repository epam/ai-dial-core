package com.epam.aidial.core.openapi.annotations;

public enum ParameterIn {
    QUERY,
    HEADER,
    PATH,
    COOKIE;

    public String openApiValue() {
        return name().toLowerCase();
    }
}
