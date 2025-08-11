package com.epam.aidial.core.server.service.toolset.registration;

import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolsetAuthenticationType;
import com.epam.aidial.core.server.data.toolset.registration.ToolsetRegistration;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ToolsetAuthSettingsService {

    private final ToolsetRegistrationService toolsetRegistrationService;

    public ToolSet updateToolsetAuthSettings(ToolSet toolSet) {
        ToolSetAuthSettings toolsetAuthSettings = toolSet.getToolsetAuthSettings();
        if (toolsetAuthSettings == null) {
            throw new IllegalArgumentException("ToolsetAuthSettings is not defined for Toolset: " + toolSet.getName());
        }

        if (shouldRegisterToolset(toolsetAuthSettings)) {
            ToolsetRegistration toolsetRegistration = toolsetRegistrationService.registerToolset(toolSet);
            ToolSetAuthSettings updatedToolSetAuthSettings = ToolSetAuthSettings.builder()
                .toolsetAuthenticationType(ToolsetAuthenticationType.OAUTH)
                .clientId(toolsetRegistration.getClientId())
                .clientSecret(toolsetRegistration.getClientSecret())
                .scope(toolsetRegistration.getScope())
                .authorizationEndpoint(toolsetRegistration.getAuthorizationEndpoint())
                .tokenEndpoint(toolsetRegistration.getTokenEndpoint())
                .redirectUri(toolsetRegistration.getRedirectUri())
                .build();

            toolSet.setToolsetAuthSettings(updatedToolSetAuthSettings);
        }

        return toolSet;
    }

    private boolean shouldRegisterToolset(ToolSetAuthSettings toolsetAuthSettings) {
        return ToolsetAuthenticationType.OAUTH.equals(toolsetAuthSettings.getToolsetAuthenticationType())
            && (toolsetAuthSettings.getClientId() == null
            || toolsetAuthSettings.getClientSecret() == null
            || toolsetAuthSettings.getScope() == null
            || toolsetAuthSettings.getAuthorizationEndpoint() == null
            || toolsetAuthSettings.getTokenEndpoint() == null
            || toolsetAuthSettings.getRedirectUri() == null
        );
    }
}
