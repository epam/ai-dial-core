package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.regex.Pattern;

public record UpstreamAuthConfig(UpstreamAuthType type, String headerName) {
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
    public static final UpstreamAuthConfig API_KEY = new UpstreamAuthConfig(UpstreamAuthType.API_KEY, null);

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UpstreamAuthConfig fromString(String type) {
        return new UpstreamAuthConfig(UpstreamAuthType.valueOf(type), null);
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public UpstreamAuthConfig(
            @JsonProperty(value = "type", required = true)
            UpstreamAuthType type,
            @JsonProperty("headerName")
            String headerName
    ) {
        if (type == null) {
            throw new IllegalArgumentException("Authentication type is required");
        }

        if (type == UpstreamAuthType.CUSTOM_HEADER) {
            if (headerName == null || !HEADER_NAME_PATTERN.matcher(headerName).matches()) {
                throw new IllegalArgumentException(UpstreamAuthType.CUSTOM_HEADER.name() + " requires a valid HTTP header name");
            }
        } else if (headerName != null) {
            throw new IllegalArgumentException("headerName is only supported for " + UpstreamAuthType.CUSTOM_HEADER.name());
        }

        this.type = type;
        this.headerName = headerName;
    }
}
