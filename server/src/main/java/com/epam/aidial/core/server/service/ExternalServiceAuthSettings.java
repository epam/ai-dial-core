package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.registration.RegistrationPolicy;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/** OAuth client setup and secret detection shared by the inline and the user-authored external-service writes. */
@RequiredArgsConstructor
public class ExternalServiceAuthSettings {

    private final ResourceAuthSettingsService resourceAuthSettingsService;

    /**
     * Runs the toolset client setup on {@code service}: validation for the change, registration or metadata lookup
     * when the service has a {@code discovery_url}, PKCE, and on an update the stored fields the body omits.
     * {@code existing} must be decrypted; when its secrets could not be, they are dropped and
     * {@code existingSecretUnreadable} is set, so an update that would have kept them is refused.
     */
    void setUp(String resourceId, String serviceId, ExternalService service, ExternalService existing,
               boolean existingSecretUnreadable) {
        ResourceAuthSettings existingSettings = existing == null ? null : existing.getAuthSettings();
        ResourceAuthSettings settings = service.getAuthSettings();
        // Internal re-writes (publication link rewrite) send back the verifier decrypted on read: that is the stored
        // value, not a new one, and the validators forbid a caller-supplied verifier.
        if (existingSettings != null && settings.getCodeVerifier() != null
                && settings.getCodeVerifier().equals(existingSettings.getCodeVerifier())) {
            settings.setCodeVerifier(null);
        }
        // A blank value clears the stored one on an OAUTH update; anywhere else there is nothing to clear.
        boolean oauthUpdate = settings.getAuthenticationType() == AuthenticationType.OAUTH
                && existingSettings != null && existingSettings.getAuthenticationType() == AuthenticationType.OAUTH;
        if (!oauthUpdate && StringUtils.isBlank(settings.getDiscoveryUrl())) {
            settings.setDiscoveryUrl(null);
        }
        try {
            resourceAuthSettingsService.processResourceAuthSettings(resourceId, StringUtils.trimToNull(settings.getDiscoveryUrl()),
                    settings, existingSettings, existingSecretUnreadable, RegistrationPolicy.EXTERNAL_SERVICE);
        } catch (Exception e) {
            // Every failure is the request's: the provider's own status (a 401, a 5xx) would read as the caller's, and
            // discovery sneaky-throws a refused connection or a connect timeout as checked exceptions.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External service '%s': %s".formatted(serviceId, e.getMessage()));
        }
    }

    // A public client has no secret but a code_verifier, which must be encrypted just the same.
    static boolean hasSecret(ExternalService service) {
        ResourceAuthSettings authSettings = service == null ? null : service.getAuthSettings();
        return authSettings != null && (authSettings.getClientSecret() != null || authSettings.getCodeVerifier() != null);
    }
}
