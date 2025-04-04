package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;

public class DeploymentService {

    private final EncryptionService encryptionService;

    private final ApplicationService applicationService;

    private final AccessService accessService;

    public DeploymentService(EncryptionService encryptionService, ApplicationService applicationService, AccessService accessService) {
        this.encryptionService = encryptionService;
        this.applicationService = applicationService;
        this.accessService = accessService;
    }

    public Deployment findDeployment(ProxyContext context, String id) {
        Deployment deployment = context.getConfig().selectDeployment(id);
        if (deployment != null) {
            if (!deployment.hasAccess(context.getUserRoles())) {
                throw new PermissionDeniedException("Forbidden deployment: " + id);
            }
            return deployment;
        }
        return getApplication(context, id);
    }

    private Application getApplication(ProxyContext context, String id) {
        String url;
        ResourceDescriptor resource;

        try {
            url = UrlUtil.encodePath(id);
            resource = ResourceDescriptorFactory.fromAnyUrl(url, encryptionService);
        } catch (Throwable ignore) {
            throw new ResourceNotFoundException("Unknown application: " + id);
        }

        if (resource.isFolder() || resource.getType() != ResourceTypes.APPLICATION) {
            throw new ResourceNotFoundException("Invalid application url: " + url);
        }

        if (!accessService.hasReadAccess(resource, context)) {
            throw new PermissionDeniedException();
        }

        return applicationService.getApplication(resource).getValue();
    }
}
