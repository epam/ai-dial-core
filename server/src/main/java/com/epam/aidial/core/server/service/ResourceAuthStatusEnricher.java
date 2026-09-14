package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceAuthStatus;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.CredentialsDescriptorFactory;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Fills in the per-caller auth statuses of an application's external services or a toolset for one
 * response.
 *
 * <p>A DIAL-native service has no per-service credential, so its user level is answered from the caller's
 * platform-wide offline credentials instead of its (always empty) USER-level records.
 *
 * <p>Memoizes that lookup, so create one per response rather than per entity.
 */
@Slf4j
public class ResourceAuthStatusEnricher {

    private final ProxyContext context;
    private final ResourceAuthSettingsService resourceAuthSettingsService;
    private Boolean offlineCredentials;

    public ResourceAuthStatusEnricher(ProxyContext context, ResourceAuthSettingsService resourceAuthSettingsService) {
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

    /**
     * Enriches every external service of one application. One failing service is logged and skipped
     * so it cannot fail the whole response.
     */
    public void enrichApplication(String appId, Map<String, ExternalService> services) {
        if (services == null || services.isEmpty() || appId == null) {
            return;
        }
        for (Map.Entry<String, ExternalService> entry : services.entrySet()) {
            ResourceAuthSettings authSettings = entry.getValue() == null ? null : entry.getValue().getAuthSettings();
            if (authSettings == null) {
                continue;
            }
            try {
                enrich(CredentialsLocatorFactory.fromExternalService(appId, entry.getKey(), context), authSettings);
            } catch (RuntimeException e) {
                log.warn("Failed to compute external-service status for '{}' on '{}'", entry.getKey(), appId, e);
            }
        }
    }

    /**
     * Sets the auth statuses of one toolset. {@code toolSetId} is the decoded toolset id — a bare name
     * for config-sourced/platform toolsets, or the full decoded {@code toolsets/{bucket}/{path}} url
     * for dynamic ones.
     */
    public void enrichToolSet(String toolSetId, ToolSet toolSet) {
        if (toolSet == null || toolSetId == null || toolSet.getAuthSettings() == null) {
            return;
        }
        try {
            CredentialsLocator locator = CredentialsLocatorFactory.fromAnyUrl(
                    UrlUtil.encodePath(toolSetId), context, ResourceTypes.TOOL_SET);
            resourceAuthSettingsService.setResourceAuthStatuses(locator, toolSet.getAuthSettings(), context.getInitiatorId());
        } catch (RuntimeException e) {
            log.warn("Failed to compute auth statuses for toolset '{}'", toolSetId, e);
        }
    }

    private boolean hasOfflineCredentials() {
        if (offlineCredentials == null) {
            // A userless caller (an API key) is not a person, so it holds no offline credentials by definition.
            offlineCredentials = context.getUserId() != null && resourceAuthSettingsService.hasUnexpiredCredentials(
                    CredentialsDescriptorFactory.offlineCredentials(context));
        }
        return offlineCredentials;
    }
}
