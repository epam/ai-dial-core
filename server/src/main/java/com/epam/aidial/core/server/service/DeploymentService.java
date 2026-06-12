package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ListSharedResourcesRequest;
import com.epam.aidial.core.server.data.SharedResourcesResponse;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static com.epam.aidial.core.storage.resource.ResourceTypes.APPLICATION;
import static com.epam.aidial.core.storage.resource.ResourceTypes.TOOL_SET;

@Slf4j
public class DeploymentService {

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
            case TOOL_SET -> toolSetService.getToolSet(deploymentDescriptor).getValue();
            default -> throw new IllegalArgumentException("Unknown resource type: " + resourceType);
        };
    }

    public  <T extends Deployment> List<T> listDeployments(ProxyContext context, ResourceTypes resourceType, DeploymentExtractor extractor) {
        List<T> deployments = new ArrayList<>();
        log.debug("Start private {} listing", resourceType.group());
        deployments.addAll(getPrivateDeployments(context, resourceType, extractor));
        log.debug("Finish private {} listing", resourceType.group());
        log.debug("Start shared {} listing", resourceType.group());
        deployments.addAll(getSharedDeployments(context, resourceType, extractor));
        log.debug("Finish shared {} listing", resourceType.group());
        log.debug("Start public {} listing", resourceType.group());
        deployments.addAll(getPublicDeployments(context, resourceType, extractor));
        log.debug("Finish public {} listing", resourceType.group());
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

        return resourceService.listResources(resource, filter)
                .stream().map(item -> {
                    try {
                        return extractor.<T>extract(item.getValue(), item.getKey(), ctx);
                    } catch (Exception e) {
                        log.warn("Can't extract deployment {} due to the error", item.getKey().getUrl(), e);
                        return null;
                    }
                }).filter(Objects::nonNull).toList();
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
        List<ResourceItemMetadata> deployments = metadata.stream()
                .filter(meta -> meta instanceof ResourceItemMetadata).map(meta -> (ResourceItemMetadata) meta).toList();
        List<Pair<ResourceItemMetadata, String>> deploymentContent = new ArrayList<>();
        resourceService.load(deployments, deploymentContent);
        for (Pair<ResourceItemMetadata, String> item : deploymentContent) {
            try {
                T deployment = extractor.extract(item.getValue(), item.getKey(), context);
                list.add(deployment);
            } catch (Exception e) {
                log.warn("Can't extract deployment {} due to the error", item.getKey().getUrl(), e);
            }
        }

        List<MetadataBase> folders = metadata.stream()
                .filter(meta -> meta instanceof ResourceFolderMetadata).toList();
        for (MetadataBase folder : folders) {
            ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(folder.getUrl(), encryptionService);
            list.addAll(getDeployments(resource, context, resourceType, extractor));
        }
        return list;
    }

    private <T extends  Deployment> List<T> getPublicDeployments(ProxyContext context, ResourceTypes resourceType, DeploymentExtractor extractor) {
        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(resourceType, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, null);
        AccessService accessService = context.getProxy().getAccessService();
        return getDeployments(folder, page -> accessService.filterForbidden(context, folder, page), context, resourceType, extractor);
    }

    public interface DeploymentExtractor {
        <T extends  Deployment> T extract(String content, ResourceItemMetadata metadata, ProxyContext context);
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
