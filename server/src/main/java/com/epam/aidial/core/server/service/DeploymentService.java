package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.RoleBasedEntity;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.alias.AliasPass;
import com.epam.aidial.core.server.alias.AliasService;
import com.epam.aidial.core.server.alias.AliasTargets;
import com.epam.aidial.core.server.data.ApiKeyData;
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
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;

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

    private final AliasService aliasService;

    public DeploymentService(EncryptionService encryptionService, ApplicationService applicationService,
                             AccessService accessService, ToolSetService toolSetService, ResourceService resourceService,
                             ApplicationSchemaService applicationSchemaService, AliasService aliasService) {
        this.encryptionService = encryptionService;
        this.applicationService = applicationService;
        this.toolSetService = toolSetService;
        this.accessService = accessService;
        this.resourceService = resourceService;
        this.applicationSchemaService = applicationSchemaService;
        this.aliasService = aliasService;
    }

    public Deployment findDeployment(ProxyContext context, String id) {
        Deployment deployment = context.getConfig().selectDeployment(id);
        if (deployment != null) {
            if (!hasAccess(context, deployment) && !isLent(context, deployment)) {
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

    /**
     * Whether the caller may use a config deployment by its {@code userRoles}. An alias without {@code userRoles} takes
     * those of its config target; when its target is a resource, or missing, it is let through here, and checked when
     * the target is resolved ({@link AliasService#toPass}, {@link AliasService#toView}).
     */
    private boolean hasAccess(ProxyContext context, Deployment deployment) {
        if (deployment instanceof Alias alias) {
            return !Boolean.FALSE.equals(aliasService.hasConfigAccess(context, alias));
        }
        return deployment.hasAccess(context.getUserRoles());
    }

    /**
     * {@link #findDeployment} for a request that calls the deployment: an {@link Alias} becomes the pass that forwards
     * the call to its target, see {@link AliasService#toPass}.
     */
    public Deployment findCallableDeployment(ProxyContext context, String id) {
        Deployment deployment = findDeployment(context, id);
        return deployment instanceof Alias alias ? aliasService.toPass(context, alias) : deployment;
    }

    /**
     * Whether the request is the target pass of an alias call for {@code deployment}: its per-request key was made by
     * the alias pass, which lends the right to call the target. The key is kept by Core and sent only to Core itself.
     */
    public static boolean isLent(ProxyContext context, RoleBasedEntity deployment) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        String lentDeployment = apiKeyData.getLentDeployment();
        return apiKeyData.getPerRequestKey() != null && lentDeployment != null && lentDeployment.equals(deployment.getName());
    }

    /**
     * The listing views of the config aliases shown as {@code presentation} that the caller may call. Reads no
     * storage for an alias to a config deployment. An alias whose target is missing or does not fit is left out.
     */
    public List<Deployment> listAliasViews(ProxyContext context, AliasPresentation presentation) {
        return listConfigAliasViews(context, presentation, true);
    }

    private List<Deployment> listConfigAliasViews(ProxyContext context, AliasPresentation presentation, boolean inMemory) {
        List<Deployment> views = new ArrayList<>();
        for (Alias alias : context.getConfig().getAliases().values()) {
            if (alias.getEffectivePresentation() == presentation && !Boolean.FALSE.equals(aliasService.hasConfigAccess(context, alias))) {
                Deployment view = aliasService.toView(context, alias, inMemory);
                if (view != null && view.hasAccess(context.getUserRoles())) {
                    views.add(view);
                }
            }
        }
        return views;
    }

    /**
     * {@link #listAliasViews} with each application target's schema resolved, for the listings on the task executor.
     */
    public List<Deployment> listAllAliasViews(ProxyContext context, AliasPresentation presentation) {
        return listConfigAliasViews(context, presentation, false);
    }

    /**
     * The names of the aliases shown as {@code presentation} the caller may call, leaving out those whose config target
     * is missing or no longer fits. A resource target is not read, so an alias to a missing resource is named, unless
     * the alias takes its access from it: then the caller's read access to it is checked. Runs on the task executor.
     */
    public List<RoleBasedEntity> listAliasNames(ProxyContext context, AliasPresentation presentation) {
        List<RoleBasedEntity> names = new ArrayList<>();
        for (Alias alias : context.getConfig().getAliases().values()) {
            if (alias.getEffectivePresentation() == presentation
                    && !AliasTargets.isTargetMissing(alias, context.getConfig())
                    && !AliasTargets.isTargetIncompatible(alias, context.getConfig())
                    && hasNameAccess(context, alias)) {
                names.add(alias);
            }
        }
        return names;
    }

    private boolean hasNameAccess(ProxyContext context, Alias alias) {
        Boolean access = aliasService.hasConfigAccess(context, alias);
        return access != null ? access : aliasService.toView(context, alias) != null;
    }

    /**
     * The listing view of the config alias named {@code id} when it is shown as {@code presentation}, or null. The
     * caller checks access on the view, which carries the alias's {@code userRoles}.
     */
    @Nullable
    public Deployment findAliasView(ProxyContext context, String id, AliasPresentation presentation) {
        Alias alias = context.getConfig().getAliases().get(id);
        if (alias == null || alias.getEffectivePresentation() != presentation) {
            return null;
        }
        return aliasService.toView(context, alias, true);
    }

    /**
     * The listing view of an alias {@link #findDeployment} returned, or null when its target is missing.
     */
    @Nullable
    public Deployment toAliasView(ProxyContext context, Alias alias) {
        return aliasService.toView(context, alias);
    }

    public AliasService getAliasService() {
        return aliasService;
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

    public List<String> getInterceptors(ProxyContext context, Deployment deployment) {
        if (deployment instanceof AliasPass) {
            // global and target interceptors run in the target pass, once
            return List.of();
        }
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
