package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Resolves the target of an alias, and builds the deployment that serves a call of it ({@link AliasPasses#build}) or
 * a listing shows ({@link AliasPasses#view}). Aliases are managed by admins, so an alias grants every caller who may
 * use it the right to call its target. Who may use it is set by the alias's {@code userRoles}; an alias without them
 * takes the target's: the {@code userRoles} of a config target, or read access to a resource target. A resource
 * target is read from storage, so the methods that may resolve one run on the task executor.
 */
@Slf4j
public class AliasService {

    private final ApplicationService applicationService;
    private final ToolSetService toolSetService;
    private final ApplicationSchemaService applicationSchemaService;
    private final EncryptionService encryptionService;
    private final ResourceService resourceService;
    private final AccessService accessService;

    // Core's own address, which an alias pass forwards to; set once the server listens
    private volatile String localBaseUrl;

    public AliasService(ApplicationService applicationService, ToolSetService toolSetService,
                        ApplicationSchemaService applicationSchemaService, EncryptionService encryptionService,
                        ResourceService resourceService, AccessService accessService) {
        this.applicationService = applicationService;
        this.toolSetService = toolSetService;
        this.applicationSchemaService = applicationSchemaService;
        this.encryptionService = encryptionService;
        this.accessService = accessService;
        this.resourceService = resourceService;
    }

    /**
     * Whether {@code resource} is where an alias is stored: an item directly in {@code aliases/public/} or
     * {@code aliases/platform/}.
     */
    public static boolean isStoredAliasLocation(ResourceDescriptor resource) {
        return resource.getType() == ResourceTypes.ALIAS
                && (ResourceDescriptor.PUBLIC_BUCKET.equals(resource.getBucketName())
                        || ResourceDescriptor.PLATFORM_BUCKET.equals(resource.getBucketName()))
                && !resource.isFolder()
                && resource.getParentPath() == null;
    }

    /**
     * The alias stored at {@code resource}, named by its url ({@code aliases/public/<name>} or
     * {@code aliases/platform/<name>}), or null when the resource is not directly in one of those folders, does not
     * exist, or cannot be read as an alias. Reads storage (through the resource cache), so it runs on the task
     * executor.
     */
    @Nullable
    public Alias findStoredAlias(ResourceDescriptor resource) {
        if (!isStoredAliasLocation(resource)) {
            return null;
        }
        String body = resourceService.getResource(resource);
        return body == null ? null : parseStored(resource, body);
    }

    /**
     * The aliases stored directly in {@code aliases/public/} and {@code aliases/platform/}, each named by its url; a
     * body that cannot be read as an alias is logged and left out. One folder listing and one read per alias, so it
     * runs on the task executor.
     */
    public List<Alias> listStoredAliases() {
        List<Alias> aliases = new ArrayList<>();
        for (String bucket : List.of(ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PLATFORM_BUCKET)) {
            ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(ResourceTypes.ALIAS,
                    bucket, bucket + ResourceDescriptor.PATH_SEPARATOR, null);
            for (Pair<ResourceItemMetadata, String> item : resourceService.listResources(folder, ignored -> { })) {
                ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(item.getKey().getUrl(), encryptionService);
                if (item.getValue() == null || !isStoredAliasLocation(resource)) {
                    continue;
                }
                Alias alias = parseStored(resource, item.getValue());
                if (alias != null) {
                    aliases.add(alias);
                }
            }
        }
        return aliases;
    }

    @Nullable
    private static Alias parseStored(ResourceDescriptor resource, String body) {
        StoredAliases.Stored stored = StoredAliases.parse(body);
        if (stored.alias() == null) {
            log.warn("Ignoring the stored alias {}: {}", resource.getUrl(), stored.warnings());
            return null;
        }
        stored.alias().setName(resource.getUrl());
        return stored.alias();
    }

    public void setLocalBaseUrl(String localBaseUrl) {
        this.localBaseUrl = localBaseUrl;
    }

    /**
     * The target of an alias: the deployment, the name it is called by (a config name or a resource url, encoded), and
     * the resource it is read from, null for a config target.
     */
    private record Target(Deployment deployment, String name, @Nullable ResourceDescriptor resource) {

        boolean isResource() {
            return resource != null;
        }
    }

    /**
     * The toolset an alias points to, for signing in to it through the alias: the toolset, and the id credentials are
     * stored under, the decoded url of a resource or the name of a config toolset.
     */
    public record SecuredTarget(ToolSet toolSet, String id, boolean resource) {
    }

    /**
     * Why the target of an alias cannot serve it.
     */
    public enum TargetProblem {
        MISSING, INCOMPATIBLE
    }

    /**
     * Whether the caller may use {@code alias}, as far as the config tells: by the alias's {@code userRoles}, or, when
     * it has none, by those of its config target. Null when only the target can tell, which the methods resolving it
     * check: the alias has no {@code userRoles} and its target is a resource or missing from the config.
     */
    @Nullable
    public Boolean hasConfigAccess(ProxyContext context, Alias alias) {
        if (alias.getUserRoles() != null) {
            return alias.hasAccess(context.getUserRoles());
        }
        String path = alias.getTarget().getPath();
        if (AliasTargets.isResourcePath(path)) {
            return null;
        }
        AliasTargets.TargetRef ref = AliasTargets.parse(path);
        Deployment target = ref == null ? null : AliasTargets.resolve(context.getConfig(), ref);
        return target == null ? null : target.hasAccess(context.getUserRoles());
    }

    /**
     * Whether the caller may use {@code alias}, whose target is resolved: by the alias's {@code userRoles}, or else by
     * the target's {@code userRoles} or the caller's read access to the target resource.
     */
    private boolean hasAccess(ProxyContext context, Alias alias, Target target) {
        if (alias.getUserRoles() != null) {
            return alias.hasAccess(context.getUserRoles());
        }
        if (target.isResource()) {
            return accessService.hasReadAccess(target.resource(), context);
        }
        return target.deployment().hasAccess(context.getUserRoles());
    }

    /**
     * The deployment serving one call of {@code alias}. Answers 503 when the target is missing or does not fit the
     * alias, and 403 when the caller may not use the alias.
     */
    public Deployment toPass(ProxyContext context, Alias alias) {
        Target target = resolveUsableTarget(context, alias);
        return AliasPasses.build(alias, alias.getEffectivePresentation(), target.deployment(), target.name(), target.isResource(), localBaseUrl);
    }

    /**
     * The deployment that serves {@code alias}, as its target pass finds it, under the name that pass runs by. Answers
     * like {@link #toPass}.
     */
    public Deployment findTarget(ProxyContext context, Alias alias) {
        return resolveUsableTarget(context, alias).deployment();
    }

    /**
     * The deployment the target path of {@code alias} names in {@code config} or in storage, or null when there is
     * none. Answers 503 when the path cannot name a resource Core can read. May read storage.
     */
    @Nullable
    public Deployment findTarget(Config config, Alias alias) {
        Target target = resolveTarget(config, alias);
        return target == null ? null : target.deployment();
    }

    private Target resolveUsableTarget(ProxyContext context, Alias alias) {
        Target target = resolveTarget(context.getConfig(), alias);
        if (target == null || !AliasTargets.fits(alias.getEffectivePresentation(), target.deployment())) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "The target of alias %s is not available".formatted(alias.getName()));
        }
        if (!hasAccess(context, alias, target)) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden deployment: " + alias.getName());
        }
        return target;
    }

    /**
     * The toolset {@code alias} points to, for signing in to it through the alias. Answers 404 when the alias is not a
     * toolset alias to a toolset (an application has no toolset sign-in), 503 when the target is missing, and 403 when
     * the caller may not use the alias.
     */
    public SecuredTarget toSecuredTarget(ProxyContext context, Alias alias) {
        if (alias.getEffectivePresentation() != AliasPresentation.TOOLSET) {
            throw new ResourceNotFoundException("Resource is not found: " + alias.getName());
        }
        Target target = resolveTarget(context.getConfig(), alias);
        if (target == null) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "The target of alias %s is not available".formatted(alias.getName()));
        }
        if (!(target.deployment() instanceof ToolSet toolSet)) {
            throw new ResourceNotFoundException("Resource is not found: " + alias.getName());
        }
        if (!hasAccess(context, alias, target)) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden deployment: " + alias.getName());
        }
        String id = target.isResource() ? UrlUtil.decodePath(target.name()) : target.name();
        return new SecuredTarget(toolSet, id, target.isResource());
    }

    /**
     * The id {@code alias}'s toolset target stores credentials under, as {@link #toSecuredTarget} gives it, without
     * reading the target: for the auth statuses of a listing entry.
     */
    public String securedTargetId(Alias alias) {
        String path = alias.getTarget().getPath();
        if (AliasTargets.isResourcePath(path)) {
            return UrlUtil.decodePath(toTargetResource(path).getUrl());
        }
        AliasTargets.TargetRef ref = AliasTargets.parse(path);
        return ref == null ? path : ref.name();
    }

    /**
     * Why {@code alias}'s target cannot serve it, reading a resource target: {@link TargetProblem#MISSING} when it
     * does not exist or cannot be read, {@link TargetProblem#INCOMPATIBLE} when it does not fit the alias's type; null
     * when it can. For the health report, on the task executor.
     */
    @Nullable
    public TargetProblem checkTarget(ProxyContext context, Alias alias) {
        Target target;
        try {
            target = resolveTarget(context.getConfig(), alias);
        } catch (RuntimeException e) {
            log.warn("Can't resolve the target of alias {}: {}", alias.getName(), e.getMessage());
            return TargetProblem.MISSING;
        }
        if (target == null) {
            return TargetProblem.MISSING;
        }
        return AliasTargets.fits(alias.getEffectivePresentation(), target.deployment()) ? null : TargetProblem.INCOMPATIBLE;
    }

    /**
     * The deployment listings show for {@code alias}, or null when its target is missing or does not fit. The view
     * carries the roles that may use the alias, its own or a config target's, for the caller to check; an alias that
     * takes its access from a resource target is checked here, and left out when the caller cannot read the target.
     */
    @Nullable
    public Deployment toView(ProxyContext context, Alias alias) {
        AliasPresentation presentation = alias.getEffectivePresentation();
        Target target;
        try {
            target = resolveTarget(context.getConfig(), alias);
        } catch (RuntimeException e) {
            log.warn("Can't resolve the target of alias {}: {}", alias.getName(), e.getMessage());
            return null;
        }
        if (target == null || !AliasTargets.fits(presentation, target.deployment())) {
            return null;
        }
        if (alias.getUserRoles() == null && target.isResource()) {
            try {
                if (!accessService.hasReadAccess(target.resource(), context)) {
                    return null;
                }
            } catch (RuntimeException e) {
                log.warn("Can't check access to the target of alias {}: {}", alias.getName(), e.getMessage());
                return null;
            }
        }
        Deployment view = AliasPasses.view(alias, presentation, target.deployment());
        if (alias.getUserRoles() == null && !target.isResource()) {
            view.setUserRoles(target.deployment().getUserRoles());
        }
        return view;
    }

    @Nullable
    private Target resolveTarget(Config config, Alias alias) {
        String path = alias.getTarget().getPath();
        if (AliasTargets.isResourcePath(path)) {
            ResourceDescriptor resource = toTargetResource(path);
            Deployment deployment;
            try {
                deployment = loadResource(resource);
            } catch (ResourceNotFoundException e) {
                return null;
            }
            return new Target(deployment, resource.getUrl(), resource);
        }
        AliasTargets.TargetRef ref = AliasTargets.parse(path);
        Deployment deployment = ref == null ? null : AliasTargets.resolve(config, ref);
        if (deployment == null) {
            return null;
        }
        if (deployment instanceof Application application) {
            deployment = resolveSchema(application);
        }
        return new Target(deployment, deployment.getName(), null);
    }

    private Deployment loadResource(ResourceDescriptor resource) {
        if (resource.getType() == ResourceTypes.APPLICATION) {
            return resolveSchema(applicationService.getApplication(resource).getValue());
        }
        return toolSetService.getToolSet(resource).getValue();
    }

    /**
     * The application as its schema completes it: endpoints, and the {@code mcp} and {@code viewerUrl} that a
     * schema-based application gets only from its schema. Never changes {@code application}, which may be shared.
     */
    private Application resolveSchema(Application application) {
        Application resolved = applicationSchemaService.modifyEndpointsForCustomApplication(application);
        if (!application.hasApplicationTypeSchemaId()) {
            return resolved;
        }
        if (resolved == application) {
            resolved = new Application(application);
        }
        resolved.setMcp(applicationSchemaService.getMcp(application));
        resolved.setViewerUrl(applicationSchemaService.getStringProperty(application, MetaSchemaHolder.APPLICATION_TYPE_VIEWER_URL));
        return resolved;
    }

    /**
     * The resource a target path names. A path the alias validation accepted names an application or a toolset; a
     * folder or an unreadable bucket answers 503 like a missing target.
     */
    private ResourceDescriptor toTargetResource(String path) {
        ResourceDescriptor resource;
        try {
            resource = ResourceDescriptorFactory.fromAnyUrl(path, encryptionService);
        } catch (RuntimeException e) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid alias target: " + path);
        }
        if (resource.isFolder() || (resource.getType() != ResourceTypes.APPLICATION && resource.getType() != ResourceTypes.TOOL_SET)) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "The target of an alias must be an application or a toolset: " + path);
        }
        return resource;
    }
}
