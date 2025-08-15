package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolsetAuthenticationType;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ToolsetAuthSettingsData {

    private ToolsetAuthenticationType toolsetAuthenticationType;
    private String clientId;
    private String redirectUri;
    private String authorizationEndpoint;
    private String apiKeyHeader;

    @JsonIgnore
    public static ToolsetAuthSettingsData toData(ToolSetAuthSettings toolsetAuthSettings) {
        return ToolsetAuthSettingsData.builder()
            .toolsetAuthenticationType(toolsetAuthSettings.getToolsetAuthenticationType())
            .clientId(toolsetAuthSettings.getClientId())
            .authorizationEndpoint(toolsetAuthSettings.getAuthorizationEndpoint())
            .redirectUri(toolsetAuthSettings.getRedirectUri())
            .apiKeyHeader(toolsetAuthSettings.getApiKeyHeader())
            .build();
    }
}
