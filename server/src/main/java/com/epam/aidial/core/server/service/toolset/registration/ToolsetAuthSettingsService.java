package com.epam.aidial.core.server.service.toolset.registration;

import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.server.data.toolset.registration.ToolsetRegistration;
import com.epam.aidial.core.server.validation.ToolSetAuthSettingsValidator;

import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ToolsetAuthSettingsService {

    private final ToolsetRegistrationService toolsetRegistrationService;
    private final ToolSetAuthSettingsValidator toolSetAuthSettingsValidator;

    public void updateToolsetAuthSettings(ToolSet toolSet) {
        try {
            ToolSetAuthSettings toolsetAuthSettings = toolSet.getAuthSettings();
            if (toolsetAuthSettings == null) {
                throw new IllegalArgumentException("ToolSetAuthSettings is not defined for ToolSet: " + toolSet.getName());
            }

            toolSetAuthSettingsValidator.validate(toolsetAuthSettings);

            ToolsetRegistration toolsetRegistration = shouldRegisterToolsetDynamically(toolsetAuthSettings)
                                                      ? toolsetRegistrationService.createDynamicToolSetRegistration(toolSet)
                                                      : toolsetRegistrationService.createStaticToolSetRegistration(toolSet);


            CodeVerifier codeVerifier = new CodeVerifier();
            CodeChallengeMethod codeChallengeMethod = CodeChallengeMethod.parse(toolsetRegistration.getCodeChallengeMethod());
            CodeChallenge codeChallenge = CodeChallenge.compute(codeChallengeMethod, codeVerifier);

            ToolSetAuthSettings updatedToolSetAuthSettings = ToolSetAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId(toolsetRegistration.getClientId())
                .clientSecret(toolsetRegistration.getClientSecret())
                .authorizationEndpoint(toolsetRegistration.getAuthorizationEndpoint())
                .tokenEndpoint(toolsetRegistration.getTokenEndpoint())
                .redirectUri(toolsetRegistration.getRedirectUri())
                .codeChallenge(codeChallenge.getValue())
                .codeVerifier(codeVerifier.getValue())
                .codeChallengeMethod(codeChallengeMethod.getValue())
                .build();

            toolSet.setAuthSettings(updatedToolSetAuthSettings);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(String.format("Can't register ToolSet: %s client.", toolSet.getName()));
        }
    }

    private boolean shouldRegisterToolsetDynamically(ToolSetAuthSettings toolsetAuthSettings) {
        return AuthenticationType.OAUTH.equals(toolsetAuthSettings.getAuthenticationType())
            && toolsetAuthSettings.getClientId() == null
            && toolsetAuthSettings.getClientSecret() == null;
    }
}
