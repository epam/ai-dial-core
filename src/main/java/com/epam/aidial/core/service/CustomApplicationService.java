package com.epam.aidial.core.service;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.data.ListSharedResourcesRequest;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.NodeType;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.SharedResourcesResponse;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class CustomApplicationService {

    private static final int FETCH_SIZE = 1000;

    private final EncryptionService encryptionService;
    private final ResourceService resourceService;
    private final ShareService shareService;
    private final AccessService accessService;
    private final JsonObject applicationsConfig;

    /**
     * Loads a custom application from provided url with permission check
     *
     * @param url - custom application url
     * @return - custom application or null if invalid url provided or resource not found
     */
    public Application getCustomApplication(String url, ProxyContext context) {
        ResourceDescription resource;
        try {
            resource = ResourceDescription.fromAnyUrl(url, encryptionService);
        } catch (Exception e) {
            log.warn("Invalid resource url provided: {}", url);
            throw new ResourceNotFoundException("Application %s not found".formatted(url));
        }

        if (resource.getType() != ResourceType.APPLICATION) {
            throw new IllegalArgumentException("Unsupported deployment type: " + resource.getType());
        }

        boolean hasAccess = accessService.hasReadAccess(resource, context);
        if (!hasAccess) {
            throw new PermissionDeniedException("User don't have access to the deployment " + url);
        }

        String applicationBody = resourceService.getResource(resource);
        return ProxyUtil.convertToObject(applicationBody, Application.class, true);
    }

    /**
     *
     * @return list of custom applications from user's bucket
     */
    public List<Application> getOwnCustomApplications(ProxyContext context) {
        String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(bucketLocation);

        List<Application> applications = new ArrayList<>();
        ResourceDescription rootFolderResource = ResourceDescription.fromDecoded(ResourceType.APPLICATION, bucket, bucketLocation, null);
        fetchApplicationsRecursively(rootFolderResource, applications);

        return applications;
    }

    /**
     *
     * @return list of custom applications shared with user
     */
    public List<Application> getSharedApplications(ProxyContext context) {
        String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(bucketLocation);

        ListSharedResourcesRequest listSharedResourcesRequest = new ListSharedResourcesRequest();
        listSharedResourcesRequest.setResourceTypes(Set.of(ResourceType.APPLICATION));
        SharedResourcesResponse sharedResourcesResponse = shareService.listSharedWithMe(bucket, bucketLocation, listSharedResourcesRequest);
        Set<MetadataBase> metadata = sharedResourcesResponse.getResources();
        // metadata can be either item or folder
        List<Application> applications = new ArrayList<>();
        for (MetadataBase meta : metadata) {
            ResourceDescription resource = ResourceDescription.fromAnyUrl(meta.getUrl(), encryptionService);
            fetchApplicationsRecursively(resource, applications);
        }

        return applications;
    }

    /**
     *
     * @return list of published custom applications
     */
    public List<Application> getPublicApplications(ProxyContext context) {
        List<Application> applications = new ArrayList<>();
        ResourceDescription rootFolderResource = ResourceDescription.fromDecoded(ResourceType.APPLICATION, BlobStorageUtil.PUBLIC_BUCKET, BlobStorageUtil.PUBLIC_LOCATION, null);

        String nextToken = null;
        boolean fetchMore = true;
        while (fetchMore) {
            ResourceFolderMetadata metadataResponse = resourceService.getFolderMetadata(rootFolderResource, nextToken, FETCH_SIZE, true);
            // if no metadata present - stop fetching
            if (metadataResponse == null) {
                return applications;
            }

            nextToken = metadataResponse.getNextToken();
            fetchMore = nextToken != null;

            accessService.filterForbidden(context, rootFolderResource, metadataResponse);
            fetchItems(metadataResponse.getItems(), applications);
        }

        return applications;
    }

    public boolean includeCustomApplications() {
        return applicationsConfig.getBoolean("includeCustomApps", false);
    }

    private void fetchApplicationsRecursively(ResourceDescription description, List<Application> result) {
        if (!description.isFolder()) {
            Application application = fetchApplication(description.getUrl());
            if (application != null) {
                result.add(application);
            }
            return;
        }

        String nextToken = null;
        boolean fetchMore = true;
        while (fetchMore) {
            MetadataBase metadataResponse = resourceService.getMetadata(description, nextToken, FETCH_SIZE, true);
            // if no metadata present - stop fetching
            if (metadataResponse == null) {
                return;
            }

            if (metadataResponse instanceof ResourceFolderMetadata folderMetadata) {
                nextToken = folderMetadata.getNextToken();
                fetchMore = nextToken != null;
                List<? extends MetadataBase> items = folderMetadata.getItems();
                fetchItems(items, result);
            } else {
                // if response is not a folder metadata - stop fetching
                fetchMore = false;
            }
        }
    }

    private void fetchItems(List<? extends MetadataBase> items, List<Application> result) {
        for (MetadataBase item : items) {
            if (item.getNodeType() == NodeType.ITEM && item.getResourceType() == ResourceType.APPLICATION) {
                Application application = fetchApplication(item.getUrl());
                if (application != null) {
                    result.add(application);
                }
            }
        }
    }

    private Application fetchApplication(String applicationUrl) {
        String data = resourceService.getResource(ResourceDescription.fromAnyUrl(applicationUrl, encryptionService));
        return ProxyUtil.convertToObject(data, Application.class, true);
    }
}
