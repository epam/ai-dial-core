package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;

import javax.annotation.Nullable;

/**
 * The function auto shares an initial deployment and their dependent resources like files in the interceptor execution flow.
 */
public class AutoShareDeploymentFn extends BaseRequestFunction<RequestObject> {

    public AutoShareDeploymentFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject tree) {
        try {
            String initialDeployment = context.getInitialDeployment();
            ResourceDescriptor descriptor = toResourceDescriptor(initialDeployment);
            if (descriptor == null || descriptor.isPublic()) {
                return false;
            }

            ApiKeyData apiKeyData = context.getProxyApiKeyData();
            AccessService accessService = proxy.getAccessService();
            if (accessService.hasReadAccess(descriptor, context)) {
                apiKeyData.getAttachedDeployments().put(descriptor.getUrl(), new AutoSharedData(ResourceAccessType.READ_ONLY));
            } else {
                throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the deployment %s".formatted(initialDeployment));
            }
            Deployment deployment = proxy.getDeploymentService().findDeployment(context, initialDeployment);
            if (deployment instanceof Application app && app.hasApplicationTypeSchemaId()) {
                shareApplicationFiles(app);
                shareApplicationPrompts(app);
                shareApplicationDeployments(app);
            }
            return false;
        } catch (HttpException ex) {
            throw ex;
        } catch (ApplicationTypeResourceException ex) {
            throw new HttpException(HttpStatus.FAILED_DEPENDENCY, ex.getMessage() + " : " + ex.getResourceUri());
        } catch (Exception ex) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    @Nullable
    private ResourceDescriptor toResourceDescriptor(String id) {
        if (id == null) {
            return null;
        }
        try {
            return ResourceDescriptorFactory.fromAnyUrl(UrlUtil.encodePath(id), proxy.getEncryptionService());
        } catch (Exception e) {
            return null;
        }
    }
}
