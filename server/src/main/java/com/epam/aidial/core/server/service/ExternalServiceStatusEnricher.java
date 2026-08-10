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
 * <p>DIAL-native services have no per-service credential: the user grants offline access once, platform-wide, and an
 * administrator approves the application separately. So the user level is answered from the caller's offline
 * credentials rather than from this service's (always empty) USER-level records, and the application level keeps its
 * ordinary meaning — an APPLICATION-level record exists, which for this type is the admin's approval.
 *
 * <p>The offline lookup is read at most once per instance, since every DIAL-native service of an application resolves
 * to the same record. Create one per response, not per service.
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
        resourceAuthSettingsService.setExternalServiceAuthStatuses(credentialsLocator, authSettings, context.getUserId());
        if (authSettings.getAuthenticationType() == AuthenticationType.DIAL_NATIVE) {
            authSettings.setUserLevelAuthStatus(hasOfflineCredentials()
                    ? ResourceAuthStatus.SIGNED_IN : ResourceAuthStatus.SIGNED_OUT);
        }
    }

    private boolean hasOfflineCredentials() {
        if (offlineCredentials == null) {
            // Without a user there is no bucket to look in; report not-connected rather than failing the listing.
            offlineCredentials = context.getUserId() != null
                    && resourceAuthSettingsService.hasUnexpiredCredentials(CredentialsDescriptorFactory.offlineCredentials(context));
        }
        return offlineCredentials;
    }
}
