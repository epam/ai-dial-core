package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.RoleBasedEntity;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.alias.AliasPass;
import com.epam.aidial.core.server.alias.AliasService;
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
import com.google.common.base.Suppliers;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import static com.epam.aidial.core.storage.resource.ResourceTypes.ALIAS;
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
            case ALIAS -> findStoredAlias(context, deploymentDescriptor, id);
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
     * The alias stored at {@code resource}, called by its url {@code aliases/public/<name>}: read from storage on
     * demand, as a public application is, and allowed by its {@code userRoles} like an alias of the config file.
     */
    private Alias findStoredAlias(ProxyContext context, ResourceDescriptor resource, String id) {
        Alias alias = aliasService.findStoredAlias(resource);
        if (alias == null) {
            throw new ResourceNotFoundException("Unknown deployment: " + id);
        }
        if (Boolean.FALSE.equals(aliasService.hasConfigAccess(context, alias)) && !isLent(context, alias)) {
            throwForbiddenDeploymentError(id);
        }
        return alias;
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
     * The listing views of the aliases shown as {@code presentation} that the caller may use (by their
     * {@code userRoles}, or the target's): those of the config file and those stored in {@code aliases/public/}. An alias whose target is
     * missing or does not fit is left out. Lists and reads the stored aliases and reads a resource target, so it runs
     * on the task executor.
     */
    public List<Deployment> listAliasViews(ProxyContext context, AliasPresentation presentation) {
        return listAliasViews(context, presentation, this::listStoredAliasesOrNone);
    }

    /**
     * {@link #listAliasViews} with the stored aliases from {@code stored}, so that a request listing several types reads
     * {@code aliases/public/} once ({@link #storedAliasesOnce}).
     */
    public List<Deployment> listAliasViews(ProxyContext context, AliasPresentation presentation, Supplier<List<Alias>> stored) {
        List<Deployment> views = new ArrayList<>();
        for (Alias alias : listUsableAliases(context, presentation, stored)) {
            Deployment view = aliasService.toView(context, alias);
            if (view != null && view.hasAccess(context.getUserRoles())) {
                views.add(view);
            }
        }
        return views;
    }

    /**
     * The names of the aliases {@link #listAliasViews} lists.
     */
    public List<RoleBasedEntity> listAliasNames(ProxyContext context, AliasPresentation presentation, Supplier<List<Alias>> stored) {
        List<RoleBasedEntity> names = new ArrayList<>();
        for (Alias alias : listUsableAliases(context, presentation, stored)) {
            Deployment view = aliasService.toView(context, alias);
            if (view != null && view.hasAccess(context.getUserRoles())) {
                names.add(alias);
            }
        }
        return names;
    }

    /**
     * The stored aliases, listed and read at the first call only: one listing of {@code aliases/public/} for all the
     * types one request lists. Safe to call from the concurrent tasks of that request.
     */
    public Supplier<List<Alias>> storedAliasesOnce() {
        return Suppliers.memoize(this::listStoredAliasesOrNone);
    }

    /**
     * The stored aliases, or none when storage cannot list or read them: a listing then shows the config deployments
     * and config aliases rather than failing as a whole.
     */
    private List<Alias> listStoredAliasesOrNone() {
        try {
            return aliasService.listStoredAliases();
        } catch (RuntimeException e) {
            log.warn("Can't list the stored aliases; listing without them", e);
            return List.of();
        }
    }

    private List<Alias> listUsableAliases(ProxyContext context, AliasPresentation presentation, Supplier<List<Alias>> stored) {
        List<Alias> usable = new ArrayList<>();
        List<Alias> aliases = new ArrayList<>(context.getConfig().getAliases().values());
        aliases.addAll(stored.get());
        for (Alias alias : aliases) {
            if (alias.getEffectivePresentation() == presentation && !Boolean.FALSE.equals(aliasService.hasConfigAccess(context, alias))) {
                usable.add(alias);
            }
        }
        return usable;
    }

    /**
     * The listing view of the alias {@code id} (a config alias's name or a stored alias's url) when it is shown as
     * {@code presentation}, or null when there is none or its target is missing. Throws
     * {@link PermissionDeniedException} when the caller may not use the alias. May read storage.
     */
    @Nullable
    public Deployment findAliasView(ProxyContext context, String id, AliasPresentation presentation) {
        Alias alias = context.getConfig().getAliases().get(id);
        if (alias == null) {
            ResourceDescriptor resource = toStoredAliasResource(id);
            alias = resource == null ? null : aliasService.findStoredAlias(resource);
        }
        if (alias == null || alias.getEffectivePresentation() != presentation) {
            return null;
        }
        if (Boolean.FALSE.equals(aliasService.hasConfigAccess(context, alias))) {
            throwForbiddenDeploymentError(id);
        }
        return aliasService.toView(context, alias);
    }

    /**
     * The alias a listing entry named {@code name} stands for: a config alias by its name, or a stored alias by its
     * url; null when there is none. May read storage.
     */
    @Nullable
    public Alias findListedAlias(ProxyContext context, String name) {
        Alias alias = context.getConfig().getAliases().get(name);
        if (alias != null) {
            return alias;
        }
        ResourceDescriptor resource = toStoredAliasResource(name);
        return resource == null ? null : aliasService.findStoredAlias(resource);
    }

    /**
     * Whether {@code id} names a stored alias by its url, {@code aliases/<bucket>/<path>}: the ids a deployment listing
     * by id must look up in storage rather than in the config.
     */
    public static boolean isStoredAliasId(String id) {
        return id.startsWith(ResourceTypes.ALIAS.group() + ResourceDescriptor.PATH_SEPARATOR);
    }

    @Nullable
    private ResourceDescriptor toStoredAliasResource(String id) {
        if (!isStoredAliasId(id)) {
            return null;
        }
        try {
            return ResourceDescriptorFactory.fromAnyUrl(UrlUtil.encodePath(id), encryptionService);
        } catch (RuntimeException e) {
            return null;
        }
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

    /**
     * Whether an administrator may approve the application: one from the config, in the public or a review bucket, or in
     * the administrator's own. The right to read it is not enough: its owner or sharer keeps control of it.
     */
    public boolean isApprovableLocation(ProxyContext context, String deploymentId) {
        if (context.getConfig().selectDeployment(deploymentId) != null) {
            return true;
        }
        ResourceDescriptor resource = toResourceDescriptor(context, deploymentId);
        return resource.isPublic() || PublicationService.isReviewBucket(resource)
                || accessService.isOwnerOf(context, resource.getBucketName());
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
        // aliases live directly in aliases/public/ only: another bucket or a folder holds no alias
        if (resource.getType() == ALIAS && !AliasService.isStoredAliasLocation(resource)) {
            throw new ResourceNotFoundException("Unknown deployment: " + resourceUrl);
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
