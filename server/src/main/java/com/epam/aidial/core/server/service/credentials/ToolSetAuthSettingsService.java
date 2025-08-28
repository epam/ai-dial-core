package com.epam.aidial.core.server.service.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolSetAuthStatus;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import com.epam.aidial.core.server.data.toolset.registration.ToolSetRegistration;
import com.epam.aidial.core.server.validation.ToolSetAuthSettingsValidator;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class ToolSetAuthSettingsService {

    private final ToolSetRegistrationService toolSetRegistrationService;
    private final ToolSetAuthSettingsValidator toolSetAuthSettingsValidator;
    private final ToolSetCredentialsManager toolSetCredentialsManager;

    public void initToolsetAuthSettings(ToolSet toolSet) {
        try {
            ToolSetAuthSettings toolSetAuthSettings = toolSet.getAuthSettings();
            if (toolSetAuthSettings == null) {
                throw new IllegalArgumentException("ToolSetAuthSettings is not defined for ToolSet: " + toolSet.getName());
            }

            toolSetAuthSettingsValidator.validate(toolSetAuthSettings);

            if (toolSetAuthSettings.getAuthenticationType() != AuthenticationType.OAUTH) {
                // do nothing
                return;
            }

            ToolSetRegistration toolSetRegistration = shouldRegisterToolsetDynamically(toolSetAuthSettings)
                                                      ? toolSetRegistrationService.createDynamicToolSetRegistration(toolSet)
                                                      : toolSetRegistrationService.createStaticToolSetRegistration(toolSet);

            CodeVerifier codeVerifier = new CodeVerifier();
            CodeChallengeMethod codeChallengeMethod = CodeChallengeMethod.parse(toolSetRegistration.getCodeChallengeMethod());
            CodeChallenge codeChallenge = CodeChallenge.compute(codeChallengeMethod, codeVerifier);

            ToolSetAuthSettings updatedToolSetAuthSettings = ToolSetAuthSettings.builder()
                    .authenticationType(AuthenticationType.OAUTH)
                    .clientId(toolSetRegistration.getClientId())
                    .clientSecret(toolSetRegistration.getClientSecret())
                    .authorizationEndpoint(toolSetRegistration.getAuthorizationEndpoint())
                    .tokenEndpoint(toolSetRegistration.getTokenEndpoint())
                    .redirectUri(toolSetRegistration.getRedirectUri())
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

    private boolean shouldRegisterToolsetDynamically(ToolSetAuthSettings toolSetAuthSettings) {
        return AuthenticationType.OAUTH.equals(toolSetAuthSettings.getAuthenticationType())
            && toolSetAuthSettings.getClientId() == null
            && toolSetAuthSettings.getClientSecret() == null;
    }

    public void setToolSetAuthStatuses(ToolSet toolSet) {
        List<ToolSetCredentials> allToolSetCredentials = toolSetCredentialsManager.getAllToolSetCredentials(toolSet.getName());
        setUserAuthStatus(toolSet, allToolSetCredentials);
        setGlobalAuthStatus(toolSet, allToolSetCredentials);
    }

    private void setUserAuthStatus(ToolSet toolSet,
                                   List<ToolSetCredentials> allToolSetCredentials) {
        Optional<ToolSetCredentials> userToolSetCredentials = allToolSetCredentials.stream()
                .filter(toolSetCredentials -> toolSetCredentials.getCredentialsLevel().equals(CredentialsLevel.USER))
                .findFirst();
        if (userToolSetCredentials.isPresent() && !userToolSetCredentials.get().isTokenExpired()) {
            toolSet.getAuthSettings().setUserLevelAuthStatus(ToolSetAuthStatus.SIGNED_IN);
        } else {
            toolSet.getAuthSettings().setUserLevelAuthStatus(ToolSetAuthStatus.SIGNED_OUT);
        }
    }

    private void setGlobalAuthStatus(ToolSet toolSet,
                                     List<ToolSetCredentials> allToolSetCredentials) {
        Optional<ToolSetCredentials> globalToolSetCredentials = allToolSetCredentials.stream()
                .filter(toolSetCredentials -> toolSetCredentials.getCredentialsLevel().equals(CredentialsLevel.GLOBAL))
                .findFirst();
        if (globalToolSetCredentials.isPresent() && !globalToolSetCredentials.get().isTokenExpired()) {
            toolSet.getAuthSettings().setGlobalAuthStatus(ToolSetAuthStatus.SIGNED_IN);
        } else {
            toolSet.getAuthSettings().setGlobalAuthStatus(ToolSetAuthStatus.SIGNED_OUT);
        }
    }
}
