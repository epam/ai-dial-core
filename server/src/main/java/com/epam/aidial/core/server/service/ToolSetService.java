package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.CatalogPropertiesLinkRewriter;
import com.epam.aidial.core.server.util.CredentialsDescriptorFactory;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.CatalogSchemaValidationException;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;

@Slf4j
@AllArgsConstructor
public class ToolSetService {

    private final ResourceService resourceService;
    private final ResourceAuthSettingsService resourceAuthSettingsService;
    private final ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;
    private final ResourceCredentialsService resourceCredentialsService;
    private final CatalogSchemaService catalogSchemaService;

    public Pair<ResourceItemMetadata, ToolSet> getToolSet(ResourceDescriptor resource) {
        return getToolSet(resource, EtagHeader.ANY);
    }

    public Pair<ResourceItemMetadata, ToolSet> getToolSet(ResourceDescriptor resource, EtagHeader etagHeader) {
        Pair<ResourceItemMetadata, ToolSet> result = getToolSet(resource, false, etagHeader);
        ToolSet toolSet = result.getValue();
        ResourceItemMetadata meta = result.getKey();

        toolSet.setAuthor(meta.getAuthor());
        toolSet.setCreatedAt(meta.getCreatedAt());
        toolSet.setUpdatedAt(meta.getUpdatedAt());

        return Pair.of(meta, toolSet);
    }

    private Pair<ResourceItemMetadata, ToolSet> getToolSet(ResourceDescriptor resource, boolean decryptAuthSettings, EtagHeader etagHeader) {
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

        if (decryptAuthSettings) {
            resourceAuthSettingsEncryptionService.decrypt(resource.getUrl(),
                    new BucketInfo(resource.getBucketName(), resource.getBucketLocation()),
                    toolSet.getAuthSettings());
        }

        return Pair.of(meta, toolSet);
    }

    /**
     * Like {@link #getToolSet(ResourceDescriptor, EtagHeader)} but with {@code authSettings}
     * decrypted — the read-after-write counterpart to {@link ApplicationService
     * .getApplicationWithDecryptedSecrets}, used after a PUT so the value applied into the merged
     * {@link com.epam.aidial.core.config.Config} carries plaintext secrets.
     */
    public Pair<ResourceItemMetadata, ToolSet> getToolSetWithDecryptedAuthSettings(ResourceDescriptor resource) {
        Pair<ResourceItemMetadata, ToolSet> result = getToolSet(resource, true, EtagHeader.ANY);
        ToolSet toolSet = result.getValue();
        ResourceItemMetadata meta = result.getKey();

        toolSet.setAuthor(meta.getAuthor());
        toolSet.setCreatedAt(meta.getCreatedAt());
        toolSet.setUpdatedAt(meta.getUpdatedAt());

        return Pair.of(meta, toolSet);
    }

    public ToolSet extractFrom(String content, ResourceItemMetadata meta) {
        ToolSet toolSet = ProxyUtil.convertToObject(content, ToolSet.class);

        if (toolSet == null) {
            throw new IllegalArgumentException("ToolSet content is missed");
        }
        toolSet.setAuthor(meta.getAuthor());
        toolSet.setCreatedAt(meta.getCreatedAt());
        toolSet.setUpdatedAt(meta.getUpdatedAt());
        return toolSet;
    }

    public Pair<ResourceItemMetadata, ToolSet> putToolSet(ResourceDescriptor resource, EtagHeader etag, String author, ToolSet toolSet,
                                                          boolean preserveForwardAuthToken) {
        validateCatalogProperties(toolSet);
        if (!preserveForwardAuthToken) {
            toolSet.setForwardAuthToken(false);
        }
        toolSet.setName(resource.getUrl());
        if (toolSet.getReference() == null) {
            toolSet.setReference(ProxyUtil.generateReference());
        }
        ResourceItemMetadata meta = resourceService.computeResource(resource, etag, author, json -> {
            ToolSet existing = ProxyUtil.convertToObject(json, ToolSet.class);
            if (existing != null) {
                resourceAuthSettingsEncryptionService.decrypt(resource.getUrl(),
                        new BucketInfo(resource.getBucketName(), resource.getBucketLocation()),
                        existing.getAuthSettings());
            }
            resourceAuthSettingsService.processResourceAuthSettings(toolSet, existing);
            resourceAuthSettingsEncryptionService.encrypt(resource.getUrl(),
                    new BucketInfo(resource.getBucketName(), resource.getBucketLocation()),
                    toolSet.getAuthSettings());
            return ProxyUtil.convertToString(toolSet);
        });

        return Pair.of(meta, toolSet);
    }

    public void copyToolSet(ProxyContext context,
                            ResourceDescriptor source,
                            ResourceDescriptor destination,
                            @Nullable String author,
                            boolean overwrite,
                            Map<CredentialsLevel, Boolean> credentialsToCopy) {
        copyToolSet(context, source, destination, author, overwrite, credentialsToCopy, toolSet -> {});
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
                            Map<CredentialsLevel, Boolean> credentialsToCopy,
                            Consumer<ToolSet> consumer) {

        verifyToolSet(source);
        verifyToolSet(destination);
        verifyCredentials(context, source, credentialsToCopy);

        Pair<ResourceItemMetadata, ToolSet> result = getToolSet(source, true, EtagHeader.ANY);
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

        consumer.accept(toolSet);
        validateCatalogProperties(toolSet);

        boolean isPublicOrReview = destination.isPublic() || PublicationService.isReviewBucket(destination);
        List<ResourceDescriptor> sourceCatalogFiles = List.of();
        List<ResourceDescriptor> destCatalogFiles = List.of();
        if (isPublicOrReview) {
            sourceCatalogFiles = catalogSchemaService.getFiles(toolSet);
            destCatalogFiles = toDestCatalogFiles(destination, sourceCatalogFiles);
            Map<String, String> fileReplacementLinks = new HashMap<>();
            for (int i = 0; i < sourceCatalogFiles.size(); i++) {
                fileReplacementLinks.put(sourceCatalogFiles.get(i).getDecodedUrl(), destCatalogFiles.get(i).getUrl());
            }
            toolSet.setCatalogProperties(CatalogPropertiesLinkRewriter.rewrite(toolSet.getCatalogProperties(), fileReplacementLinks));
        }

        String json = ProxyUtil.convertToString(toolSet);
        resourceService.putResource(destination, json, etag, author);

        for (int i = 0; i < sourceCatalogFiles.size(); i++) {
            ResourceDescriptor sourceFile = sourceCatalogFiles.get(i);
            ResourceDescriptor destFile = destCatalogFiles.get(i);
            if (sourceFile.isFolder()) {
                resourceService.copyFolder(sourceFile, destFile, false);
            } else if (!resourceService.copyResource(sourceFile, destFile, null, false)) {
                throw new IllegalArgumentException("Can't copy source file: " + sourceFile.getUrl()
                        + " to destination file: " + destFile.getUrl());
            }
        }

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

    public void setResourceAuthStatuses(ProxyContext context, ToolSet toolSet, String encodedToolSetId) {
        CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(encodedToolSetId, context, ResourceTypes.TOOL_SET);
        resourceAuthSettingsService.setResourceAuthStatuses(credentialsLocator, toolSet.getAuthSettings(), context.getInitiatorId());
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
        CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(resource.getUrl(), context, ResourceTypes.TOOL_SET);
        resourceCredentialsService.deleteResourceCredentials(credentialsLocator);

        return resourceService.deleteResource(resource, etag);
    }

    private void validateCatalogProperties(ToolSet toolSet) {
        try {
            catalogSchemaService.validate(toolSet);
        } catch (CatalogSchemaValidationException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Catalog properties validation failed: " + e.getMessage(), e);
        }
    }

    private static String getTargetFolderForCatalogFiles(ResourceDescriptor target) {
        String toolSetName = target.getName();
        String parentPath = target.getParentPath();
        return (parentPath == null ? "" : parentPath + ResourceDescriptor.PATH_SEPARATOR)
                + "." + toolSetName + ResourceDescriptor.PATH_SEPARATOR;
    }

    private static List<ResourceDescriptor> toDestCatalogFiles(ResourceDescriptor dest, List<ResourceDescriptor> sourceFiles) {
        if (sourceFiles.isEmpty()) {
            return List.of();
        }
        String targetFolder = getTargetFolderForCatalogFiles(dest);
        List<ResourceDescriptor> result = new ArrayList<>();
        for (ResourceDescriptor file : sourceFiles) {
            String path = targetFolder + file.getName();
            if (file.isFolder()) {
                path += ResourceDescriptor.PATH_SEPARATOR;
            }
            result.add(ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, dest.getBucketName(), dest.getBucketLocation(), path));
        }
        return result;
    }

    private static void verifyToolSet(ResourceDescriptor resource) {
        if (resource.isFolder() || resource.getType() != ResourceTypes.TOOL_SET) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }
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
