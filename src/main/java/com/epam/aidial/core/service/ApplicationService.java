package com.epam.aidial.core.service;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.controller.ApplicationUtil;
import com.epam.aidial.core.data.*;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
public class ApplicationService {

    private static final int PAGE_SIZE = 1000;

    private final EncryptionService encryption;
    private final ResourceService resources;
    private final String controllerUrl;
    @Getter
    private final boolean includeCustomApps;

    public ApplicationService(EncryptionService encryption, ResourceService resources, JsonObject settings) {
        this.encryption = encryption;
        this.resources = resources;
        this.controllerUrl = settings.getString("controllerUrl", null);
        this.includeCustomApps = settings.getBoolean("includeCustomApps", false);
    }

    public List<Application> getAllApplications(ProxyContext context) {
        List<Application> applications = new ArrayList<>();
        applications.addAll(getPrivateApplications(context));
        applications.addAll(getSharedApplications(context));
        applications.addAll(getPublicApplications(context));
        return applications;
    }

    public List<Application> getPrivateApplications(ProxyContext context) {
        String location = BlobStorageUtil.buildInitiatorBucket(context);
        String bucket = encryption.encrypt(location);

        ResourceDescription folder = ResourceDescription.fromDecoded(ResourceType.APPLICATION, bucket, location, null);
        return getApplications(folder);
    }

    public List<Application> getSharedApplications(ProxyContext context) {
        String location = BlobStorageUtil.buildInitiatorBucket(context);
        String bucket = encryption.encrypt(location);

        ListSharedResourcesRequest request = new ListSharedResourcesRequest();
        request.setResourceTypes(Set.of(ResourceType.APPLICATION));

        ShareService shares = context.getProxy().getShareService();
        SharedResourcesResponse response = shares.listSharedWithMe(bucket, location, request);
        Set<MetadataBase> metadata = response.getResources();

        List<Application> list = new ArrayList<>();

        for (MetadataBase meta : metadata) {
            ResourceDescription resource = ResourceDescription.fromAnyUrl(meta.getUrl(), encryption);

            if (meta instanceof ResourceItemMetadata) {
                list.add(getApplication(resource).getValue());
            } else {
                list.addAll(getApplications(resource));
            }
        }

        return list;
    }

    public List<Application> getPublicApplications(ProxyContext context) {
        ResourceDescription folder = ResourceDescription.fromDecoded(ResourceType.APPLICATION, BlobStorageUtil.PUBLIC_BUCKET, BlobStorageUtil.PUBLIC_LOCATION, null);
        AccessService accesses = context.getProxy().getAccessService();
        return getApplications(folder, page -> accesses.filterForbidden(context, folder, page));
    }

    public Pair<ResourceItemMetadata, Application> getApplication(ResourceDescription resource) {
        if (resource.isFolder() || resource.getType() != ResourceType.APPLICATION) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }

        Pair<ResourceItemMetadata, String> result = resources.getResourceWithMetadata(resource);
        if (result == null) {
            throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
        }

        ResourceItemMetadata meta = result.getKey();
        Application application = ProxyUtil.convertToObject(result.getValue(), Application.class, true);

        if (application.getFunction() != null) {
            application.getFunction().setState(null); // hide state from a client
        }

        return Pair.of(meta, application);
    }

    public List<Application> getApplications(ResourceDescription resource) {
        Consumer<ResourceFolderMetadata> noop = ignore -> {
        };
        return getApplications(resource, noop);
    }

    public List<Application> getApplications(ResourceDescription resource, Consumer<ResourceFolderMetadata> filter) {
        if (!resource.isFolder() || resource.getType() != ResourceType.APPLICATION) {
            throw new IllegalArgumentException("Invalid application folder: " + resource.getUrl());
        }

        List<Application> applications = new ArrayList<>();
        String nextToken = null;

        do {
            ResourceFolderMetadata folder = resources.getFolderMetadata(resource, nextToken, PAGE_SIZE, true);
            if (folder == null) {
                break;
            }

            filter.accept(folder);

            for (MetadataBase meta : folder.getItems()) {
                if (meta.getNodeType() == NodeType.ITEM && meta.getResourceType() == ResourceType.APPLICATION) {
                    try {
                        ResourceDescription item = ResourceDescription.fromAnyUrl(meta.getUrl(), encryption);
                        Application application = getApplication(item).getValue();
                        applications.add(application);
                    } catch (ResourceNotFoundException ignore) {
                        // deleted while fetching
                    }
                }
            }

            nextToken = folder.getNextToken();
        } while (nextToken != null);

        return applications;
    }

    public Pair<ResourceItemMetadata, Application> putApplication(ResourceDescription resource, EtagHeader etag, Application application) {
        return putApplication(resource, etag, application, false);
    }

    private Pair<ResourceItemMetadata, Application> putApplication(ResourceDescription resource, EtagHeader etag,
                                                                   Application application, boolean preserveReference) {
        prepareApplication(resource, etag, application, preserveReference);

        if (application.getFunction() != null) {
            throw new HttpException(HttpStatus.CONFLICT, "Currently not supported");
        }

        ResourceItemMetadata meta = resources.computeResource(resource, etag, json -> {
            Application existing = ProxyUtil.convertToObject(json, Application.class, true);
            Application.Function function = application.getFunction();

            if (function != null) {
                function.setStatus(Application.Function.Status.CREATED);
                function.setState(new Application.Function.State());
            }

            if (existing != null && existing.getFunction() != null) {
                Application.Function existingFunction = existing.getFunction();

                if (function == null && existingFunction.getStatus() != Application.Function.Status.CREATED) {
                    throw new HttpException(HttpStatus.CONFLICT, "The previous application must be deleted");
                }

                if (function != null) {
                    function.setTargets(existing.getFunction().getTargets());
                    function.setStatus(existing.getFunction().getStatus());
                    function.setState(existing.getFunction().getState());
                }
            }

            return ProxyUtil.convertToString(application, true);
        });

        return Pair.of(meta, application);
    }

    public void deleteApplication(ResourceDescription resource, EtagHeader etag) {
        if (resource.isFolder() || resource.getType() != ResourceType.APPLICATION) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }

        resources.computeResource(resource, etag, json -> {
            Application application = ProxyUtil.convertToObject(json, Application.class, true);
            if (application == null) {
                throw new HttpException(HttpStatus.NOT_FOUND, "Application is not found: " + resource.getUrl());
            }

            Application.Function function = application.getFunction();
            if (function != null && (function.getStatus() == Application.Function.Status.STARTING
                            || function.getStatus() == Application.Function.Status.STARTED
                            || function.getStatus() == Application.Function.Status.STOPPING)) {
                throw new HttpException(HttpStatus.NOT_FOUND, "Application must be stopped: " + resource.getUrl());
            }

            return null;
        });
    }

    public void copyApplication(ResourceDescription source, ResourceDescription description, boolean overwrite) {
        Application application = getApplication(source).getValue();
        putApplication(description, overwrite ? EtagHeader.ANY : EtagHeader.NEW_ONLY, application, true);
    }

    private void prepareApplication(ResourceDescription resource, EtagHeader etag,
                                    Application application, boolean preserveReference) {
        if (resource.isFolder() || resource.getType() != ResourceType.APPLICATION) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }

        if (application.getEndpoint() == null && application.getFunction() == null) {
            throw new IllegalArgumentException("Application endpoint or function must be provided");
        }

        if (application.getEndpoint() != null && application.getFunction() != null) {
            throw new IllegalArgumentException("Both application endpoint and function are provided");
        }

        // replace application name with it's url
        application.setName(resource.getUrl());
        // defining user roles in custom applications are not allowed
        application.setUserRoles(Set.of());
        // forward auth token is not allowed for custom applications
        application.setForwardAuthToken(false);
        // reject request if both If-None-Match header and reference provided

        if (preserveReference && application.getReference() == null) {
            throw new IllegalArgumentException("No application reference");
        }

        if (!preserveReference && application.getReference() != null && !etag.isOverwrite()) {
            throw new IllegalArgumentException("Creating application with provided reference is not allowed");
        }

        if (application.getReference() == null) {
            application.setReference(ApplicationUtil.generateReference());
        }

        Application.Function function = application.getFunction();
        if (function != null) {
            function.setStatus(null);
            function.setTargets(null);
            function.setState(null);

            if (function.getSources() == null) {
                throw new IllegalArgumentException("Application function sources must be provided");
            }

            try {
                ResourceDescription folder = ResourceDescription.fromAnyUrl(function.getSources(), encryption);

                if (!folder.isFolder() || folder.getType() != ResourceType.FILE) {
                    throw new IllegalArgumentException();
                }

                function.setSources(folder.getUrl());
            } catch (Throwable e) {
                throw new IllegalArgumentException("Application function sources must be a valid file folder: " + function.getSources());
            }
        }
    }
}