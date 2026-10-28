package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.regex.Pattern;

public record UpstreamAuthConfig(UpstreamAuthType type, String headerName) {
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
    private static final ObjectMapper ENUM_MAPPER = JsonMapper.builder().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();
    public static final UpstreamAuthConfig API_KEY = new UpstreamAuthConfig(UpstreamAuthType.API_KEY, null);

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UpstreamAuthConfig fromString(String type) {
        // reuse Jackson enum parsing with the same feature as the application mappers, so the string and object forms agree
        return new UpstreamAuthConfig(ENUM_MAPPER.convertValue(type, UpstreamAuthType.class), null);
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
