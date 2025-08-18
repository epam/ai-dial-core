package com.epam.aidial.core.server.service.toolset.registration;

import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.server.data.toolset.registration.ToolsetRegistration;
import com.epam.aidial.core.server.validation.ToolSetAuthSettingsValidator;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ToolsetAuthSettingsService {

    private final ToolsetRegistrationService toolsetRegistrationService;
    private final ToolSetAuthSettingsValidator toolSetAuthSettingsValidator;

    public void updateToolsetAuthSettings(ToolSet toolSet) {
        ToolSetAuthSettings toolsetAuthSettings = toolSet.getToolSetAuthSettings();
        if (toolsetAuthSettings == null) {
            throw new IllegalArgumentException("ToolSetAuthSettings is not defined for ToolSet: " + toolSet.getName());
        }

        toolSetAuthSettingsValidator.validate(toolsetAuthSettings);

        ToolsetRegistration toolsetRegistration = shouldRegisterToolsetDynamically(toolsetAuthSettings)
                                                  ? toolsetRegistrationService.createDynamicToolSetRegistration(toolSet)
                                                  : toolsetRegistrationService.createStaticToolSetRegistration(toolSet);

        ToolSetAuthSettings updatedToolSetAuthSettings = ToolSetAuthSettings.builder()
            .authenticationType(AuthenticationType.OAUTH)
            .clientId(toolsetRegistration.getClientId())
            .clientSecret(toolsetRegistration.getClientSecret())
            .authorizationEndpoint(toolsetRegistration.getAuthorizationEndpoint())
            .tokenEndpoint(toolsetRegistration.getTokenEndpoint())
            .redirectUri(toolsetRegistration.getRedirectUri())
            .build();

        toolSet.setToolSetAuthSettings(updatedToolSetAuthSettings);
    }

    private boolean shouldRegisterToolsetDynamically(ToolSetAuthSettings toolsetAuthSettings) {
        return AuthenticationType.OAUTH.equals(toolsetAuthSettings.getAuthenticationType())
            && toolsetAuthSettings.getClientId() == null
            && toolsetAuthSettings.getClientSecret() == null;
    }
}
