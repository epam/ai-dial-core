package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ListSharedResourcesRequest;
import com.epam.aidial.core.server.data.SharedResourcesResponse;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.epam.aidial.core.storage.resource.ResourceTypes.APPLICATION;
import static com.epam.aidial.core.storage.resource.ResourceTypes.TOOL_SET;

@Slf4j
public class DeploymentService {

    private static final int PAGE_SIZE = 1000;

    private final EncryptionService encryptionService;

    private final ApplicationService applicationService;

    private final ToolSetService toolSetService;

    private final ResourceService resourceService;

    private final AccessService accessService;

    private final ApplicationSchemaService applicationSchemaService;

    public DeploymentService(EncryptionService encryptionService, ApplicationService applicationService,
                             AccessService accessService, ToolSetService toolSetService, ResourceService resourceService,
                             ApplicationSchemaService applicationSchemaService) {
        this.encryptionService = encryptionService;
        this.applicationService = applicationService;
        this.toolSetService = toolSetService;
        this.accessService = accessService;
        this.resourceService = resourceService;
        this.applicationSchemaService = applicationSchemaService;
    }

    public Deployment findDeployment(ProxyContext context, String id) {
        Deployment deployment = context.getConfig().selectDeployment(id);
        if (deployment != null) {
            if (!deployment.hasAccess(context.getUserRoles())) {
                throwForbiddenDeploymentError(id);
            }
            return deployment;
        }
        ResourceDescriptor deploymentDescriptor = toResourceDescriptor(context, id);
        ResourceType resourceType = deploymentDescriptor.getType();
        return switch (resourceType) {
            case APPLICATION -> applicationService.getApplication(deploymentDescriptor).getValue();
            case TOOL_SET -> toolSetService.getToolSet(context, deploymentDescriptor).getValue();
            default -> throw new IllegalArgumentException("Unknown resource type: " + resourceType);
        };
    }

    public  <T extends Deployment> List<T> listDeployments(ProxyContext context, ResourceTypes resourceType, DeploymentExtractor extractor) {
        List<T> deployments = new ArrayList<>();
        deployments.addAll(getPrivateDeployments(context, resourceType, extractor));
        deployments.addAll(getSharedDeployments(context, resourceType, extractor));
        deployments.addAll(getPublicDeployments(context, resourceType, extractor));
        return deployments;
    }

    private ResourceDescriptor toResourceDescriptor(ProxyContext context, String resourceUrl) {
        String url;
        ResourceDescriptor resource;

        try {
            url = UrlUtil.encodePath(resourceUrl);
            resource = ResourceDescriptorFactory.fromAnyUrl(url, encryptionService);
        } catch (Throwable ignore) {
            throw new ResourceNotFoundException("Unknown deployment: " + resourceUrl);
        }

        if (resource.isFolder()) {
            throw new ResourceNotFoundException("Invalid deployment url: " + url);
        }

        if (!accessService.hasReadAccess(resource, context)) {
            throwForbiddenDeploymentError(resourceUrl);
        }

        return resource;
    }

    private static void throwForbiddenDeploymentError(String deploymentId) {
        throw new PermissionDeniedException("Forbidden deployment: " + deploymentId);
    }

    private <T extends  Deployment> List<T> getPrivateDeployments(ProxyContext context, ResourceTypes resourceType, DeploymentExtractor extractor) {
        String location = BucketBuilder.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(location);

        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(resourceType, bucket, location, null);
        return getDeployments(folder, context, resourceType, extractor);
    }

    private <T extends  Deployment> List<T> getDeployments(ResourceDescriptor resource, ProxyContext ctx,
                                                           ResourceTypes resourceType, DeploymentExtractor extractor) {
        Consumer<ResourceFolderMetadata> noop = ignore -> {
        };
        return getDeployments(resource, noop, ctx, resourceType, extractor);
    }

    private <T extends  Deployment> List<T> getDeployments(ResourceDescriptor resource, Consumer<ResourceFolderMetadata> filter,
                                                           ProxyContext ctx, ResourceTypes resourceType, DeploymentExtractor extractor) {
        if (!resource.isFolder() || resource.getType() != resourceType) {
            throw new IllegalArgumentException("Invalid deployment folder: " + resource.getUrl());
        }

        List<T> deployments = new ArrayList<>();
        String nextToken = null;

        do {
            ResourceFolderMetadata folder = resourceService.getFolderMetadata(resource, nextToken, PAGE_SIZE, true);
            if (folder == null) {
                break;
            }

            filter.accept(folder);

            for (MetadataBase meta : folder.getItems()) {
                if (meta.getNodeType() == NodeType.ITEM && meta.getResourceType() == resourceType) {
                    try {
                        ResourceDescriptor item = ResourceDescriptorFactory.fromAnyUrl(meta.getUrl(), encryptionService);
                        T deployment = extractor.extract(item, ctx);
                        deployments.add(deployment);
                    } catch (ResourceNotFoundException ignore) {
                        // deleted while fetching
                    }
                }
            }

            nextToken = folder.getNextToken();
        } while (nextToken != null);

        return deployments;
    }

    private <T extends  Deployment> List<T> getSharedDeployments(ProxyContext context, ResourceTypes resourceType, DeploymentExtractor extractor) {
        String location = BucketBuilder.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(location);

        ListSharedResourcesRequest request = new ListSharedResourcesRequest();
        request.setResourceTypes(Set.of(resourceType));

        ShareService shares = context.getProxy().getShareService();
        SharedResourcesResponse response = shares.listSharedWithMe(bucket, location, request);
        Set<MetadataBase> metadata = response.getResources();

        List<T> list = new ArrayList<>();

        for (MetadataBase meta : metadata) {
            ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(meta.getUrl(), encryptionService);

            if (meta instanceof ResourceItemMetadata) {
                try {
                    T deployment = extractor.extract(resource, context);
                    list.add(deployment);
                } catch (ResourceNotFoundException ignore) {
                    // skip shared app which might be deleted incidentally
                    log.warn("Shared deployment is not found: {}", meta.getUrl());
                }
            } else {
                list.addAll(getDeployments(resource, context, resourceType, extractor));
            }
        }

        return list;
    }

    private <T extends  Deployment> List<T> getPublicDeployments(ProxyContext context, ResourceTypes resourceType, DeploymentExtractor extractor) {
        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(resourceType, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, null);
        AccessService accessService = context.getProxy().getAccessService();
        return getDeployments(folder, page -> accessService.filterForbidden(context, folder, page), context, resourceType, extractor);
    }

    public interface DeploymentExtractor {
        <T extends Deployment> T extract(ResourceDescriptor resource, ProxyContext context);

    }

    public List<String> getInterceptors(ProxyContext context, Deployment deployment) {
        List<String> result = new ArrayList<>(context.getConfig().getGlobalInterceptors());
        if (deployment instanceof Application application) {
            List<String> appTypeInterceptors = applicationSchemaService.getInterceptors(application);
            mergeInterceptors(appTypeInterceptors, result);
        }
        List<String> localInterceptors = deployment.getInterceptors();
        mergeInterceptors(localInterceptors, result);
        return result;
    }

    private static void mergeInterceptors(List<String> source, List<String> destination) {
        for (String interceptor : source) {
            if (!destination.contains(interceptor)) {
                destination.add(interceptor);
            }
        }
    }

}
