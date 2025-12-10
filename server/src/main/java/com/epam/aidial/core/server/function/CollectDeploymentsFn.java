package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public class CollectDeploymentsFn extends BaseRequestFunction<ObjectNode> {

    public CollectDeploymentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        if (context.getDeployment() instanceof Application application) {
            List<ResourceDescriptor> deployments = proxy.getApplicationSchemaService().getDeployments(application);
            ApiKeyData sourceApiKeyData = context.getApiKeyData();
            ApiKeyData destApiKeyData = context.getProxyApiKeyData();
            AccessService accessService = proxy.getAccessService();
            for (var deployment : deployments) {
                if (deployment.isPublic()) {
                    continue;
                }
                String resourceUrl = deployment.getUrl();
                if (sourceApiKeyData.getAttachedDeployments().containsKey(resourceUrl) || accessService.hasReadAccess(deployment, context)) {
                    destApiKeyData.getAttachedDeployments().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
                    ResourceType resourceType = deployment.getType();
                    attachDeploymentCredentials(accessService, destApiKeyData, resourceUrl, resourceType);
                } else {
                    throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the deployment %s".formatted(resourceUrl));
                }
            }
        }
        return false;
    }

    private void attachDeploymentCredentials(AccessService accessService,
                                             ApiKeyData destApiKeyData,
                                             String resourceUrl,
                                             ResourceType resourceType) {
        CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(resourceUrl, context, resourceType);
        List<ResourceDescriptor> credentialsResourceDescriptors = credentialsLocator.getUniqueCredentialsDescriptors().stream()
                .map(CredentialsDescriptor::toResourceDescriptor)
                .filter(credentialsDescriptor -> accessService.hasReadAccess(credentialsDescriptor, context))
                .toList();
        for (ResourceDescriptor credentialsResourceDescriptor : credentialsResourceDescriptors) {
            destApiKeyData.getAttachedResourceCredentials().put(
                    credentialsResourceDescriptor.getUrl(),
                    new AutoSharedData(ResourceAccessType.READ_ONLY));
        }
    }
}
