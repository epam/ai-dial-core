package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.credentials.service.registration.ResourceRegistrationService;
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

@Slf4j
@AllArgsConstructor
public class ToolSetRepairService {

    private final ResourceService resourceService;
    private final ResourceAuthSettingsEncryptionService encryptionService;
    private final ResourceCredentialsService credentialsService;
    private final ResourceRegistrationService registrationService;
    private final ResourceAuthSettingsService authSettingsService;

    public void repair(ResourceDescriptor resource, ProxyContext context) {
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

        log.info("Repair: discovering AS metadata for toolset={}", resourceUrl);
        if (registrationService.discoverMetadata(resourceUrl, toolSet.getEndpoint()) == null) {
            throw new HttpException(HttpStatus.FAILED_DEPENDENCY,
                    "Cannot discover AS metadata for toolset " + resourceUrl + ": AS unreachable or PRM missing");
        }

        ClientRegistration registration = registrationService.register(
                resourceUrl, toolSet.getEndpoint(), authSettings, true);

        String author = toolSet.getAuthor();
        // computeResource holds the distributed lock internally; an outer lock would deadlock against it.
        resourceService.computeResource(resource, EtagHeader.ANY, author, json -> {
            ToolSet stored = ProxyUtil.convertToObject(json, ToolSet.class);
            if (stored == null) {
                throw new ResourceNotFoundException("ToolSet is not found: " + resourceUrl);
            }
            ResourceAuthSettings storedSettings = stored.getAuthSettings();
            // Decrypt before applying registration: applyRegistration only replaces fields present
            // in the new ClientRegistration (e.g. codeVerifier is skipped when PKCE is absent).
            // Any field left untouched would still carry its encrypted value and be double-encrypted.
            encryptionService.decrypt(resourceUrl, bucketInfo, storedSettings);
            authSettingsService.applyRegistration(storedSettings, registration);
            storedSettings.setDynamicallyRegistered(true);
            encryptionService.encrypt(resourceUrl, bucketInfo, storedSettings);
            return ProxyUtil.convertToString(stored);
        });

        CredentialsLocator locator = CredentialsLocatorFactory.fromAnyUrl(resourceUrl, context, ResourceTypes.TOOL_SET);
        try {
            credentialsService.deleteResourceCredentials(locator);
        } catch (Exception e) {
            log.error("Repair: credential cleanup failed for toolset={}, stale credentials must be cleared manually. locator={}",
                    resourceUrl, locator, e);
        }

        log.info("Repair: re-registered toolset={}", resourceUrl);
    }
}
