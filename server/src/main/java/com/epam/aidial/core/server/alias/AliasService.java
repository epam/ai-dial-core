package com.epam.aidial.core.server.alias;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

/**
 * Resolves the target of an alias, and builds the deployment that serves a call of it ({@link AliasPasses#build}) or
 * a listing shows ({@link AliasPasses#view}). Aliases are managed by admins, so an alias grants every caller who may
 * use it the right to call its target; nothing about the caller's own rights on the target is checked. A resource
 * target is read from storage, so the methods that may resolve one run on the task executor.
 */
@Slf4j
public class AliasService {

    private final ApplicationService applicationService;
    private final ToolSetService toolSetService;
    private final ApplicationSchemaService applicationSchemaService;
    private final EncryptionService encryptionService;

    // Core's own address, which an alias pass forwards to; set once the server listens
    private volatile String localBaseUrl;

    public AliasService(ApplicationService applicationService, ToolSetService toolSetService,
                        ApplicationSchemaService applicationSchemaService, EncryptionService encryptionService) {
        this.applicationService = applicationService;
        this.toolSetService = toolSetService;
        this.applicationSchemaService = applicationSchemaService;
        this.encryptionService = encryptionService;
    }

    public void setLocalBaseUrl(String localBaseUrl) {
        this.localBaseUrl = localBaseUrl;
    }

    /**
     * The target of an alias: the deployment, the name it is called by (a config name or a resource url), and whether
     * it is a resource.
     */
    private record Target(Deployment deployment, String name, boolean resource) {
    }

    /**
     * The deployment serving one call of {@code alias}. Answers 503 when the target is missing or does not fit the
     * alias.
     */
    public Deployment toPass(ProxyContext context, Alias alias) {
        AliasPresentation presentation = alias.getEffectivePresentation();
        Target target = resolveTarget(context, alias, false);
        if (target == null || !AliasTargets.fits(presentation, target.deployment())) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "The target of alias %s is not available".formatted(alias.getName()));
        }
        return AliasPasses.build(alias, presentation, target.deployment(), target.name(), target.resource(), localBaseUrl);
    }

    /**
     * The deployment listings show for {@code alias}, or null when its target is missing or does not fit.
     */
    @Nullable
    public Deployment toView(ProxyContext context, Alias alias) {
        return toView(context, alias, false);
    }

    /**
     * {@link #toView}, reading no storage when {@code inMemory}, for the listings that answer on the event loop: then
     * an alias to a resource is left out, and an application target is taken as configured, without resolving its
     * schema.
     */
    @Nullable
    public Deployment toView(ProxyContext context, Alias alias, boolean inMemory) {
        AliasPresentation presentation = alias.getEffectivePresentation();
        Target target;
        try {
            target = resolveTarget(context, alias, inMemory);
        } catch (RuntimeException e) {
            log.warn("Can't resolve the target of alias {}: {}", alias.getName(), e.getMessage());
            return null;
        }
        if (target == null || !AliasTargets.fits(presentation, target.deployment())) {
            return null;
        }
        return AliasPasses.view(alias, presentation, target.deployment());
    }

    @Nullable
    private Target resolveTarget(ProxyContext context, Alias alias, boolean inMemory) {
        String path = alias.getTarget().getPath();
        if (AliasTargets.isResourcePath(path)) {
            if (inMemory) {
                return null;
            }
            ResourceDescriptor resource = toTargetResource(path);
            Deployment deployment;
            try {
                deployment = loadResource(resource);
            } catch (ResourceNotFoundException e) {
                return null;
            }
            return new Target(deployment, resource.getUrl(), true);
        }
        AliasTargets.TargetRef ref = AliasTargets.parse(path);
        Deployment deployment = ref == null ? null : AliasTargets.resolve(context.getConfig(), ref);
        if (deployment == null) {
            return null;
        }
        if (!inMemory && deployment instanceof Application application) {
            deployment = resolveSchema(application);
        }
        return new Target(deployment, deployment.getName(), false);
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
