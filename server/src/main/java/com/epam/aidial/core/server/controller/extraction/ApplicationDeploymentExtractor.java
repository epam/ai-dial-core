package com.epam.aidial.core.server.controller.extraction;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;

import java.util.Objects;

public class ApplicationDeploymentExtractor implements DeploymentService.DeploymentExtractor {
    private final AccessService accessService;
    private final ApplicationService applicationService;
    private final ApplicationSchemaService applicationSchemaService;

    public ApplicationDeploymentExtractor(AccessService accessService, ApplicationService applicationService, ApplicationSchemaService applicationSchemaService) {
        this.accessService = accessService;
        this.applicationService = applicationService;
        this.applicationSchemaService = applicationSchemaService;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Application extract(String content, ResourceItemMetadata metadata, ProxyContext context) {
        ResourceDescriptor resource = metadata.getDescriptor();
        Application application = applicationService.extractFrom(content, metadata);
        boolean applicationRequestInfoAboutItSelf = !Objects.equals(context.getDecodedSourceDeployment(),
                resource.getDecodedUrl());
        boolean filterClientProps = applicationRequestInfoAboutItSelf && !accessService.hasWriteAccess(resource, context);
        if (application.hasApplicationTypeSchemaId()) {
            application.setMcp(applicationSchemaService.getMcp(application));
            application.setViewerUrl(applicationSchemaService.getStringProperty(application, MetaSchemaHolder.APPLICATION_TYPE_VIEWER_URL));
        }
        return applicationSchemaService.modifySchemaRichApplication(application, filterClientProps);
    }

}
