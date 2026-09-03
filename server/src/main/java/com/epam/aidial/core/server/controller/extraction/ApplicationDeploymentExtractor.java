package com.epam.aidial.core.server.controller.extraction;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class ApplicationDeploymentExtractor implements DeploymentService.DeploymentExtractor {
    private final AccessService accessService;
    private final ApplicationService applicationService;
    private final ApplicationSchemaService applicationSchemaService;

    // Accumulated across every prepareBatch() call for this extractor instance (one call per folder:
    // private, each shared folder, public) so extract() - and any other write-access check for the same
    // listing request, see getBatchedPermissions() - never has to re-derive write access (and re-hit
    // Redis for rules/shared-with-me state) for an item that's already been resolved.
    private final Map<ResourceDescriptor, Set<ResourceAccessType>> batchPermissions = new HashMap<>();

    public ApplicationDeploymentExtractor(AccessService accessService, ApplicationService applicationService, ApplicationSchemaService applicationSchemaService) {
        this.accessService = accessService;
        this.applicationService = applicationService;
        this.applicationSchemaService = applicationSchemaService;
    }

    @Override
    public void prepareBatch(List<ResourceItemMetadata> items, ProxyContext context) {
        Set<ResourceDescriptor> resources = items.stream()
                .map(ResourceItemMetadata::getDescriptor)
                .collect(Collectors.toUnmodifiableSet());
        batchPermissions.putAll(accessService.lookupPermissions(resources, context));
    }

    /**
     * Permissions accumulated so far by {@link #prepareBatch}, keyed by resource. Read-only view for
     * callers (e.g. {@code ApplicationController}) that want to reuse a listing request's already-batched
     * permissions instead of computing their own for a resource that's already in this map - and to know
     * when a resource ISN'T in it, so they can fall back to a real lookup (e.g. for a single-resource
     * fetch that never went through {@link #prepareBatch}) instead of silently treating it as "no access".
     */
    public Map<ResourceDescriptor, Set<ResourceAccessType>> getBatchedPermissions() {
        return batchPermissions;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Application extract(String content, ResourceItemMetadata metadata, ProxyContext context) {
        ResourceDescriptor resource = metadata.getDescriptor();
        Application application = applicationService.extractFrom(content, metadata);
        boolean applicationRequestInfoAboutItSelf = !Objects.equals(context.getDecodedSourceDeployment(),
                resource.getDecodedUrl());
        boolean hasWriteAccess = batchPermissions.getOrDefault(resource, Set.of()).contains(ResourceAccessType.WRITE);
        boolean filterClientProps = applicationRequestInfoAboutItSelf && !hasWriteAccess;
        if (application.hasApplicationTypeSchemaId()) {
            application.setMcp(applicationSchemaService.getMcp(application));
            application.setViewerUrl(applicationSchemaService.getStringProperty(application, MetaSchemaHolder.APPLICATION_TYPE_VIEWER_URL));
        }
        return applicationSchemaService.modifySchemaRichApplication(application, filterClientProps);
    }

}
