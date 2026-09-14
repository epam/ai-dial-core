package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.RoleBasedEntity;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ListSharedResourcesRequest;
import com.epam.aidial.core.server.data.SharedResourcesResponse;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
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
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.epam.aidial.core.storage.resource.ResourceTypes.APPLICATION;
import static com.epam.aidial.core.storage.resource.ResourceTypes.TOOL_SET;

@Slf4j
public class DeploymentService {

    private static final int NAME_LISTING_PAGE_SIZE = 1000;

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

    /**
     * Lists the caller's accessible deployments by name only - one {@code entitySupplier}-built
     * {@link RoleBasedEntity} per resource, carrying just the resource url as its name, never a fully
     * populated {@link Deployment}. Unlike {@link #listDeployments}, this never reads a resource's
     * content: a caller wanting only a name (e.g. to key a rate-limit lookup) would otherwise pay for
     * parsing every accessible resource's full body - and for an Application, resolving its
     * schema/mcp/viewerUrl on top - none of which the name alone needs. The service stays agnostic to
     * which concrete {@link RoleBasedEntity} the caller wants back; {@code entitySupplier} is invoked
     * once per listed resource, never shared/reused across entries.
     */
    public List<RoleBasedEntity> listDeploymentNames(
            ProxyContext context, ResourceTypes resourceType, Supplier<? extends RoleBasedEntity> entitySupplier) {
        List<RoleBasedEntity> names = new ArrayList<>();
        log.debug("Start private {} name listing", resourceType.group());
        names.addAll(getPrivateDeploymentNames(context, resourceType, entitySupplier));
        log.debug("Finish private {} name listing", resourceType.group());
        log.debug("Start shared {} name listing", resourceType.group());
        names.addAll(getSharedDeploymentNames(context, resourceType, entitySupplier));
        log.debug("Finish shared {} name listing", resourceType.group());
        log.debug("Start public {} name listing", resourceType.group());
        names.addAll(getPublicDeploymentNames(context, resourceType, entitySupplier));
        log.debug("Finish public {} name listing", resourceType.group());
        return names;
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

        List<Pair<ResourceItemMetadata, String>> items = resourceService.listResources(resource, filter);
        return extractor.<T>extract(items, ctx);
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
        list.addAll(extractor.<T>extract(deploymentContent, context));

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

    private List<RoleBasedEntity> getPrivateDeploymentNames(
            ProxyContext context, ResourceTypes resourceType, Supplier<? extends RoleBasedEntity> entitySupplier) {
        String location = BucketBuilder.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(location);

        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(resourceType, bucket, location, null);
        return getDeploymentNames(folder, ignore -> { }, entitySupplier);
    }

    private List<RoleBasedEntity> getDeploymentNames(
            ResourceDescriptor resource, Consumer<ResourceFolderMetadata> filter, Supplier<? extends RoleBasedEntity> entitySupplier) {
        if (!resource.isFolder()) {
            throw new IllegalArgumentException("Invalid deployment folder: " + resource.getUrl());
        }

        List<RoleBasedEntity> names = new ArrayList<>();
        String token = null;
        do {
            ResourceFolderMetadata page = resourceService.getFolderMetadata(resource, token, NAME_LISTING_PAGE_SIZE, true);
            if (page == null) {
                break;
            }
            filter.accept(page);
            for (MetadataBase item : page.getItems()) {
                if (item.getNodeType() == NodeType.ITEM) {
                    names.add(toRoleBasedEntity(item, entitySupplier));
                }
            }
            token = page.getNextToken();
        } while (token != null);
        return names;
    }

    private List<RoleBasedEntity> getSharedDeploymentNames(
            ProxyContext context, ResourceTypes resourceType, Supplier<? extends RoleBasedEntity> entitySupplier) {
        String location = BucketBuilder.buildInitiatorBucket(context);
        String bucket = encryptionService.encrypt(location);

        ListSharedResourcesRequest request = new ListSharedResourcesRequest();
        request.setResourceTypes(Set.of(resourceType));

        ShareService shares = context.getProxy().getShareService();
        SharedResourcesResponse response = shares.listSharedWithMe(bucket, location, request);
        Set<MetadataBase> metadata = response.getResources();

        List<RoleBasedEntity> names = new ArrayList<>();
        for (MetadataBase meta : metadata) {
            if (meta instanceof ResourceItemMetadata item) {
                names.add(toRoleBasedEntity(item, entitySupplier));
            }
        }
        for (MetadataBase meta : metadata) {
            if (meta instanceof ResourceFolderMetadata) {
                ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(meta.getUrl(), encryptionService);
                names.addAll(getDeploymentNames(resource, ignore -> { }, entitySupplier));
            }
        }
        return names;
    }

    private List<RoleBasedEntity> getPublicDeploymentNames(
            ProxyContext context, ResourceTypes resourceType, Supplier<? extends RoleBasedEntity> entitySupplier) {
        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(resourceType, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, null);
        AccessService accessService = context.getProxy().getAccessService();
        return getDeploymentNames(folder, page -> accessService.filterForbidden(context, folder, page), entitySupplier);
    }

    /**
     * A bare {@code entitySupplier}-built shell carrying only the resource url as its name - the one
     * field a rate-limit lookup keys on. The supplier is the caller's choice, so this stays agnostic to
     * which concrete {@link RoleBasedEntity} kind is being listed (e.g. an {@link Application} shell,
     * left with a {@code null} {@code userRoles} - "applies to all" - matching what a real custom
     * application would carry: {@code prepareApplication} always clears it, so no resource-based
     * Application is ever actually restricted by it).
     */
    private static RoleBasedEntity toRoleBasedEntity(MetadataBase item, Supplier<? extends RoleBasedEntity> entitySupplier) {
        RoleBasedEntity shell = entitySupplier.get();
        shell.setName(item.getUrl());
        return shell;
    }

    public interface DeploymentExtractor {
        <T extends  Deployment> List<T> extract(List<Pair<ResourceItemMetadata, String>> items, ProxyContext context);
    }

    public List<String> getInterceptors(ProxyContext context, Deployment deployment, InterfaceType requestedInterface) {
        List<String> result = new ArrayList<>(context.getConfig().getGlobalInterceptors());
        if (deployment instanceof Application application) {
            List<String> appTypeInterceptors = applicationSchemaService.getInterceptors(application);
            mergeInterceptors(appTypeInterceptors, result);
        }
        List<String> localInterceptors = deployment.getInterceptors();
        mergeInterceptors(localInterceptors, result);
        return filterByInterface(context, result, requestedInterface);
    }

    private static void mergeInterceptors(List<String> source, List<String> destination) {
        for (String interceptor : source) {
            if (!destination.contains(interceptor)) {
                destination.add(interceptor);
            }
        }
    }

    /**
     * Drops interceptors that don't serve the requested interface, e.g. an interceptor with only a
     * {@code responsesEndpoint} is skipped for a chat completions request. An unresolvable interceptor name is
     * kept as-is - that's a "not found" case handled downstream, not an "unsupported interface" one.
     */
    private static List<String> filterByInterface(ProxyContext context, List<String> interceptorNames, InterfaceType requestedInterface) {
        Map<String, Interceptor> interceptors = context.getConfig().getInterceptors();
        Map<String, Translator> translators = context.getConfig().getTranslators();
        List<String> result = new ArrayList<>(interceptorNames.size());
        for (String name : interceptorNames) {
            Interceptor interceptor = interceptors.get(name);
            if (interceptor != null
                    && DeploymentEndpointUtil.resolveServingEndpoint(interceptor, requestedInterface, translators) == null) {
                continue;
            }
            result.add(name);
        }
        return result;
    }

}
