package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceAuthStatus;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.CredentialsDescriptorFactory;

/**
 * Fills in the auth statuses of an application's external services for one response.
 *
 * <p>A DIAL-native service has no per-service credential, so its user level is answered from the caller's
 * platform-wide offline credentials instead of its (always empty) USER-level records.
 *
 * <p>Memoizes that lookup, so create one per response rather than per service.
 */
public class ExternalServiceStatusEnricher {

    private final ProxyContext context;
    private final ResourceAuthSettingsService resourceAuthSettingsService;
    private Boolean offlineCredentials;

    public ExternalServiceStatusEnricher(ProxyContext context, ResourceAuthSettingsService resourceAuthSettingsService) {
        this.context = context;
        this.resourceAuthSettingsService = resourceAuthSettingsService;
    }

    public void enrich(CredentialsLocator credentialsLocator, ResourceAuthSettings authSettings) {
        if (context.getUserId() == null) {
            // A userless caller (an API key) has no bucket to look in and no user-level status to report. Leave the
            // settings as they are: matching user credentials by a null user id fails deeper, where it reads as a
            // server fault rather than an absent user.
            return;
        }
        resourceAuthSettingsService.setExternalServiceAuthStatuses(credentialsLocator, authSettings, context.getUserId());
        if (authSettings.getAuthenticationType() == AuthenticationType.DIAL_NATIVE) {
            authSettings.setUserLevelAuthStatus(hasOfflineCredentials()
                    ? ResourceAuthStatus.SIGNED_IN : ResourceAuthStatus.SIGNED_OUT);
        }
    }

    private boolean hasOfflineCredentials() {
        if (offlineCredentials == null) {
            offlineCredentials = resourceAuthSettingsService.hasUnexpiredCredentials(
                    CredentialsDescriptorFactory.offlineCredentials(context));
        }
        return offlineCredentials;
    }
}
