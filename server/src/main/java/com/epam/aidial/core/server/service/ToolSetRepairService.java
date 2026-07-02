package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.credentials.service.TokenService;
import com.epam.aidial.core.credentials.service.registration.ResourceRegistrationService;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@AllArgsConstructor
public class ToolSetRepairService {

    private final ResourceService resourceService;
    private final ResourceAuthSettingsEncryptionService encryptionService;
    private final ResourceCredentialsService credentialsService;
    private final TokenService tokenService;
    private final ResourceRegistrationService registrationService;
    private final ResourceAuthSettingsService authSettingsService;

    public enum RepairOutcome {
        ENDPOINTS_REFRESHED,
        NO_OP,
        REREGISTERED
    }

    public RepairOutcome repair(ResourceDescriptor resource, ProxyContext context) {
        String resourceUrl = resource.getUrl();
        BucketInfo bucketInfo = new BucketInfo(resource.getBucketName(), resource.getBucketLocation());

        Pair<ResourceItemMetadata, String> raw = resourceService.getResourceWithMetadata(resource, EtagHeader.ANY);
        if (raw == null) {
            throw new ResourceNotFoundException("ToolSet is not found: " + resourceUrl);
        }
        ResourceItemMetadata meta = raw.getKey();
        ToolSet toolSet = ProxyUtil.convertToObject(raw.getValue(), ToolSet.class);
        if (toolSet == null) {
            throw new ResourceNotFoundException("ToolSet is not found: " + resourceUrl);
        }
        toolSet.setAuthor(meta.getAuthor());

        ResourceAuthSettings authSettings = toolSet.getAuthSettings();
        if (authSettings == null || authSettings.getAuthenticationType() != AuthenticationType.OAUTH) {
            throw new IllegalArgumentException("ToolSet " + resourceUrl + " does not use OAuth");
        }
        if (!Boolean.TRUE.equals(authSettings.getDynamicallyRegistered())) {
            throw new IllegalArgumentException(
                    "ToolSet " + resourceUrl + " is not eligible for repair (dynamicallyRegistered != true)");
        }

        encryptionService.decrypt(resourceUrl, bucketInfo, authSettings);

        // Locator covers GLOBAL + calling user's USER level; used for both probe and cleanup
        CredentialsLocator locator = CredentialsLocatorFactory.fromAnyUrl(resourceUrl, context, ResourceTypes.TOOL_SET);

        log.info("Repair: discovering AS metadata for toolset={}", resourceUrl);
        AuthorizationServerMetadata asMetadata = registrationService.discoverMetadata(resourceUrl, toolSet.getEndpoint());
        if (asMetadata == null) {
            throw new HttpException(HttpStatus.BAD_GATEWAY,
                    "Cannot discover AS metadata for toolset " + resourceUrl + ": AS unreachable or PRM missing");
        }

        String freshAuthEndpoint = asMetadata.getAuthorizationEndpoint();
        String freshTokenEndpoint = asMetadata.getTokenEndpoint();
        boolean endpointsChanged = !Objects.equals(freshAuthEndpoint, authSettings.getAuthorizationEndpoint())
                || !Objects.equals(freshTokenEndpoint, authSettings.getTokenEndpoint());

        // Probe with any available refresh token — client_id is shared, one probe covers the whole toolset
        List<ResourceCredentials> allCreds = credentialsService.getAllResourceCredentials(locator);
        String probeToken = allCreds.stream()
                .filter(c -> c != null && c.getRefreshToken() != null)
                .map(ResourceCredentials::getRefreshToken)
                .findFirst()
                .orElse(null);

        boolean clientDead;
        if (probeToken != null) {
            clientDead = isClientDead(resourceUrl, authSettings, freshTokenEndpoint, probeToken);
        } else {
            log.info("Repair: no credentials found for toolset={}, proceeding with re-registration", resourceUrl);
            clientDead = true;
        }

        if (!clientDead) {
            if (!endpointsChanged) {
                log.info("Repair: no-op for toolset={} (client valid, endpoints unchanged)", resourceUrl);
                return RepairOutcome.NO_OP;
            }
            // Atomic update: endpoint URLs are plaintext — no decrypt/re-encrypt needed
            String author = toolSet.getAuthor();
            resourceService.computeResource(resource, EtagHeader.ANY, author, json -> {
                ToolSet stored = ProxyUtil.convertToObject(json, ToolSet.class);
                if (stored == null) {
                    throw new ResourceNotFoundException("ToolSet is not found: " + resourceUrl);
                }
                stored.getAuthSettings().setAuthorizationEndpoint(freshAuthEndpoint);
                stored.getAuthSettings().setTokenEndpoint(freshTokenEndpoint);
                return ProxyUtil.convertToString(stored);
            });
            log.info("Repair: endpoints refreshed for toolset={}", resourceUrl);
            return RepairOutcome.ENDPOINTS_REFRESHED;
        }

        // Re-register: perform DCR outside the lock (network call), then apply atomically
        ClientRegistration registration = registrationService.register(
                resourceUrl, toolSet.getEndpoint(), authSettings, true);

        String author = toolSet.getAuthor();
        resourceService.computeResource(resource, EtagHeader.ANY, author, json -> {
            ToolSet stored = ProxyUtil.convertToObject(json, ToolSet.class);
            if (stored == null) {
                throw new ResourceNotFoundException("ToolSet is not found: " + resourceUrl);
            }
            ResourceAuthSettings storedSettings = stored.getAuthSettings();
            authSettingsService.applyRegistration(storedSettings, registration);
            storedSettings.setDynamicallyRegistered(true);
            encryptionService.encrypt(resourceUrl, bucketInfo, storedSettings);
            return ProxyUtil.convertToString(stored);
        });

        try {
            credentialsService.deleteResourceCredentials(locator);
        } catch (Exception e) {
            log.error("Repair: new registration persisted for toolset={} but credential cleanup failed. "
                    + "Stale credentials referencing the old client_id must be cleared manually. locator={}",
                    resourceUrl, locator, e);
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Re-registration completed but credential cleanup failed for toolset " + resourceUrl
                    + ". Check server logs.");
        }

        log.info("Repair: re-registered toolset={}", resourceUrl);
        return RepairOutcome.REREGISTERED;
    }

    /**
     * Returns {@code true} when the probe confirms the client is dead.
     * {@code false} means the client is valid (success or {@code invalid_grant} — AS authenticates
     * the client before checking the grant, so an expired token returns {@code invalid_grant}, never
     * {@code invalid_client}).
     * Any other outcome (network error, unexpected status, unparseable body) is treated as
     * inconclusive → {@code true} (safe fallback: re-register).
     */
    private boolean isClientDead(String resourceId, ResourceAuthSettings authSettings,
                                  String freshTokenEndpoint, String refreshToken) {
        ResourceAuthSettings probeSettings = authSettings.toBuilder()
                .tokenEndpoint(freshTokenEndpoint)
                .build();
        try {
            tokenService.getToken(resourceId, probeSettings, refreshToken);
            return false;
        } catch (HttpException e) {
            String error = extractOauthError(e.getBody());
            log.info("Repair: token probe for toolset={} → http_status={}, oauth_error={}",
                    resourceId, e.getStatus(), error);
            return !"invalid_grant".equals(error);
        } catch (Exception e) {
            // Network timeout, SSL failure, etc. — inconclusive, safe to re-register
            log.warn("Repair: token probe failed with network/IO error for toolset={}, treating as inconclusive",
                    resourceId, e);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractOauthError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = JsonMapperUtil.convertToObject(body, Map.class);
            if (map != null && map.containsKey("error")) {
                return String.valueOf(map.get("error"));
            }
        } catch (Exception e) {
            log.warn("Repair: could not parse OAuth error body, treating as inconclusive. body={}", body, e);
        }
        return null;
    }
}
