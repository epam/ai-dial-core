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

import java.util.List;

public abstract class BaseRequestFunction<T> extends BaseFunction<T, Boolean> {


    public BaseRequestFunction(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    void shareApplicationDeployments(Application application) {
        List<ResourceDescriptor> deployments = proxy.getApplicationSchemaService().getDeployments(application);
        AccessService accessService = proxy.getAccessService();
        ApiKeyData destApiKeyData = context.getProxyApiKeyData();
        for (var deployment : deployments) {
            if (deployment.isPublic()) {
                continue;
            }
            String resourceUrl = deployment.getUrl();
            if (accessService.hasReadAccess(deployment, context)) {
                destApiKeyData.getAttachedDeployments().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
                ResourceType resourceType = deployment.getType();
                attachDeploymentCredentials(accessService, destApiKeyData, resourceUrl, resourceType);
            } else {
                throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the deployment %s".formatted(resourceUrl));
            }
        }
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

    void shareApplicationPrompts(Application application) {
        List<ResourceDescriptor> prompts = proxy.getApplicationSchemaService().getPrompts(application);
        ApiKeyData apiKeyData = context.getProxyApiKeyData();
        AccessService accessService = proxy.getAccessService();
        for (ResourceDescriptor resource : prompts) {
            if (resource.isPublic()) {
                continue;
            }
            String resourceUrl = resource.getUrl();
            if (accessService.hasReadAccess(resource, context)) {
                apiKeyData.getAttachedPrompts().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
            } else {
                throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the prompt %s".formatted(resourceUrl));
            }
        }
    }

    void shareApplicationSkills(Application application) {
        List<ResourceDescriptor> skills = proxy.getApplicationSchemaService().getSkills(application);
        ApiKeyData apiKeyData = context.getProxyApiKeyData();
        AccessService accessService = proxy.getAccessService();
        for (ResourceDescriptor resource : skills) {
            if (resource.isPublic()) {
                continue;
            }
            String resourceUrl = resource.getUrl();
            if (accessService.hasReadAccess(resource, context)) {
                apiKeyData.getAttachedSkills().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
            } else {
                throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the skill %s".formatted(resourceUrl));
            }
        }
    }

    void shareApplicationFiles(Application application) {
        List<ResourceDescriptor> files = proxy.getApplicationSchemaService().getFiles(application);
        ApiKeyData apiKeyData = context.getProxyApiKeyData();
        for (ResourceDescriptor resource : files) {
            if (resource.isPublic()) {
                continue;
            }
            String resourceUrl = resource.getUrl();
            AccessService accessService = proxy.getAccessService();
            if (accessService.hasReadAccess(resource, context)) {
                if (resource.isFolder()) {
                    apiKeyData.getAttachedFolders().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
                } else {
                    apiKeyData.getAttachedFiles().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
                }
            } else {
                throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the file %s".formatted(resourceUrl));
            }
        }
    }


}
