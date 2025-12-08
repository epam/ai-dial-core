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
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public class CollectToolSetsFn extends BaseRequestFunction<ObjectNode> {

    public CollectToolSetsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        if (context.getDeployment() instanceof Application application) {
            List<ResourceDescriptor> toolsets = proxy.getApplicationSchemaService().getToolSets(application);
            ApiKeyData sourceApiKeyData = context.getApiKeyData();
            ApiKeyData destApiKeyData = context.getProxyApiKeyData();
            AccessService accessService = proxy.getAccessService();
            for (var toolset : toolsets) {
                if (toolset.isPublic()) {
                    continue;
                }
                String resourceUrl = toolset.getUrl();
                if (sourceApiKeyData.getAttachedToolSets().containsKey(resourceUrl) || accessService.hasReadAccess(toolset, context)) {
                    destApiKeyData.getAttachedToolSets().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
                    attachToolSetCredentials(accessService, destApiKeyData, resourceUrl);
                } else {
                    throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the toolset %s".formatted(resourceUrl));
                }
            }
        }
        return false;
    }

    private void attachToolSetCredentials(AccessService accessService,
                                          ApiKeyData destApiKeyData,
                                          String toolSetUrl) {
        CredentialsLocator credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(toolSetUrl, context, ResourceTypes.TOOL_SET);
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
