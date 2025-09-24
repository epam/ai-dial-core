package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.util.CredentialsDescriptorFactory;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

@Slf4j
@AllArgsConstructor
public class ToolSetService {

    private final ResourceService resourceService;
    private final ResourceAuthSettingsService resourceAuthSettingsService;
    private final ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;
    private final ResourceCredentialsService resourceCredentialsService;

    public Pair<ResourceItemMetadata, ToolSet> getToolSet(ProxyContext context, ResourceDescriptor resource) {
        return getToolSet(context, resource, EtagHeader.ANY);
    }

    public Pair<ResourceItemMetadata, ToolSet> getToolSet(ProxyContext context, ResourceDescriptor resource, EtagHeader etagHeader) {
        Pair<ResourceItemMetadata, ToolSet> result = getToolSet(resource, etagHeader);
        ToolSet toolSet = result.getValue();
        ResourceItemMetadata meta = result.getKey();

        CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(resource.getUrl(), context);
        resourceAuthSettingsService.setResourceAuthStatuses(credentialsLocator, toolSet.getAuthSettings(), context.getUserSub());
        toolSet.setAuthor(meta.getAuthor());
        toolSet.setCreatedAt(meta.getCreatedAt());
        toolSet.setUpdatedAt(meta.getUpdatedAt());

        return Pair.of(meta, toolSet);
    }

    private Pair<ResourceItemMetadata, ToolSet> getToolSet(ResourceDescriptor resource, EtagHeader etagHeader) {
        verifyToolSet(resource);
        Pair<ResourceItemMetadata, String> result = resourceService.getResourceWithMetadata(resource, etagHeader);

        if (result == null) {
            throw new ResourceNotFoundException("ToolSet is not found: " + resource.getUrl());
        }

        ResourceItemMetadata meta = result.getKey();
        ToolSet toolSet = ProxyUtil.convertToObject(result.getValue(), ToolSet.class);

        if (toolSet == null) {
            throw new ResourceNotFoundException("ToolSet is not found: " + resource.getUrl());
        }

        resourceAuthSettingsEncryptionService.decrypt(resource.getUrl(),
                new BucketInfo(resource.getBucketName(), resource.getBucketLocation()),
                toolSet.getAuthSettings());

        return Pair.of(meta, toolSet);
    }

    public Pair<ResourceItemMetadata, ToolSet> putToolSet(ResourceDescriptor resource, EtagHeader etag, String author, ToolSet toolSet) {
        toolSet.setName(resource.getUrl());
        if (toolSet.getReference() == null) {
            toolSet.setReference(ProxyUtil.generateReference());
        }
        ResourceItemMetadata meta = resourceService.computeResource(resource, etag, author, json -> {
            ToolSet existing = ProxyUtil.convertToObject(json, ToolSet.class);
            if (shouldEnrichResourceAuthSettings(toolSet, existing)) {
                resourceAuthSettingsService.enrichResourceAuthSettings(toolSet.getName(), toolSet.getEndpoint(), toolSet.getAuthSettings());
            } else {
                //TODO we don't support auth settings update yet
                toolSet.setAuthSettings(existing.getAuthSettings());
            }
            resourceAuthSettingsEncryptionService.encrypt(resource.getUrl(),
                    new BucketInfo(resource.getBucketName(), resource.getBucketLocation()),
                    toolSet.getAuthSettings());
            return ProxyUtil.convertToString(toolSet);
        });

        return Pair.of(meta, toolSet);
    }

    public void copyToolSet(ProxyContext context, ResourceDescriptor source, ResourceDescriptor destination,
                            String author, boolean overwrite, boolean copyCredentials) {
        verifyToolSet(source);
        verifyToolSet(destination);

        Pair<ResourceItemMetadata, ToolSet> result = getToolSet(source, EtagHeader.ANY);
        ToolSet toolSet = result.getValue();
        if (author == null) {
            author = result.getKey().getAuthor();
        }

        EtagHeader etag = overwrite ? EtagHeader.ANY : EtagHeader.NEW_ONLY;
        toolSet.setName(destination.getUrl());
        toolSet.setReference(ProxyUtil.generateReference());

        resourceAuthSettingsEncryptionService.encrypt(destination.getUrl(),
                new BucketInfo(destination.getBucketName(), destination.getBucketLocation()),
                toolSet.getAuthSettings());

        String json = ProxyUtil.convertToString(toolSet);
        resourceService.putResource(destination, json, etag, author);

        // Copy the toolset first. If toolset copying fails, credentials won't be copied.
        // If the dataset is copied but credentials are not, it's not a critical issue.
        if (copyCredentials) {
            copyCredentials(context, source, destination);
        }
    }

    private void copyCredentials(ProxyContext context, ResourceDescriptor source, ResourceDescriptor destination) {
        CredentialsDescriptor sourceCredentialDescriptor =
                CredentialsDescriptorFactory.fromResourceDescriptor(source, CredentialsLevel.GLOBAL, context);
        CredentialsDescriptor destinationCredentialDescriptor =
                CredentialsDescriptorFactory.fromResourceDescriptor(destination, CredentialsLevel.GLOBAL, context);
        boolean copied = resourceCredentialsService.copyResourceCredentials(
                sourceCredentialDescriptor, destinationCredentialDescriptor, CredentialsLevel.GLOBAL);
        if (!copied) {
            throw new ResourceNotFoundException("Global toolset credentials are not found. ResourceId: %s"
                    .formatted(source.getUrl()));
        }
    }

    public boolean deleteToolset(ProxyContext context, ResourceDescriptor resource, EtagHeader etag) {

        // TODO: support removal all USER and APP credentials for public toolsets
        // TODO: support removal all USER and APP credentials for shared toolsets (?)
        CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(resource.getUrl(), context);
        resourceCredentialsService.deleteResourceCredentials(credentialsLocator);

        return resourceService.deleteResource(resource, etag);
    }

    private static void verifyToolSet(ResourceDescriptor resource) {
        if (resource.isFolder() || resource.getType() != ResourceTypes.TOOL_SET) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }
    }

    private boolean shouldEnrichResourceAuthSettings(ToolSet toolSet, ToolSet existing) {
        return existing == null
                || !existing.getAuthSettings().getAuthenticationType().equals(toolSet.getAuthSettings().getAuthenticationType())
                || !existing.getEndpoint().equals(toolSet.getEndpoint());
    }
}
