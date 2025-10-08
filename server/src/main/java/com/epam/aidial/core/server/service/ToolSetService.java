package com.epam.aidial.core.server.service;

<<<<<<< HEAD
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
=======
import com.epam.aidial.core.config.CredentialsLevel;
>>>>>>> development
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
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

<<<<<<< HEAD
import java.util.Objects;

=======
import java.util.Map;
import javax.annotation.Nullable;

@Slf4j
>>>>>>> development
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

            verifyToolSetUpdate(toolSet);

            if (shouldEnrichOauthResourceAuthSettings(toolSet, existing)) {
                resourceAuthSettingsService.enrichResourceAuthSettings(toolSet.getName(), toolSet.getEndpoint(), toolSet.getAuthSettings());
            }

            if (!shouldUpdateResourceAuthSettings(toolSet, existing)) {
                toolSet.setAuthSettings(existing.getAuthSettings());
            }

            resourceAuthSettingsEncryptionService.encrypt(resource.getUrl(),
                    new BucketInfo(resource.getBucketName(), resource.getBucketLocation()),
                    toolSet.getAuthSettings());
            return ProxyUtil.convertToString(toolSet);
        });

        return Pair.of(meta, toolSet);
    }

    /**
     * @param credentialsToCopy a map defining which credential levels should be copied
     *                          and whether they are required:
     *                          <ul>
     *                            <li>{@code true} — credentials at this level are required; if not found, an error is thrown.</li>
     *                            <li>{@code false} — credentials at this level are optional; they are copied only if present.</li>
     *                          </ul>
     */
    public void copyToolSet(ProxyContext context,
                            ResourceDescriptor source,
                            ResourceDescriptor destination,
                            @Nullable String author,
                            boolean overwrite,
                            Map<CredentialsLevel, Boolean> credentialsToCopy) {

        verifyToolSet(source);
        verifyToolSet(destination);
        verifyCredentials(context, source, credentialsToCopy);

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

        for (Map.Entry<CredentialsLevel, Boolean> entry : credentialsToCopy.entrySet()) {
            // Copy the toolset first. If toolset copying fails, credentials won't be copied.
            // If the dataset is copied but credentials are not, it's not a critical issue.
            CredentialsLevel credentialsLevel = entry.getKey();
            boolean isRequired = entry.getValue();
            boolean copied = copyCredentials(context, source, destination, credentialsLevel, overwrite);
            if (!copied && isRequired) {
                throw new ResourceNotFoundException("Toolset was copied, but credentials are not. ResourceId: %s"
                        .formatted(source.getUrl()));
            }
        }
    }

    private boolean copyCredentials(ProxyContext context, ResourceDescriptor source, ResourceDescriptor destination,
                                    CredentialsLevel credentialsLevel, boolean overwrite) {
        CredentialsDescriptor sourceCredentialDescriptor =
                CredentialsDescriptorFactory.fromResourceDescriptor(source, credentialsLevel, context);
        CredentialsDescriptor destinationCredentialDescriptor =
                CredentialsDescriptorFactory.fromResourceDescriptor(destination, credentialsLevel, context);
        return resourceCredentialsService.copyResourceCredentials(
                sourceCredentialDescriptor, destinationCredentialDescriptor, credentialsLevel, overwrite);
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

    /**
     * Determines whether OAuth resource authentication settings should be enriched with metadata
     * from the authorization server.
     *
     * <p>Enrichment is required in the following cases:
     * <ul>
     *   <li>When creating a new ToolSet with OAuth authentication</li>
     *   <li>When the ToolSet endpoint URL has changed (requires re-discovery of OAuth metadata)</li>
     *   <li>When any OAuth-specific authentication settings have been modified</li>
     * </ul>
     *
     * <p>Enrichment is skipped when:
     * <ul>
     *   <li>Authentication type is not OAuth (API key or no authentication)</li>
     *   <li>Updating an existing ToolSet with unchanged endpoint and OAuth settings</li>
     * </ul>
     *
     * @param toolSet the new ToolSet configuration being saved
     * @param existing the existing ToolSet configuration, or null if creating a new ToolSet
     * @return true if OAuth settings should be enriched with authorization server metadata, false otherwise
     */
    private boolean shouldEnrichOauthResourceAuthSettings(ToolSet toolSet, ToolSet existing) {
        ResourceAuthSettings newResourceAuthSettings = toolSet.getAuthSettings();

        // Skip enrichment for non-OAuth authentication types
        if (!newResourceAuthSettings.getAuthenticationType().equals(AuthenticationType.OAUTH)) {
            return false;
        }

        // Always enrich when creating a new ToolSet with OAuth
        if (existing == null) {
            return true;
        }

        // Enrich when endpoint URL changes (requires OAuth metadata re-discovery)
        if (!Objects.equals(existing.getEndpoint(), toolSet.getEndpoint())) {
            return true;
        }

        // Enrich when OAuth authentication settings have been modified
        return isOauthAuthSettingsChanged(newResourceAuthSettings, existing.getAuthSettings());
    }

    private void verifyToolSetUpdate(ToolSet toolSet) {
        ResourceAuthSettings newResourceAuthSettings = toolSet.getAuthSettings();

        if (newResourceAuthSettings.getCodeChallenge() != null
                || newResourceAuthSettings.getCodeVerifier() != null) {
            throw new IllegalArgumentException("Code challenge/Code verifier can't be set by client");
        }
    }

    /**
     * Determines whether the resource authentication settings should be updated with the new values.
     *
     * <p>Settings are updated in the following cases:
     * <ul>
     *   <li>When creating a new ToolSet (no existing configuration)</li>
     *   <li>When OAuth authentication settings have been modified</li>
     *   <li>When the API key header has changed</li>
     * </ul>
     *
     * <p>If settings should not be updated, the existing authentication settings are preserved
     * to maintain current authentication state and credentials.
     *
     * @param toolSet the new ToolSet configuration being saved
     * @param existing the existing ToolSet configuration, or null if creating a new ToolSet
     * @return true if authentication settings should be updated with new values, false to preserve existing settings
     */
    private boolean shouldUpdateResourceAuthSettings(ToolSet toolSet, ToolSet existing) {
        // Always update when creating a new ToolSet
        if (existing == null) {
            return true;
        }

        ResourceAuthSettings newResourceAuthSettings = toolSet.getAuthSettings();
        ResourceAuthSettings existingResourceAuthSettings = existing.getAuthSettings();

        // Update when OAuth settings or API key header have changed
        return isOauthAuthSettingsChanged(newResourceAuthSettings, existingResourceAuthSettings)
                || !Objects.equals(existingResourceAuthSettings.getApiKeyHeader(), newResourceAuthSettings.getApiKeyHeader()
        );
    }

    private boolean isOauthAuthSettingsChanged(ResourceAuthSettings newResourceAuthSettings,
                                               ResourceAuthSettings existingResourceAuthSettings) {
        return !Objects.equals(existingResourceAuthSettings.getAuthenticationType(), newResourceAuthSettings.getAuthenticationType())
                || !Objects.equals(existingResourceAuthSettings.getClientId(), newResourceAuthSettings.getClientId())
                || (newResourceAuthSettings.getClientSecret() != null
                && !Objects.equals(existingResourceAuthSettings.getClientSecret(), newResourceAuthSettings.getClientSecret()))
                || !Objects.equals(existingResourceAuthSettings.getAuthorizationEndpoint(), newResourceAuthSettings.getAuthorizationEndpoint())
                || !Objects.equals(existingResourceAuthSettings.getTokenEndpoint(), newResourceAuthSettings.getTokenEndpoint())
                || !Objects.equals(existingResourceAuthSettings.getRedirectUri(), newResourceAuthSettings.getRedirectUri())
                || !Objects.equals(existingResourceAuthSettings.getCodeChallengeMethod(), newResourceAuthSettings.getCodeChallengeMethod())
                || !Objects.equals(existingResourceAuthSettings.getScopesSupported(), newResourceAuthSettings.getScopesSupported()
        );
    }

    private void verifyCredentials(ProxyContext context, ResourceDescriptor resource,
                                   Map<CredentialsLevel, Boolean> copyingStrategy) {
        for (Map.Entry<CredentialsLevel, Boolean> entry : copyingStrategy.entrySet()) {
            CredentialsLevel credentialsLevel = entry.getKey();
            boolean isRequired = entry.getValue();

            if (isRequired) {
                CredentialsDescriptor credentialsDescriptor =
                        CredentialsDescriptorFactory.fromResourceDescriptor(resource, credentialsLevel, context);
                ResourceCredentials resourceCredentials = resourceCredentialsService.getResourceCredentials(credentialsDescriptor);
                if (resourceCredentials == null || resourceCredentials.getCredentialsLevel() != credentialsLevel) {
                    throw new ResourceNotFoundException("Global toolset credentials are not found. ResourceId: %s"
                            .formatted(resource.getUrl()));
                }
            }
        }
    }

}
