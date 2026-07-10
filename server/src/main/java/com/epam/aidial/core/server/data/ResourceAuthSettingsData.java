package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceAuthStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ResourceAuthSettingsData {

    private AuthenticationType authenticationType;
    private String clientId;
    private String redirectUri;
    private String codeChallenge;
    private String codeChallengeMethod;
    private String authorizationEndpoint;
    private String apiKeyHeader;
    private ResourceAuthStatus globalAuthStatus;
    private ResourceAuthStatus appLevelAuthStatus;
    private ResourceAuthStatus userLevelAuthStatus;
    private List<String> scopesSupported;
    private Boolean dynamicallyRegistered;

    @JsonIgnore
    public static ResourceAuthSettingsData toData(ResourceAuthSettings toolsetAuthSettings) {
        return ResourceAuthSettingsData.builder()
            .authenticationType(toolsetAuthSettings.getAuthenticationType())
            .clientId(toolsetAuthSettings.getClientId())
            .authorizationEndpoint(toolsetAuthSettings.getAuthorizationEndpoint())
            .redirectUri(toolsetAuthSettings.getRedirectUri())
            .codeChallenge(toolsetAuthSettings.getCodeChallenge())
            .codeChallengeMethod(toolsetAuthSettings.getCodeChallengeMethod())
            .apiKeyHeader(toolsetAuthSettings.getApiKeyHeader())
            .globalAuthStatus(toolsetAuthSettings.getGlobalAuthStatus())
            .appLevelAuthStatus(toolsetAuthSettings.getAppLevelAuthStatus())
            .userLevelAuthStatus(toolsetAuthSettings.getUserLevelAuthStatus())
            .scopesSupported(toolsetAuthSettings.getScopesSupported())
            .dynamicallyRegistered(toolsetAuthSettings.getDynamicallyRegistered())
            .build();
    }
}
