package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;


@Slf4j
public class CollectRequestApplicationFilesFn extends BaseRequestFunction<ObjectNode> {
    public CollectRequestApplicationFilesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        try {
            Deployment deployment = context.getDeployment();
            if (!(deployment instanceof Application application && application.hasApplicationTypeSchemaId())) {
                return false;
            }
            if (application.getApplicationProperties() == null) {
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Typed application's properties not set");
            }
            ApplicationSchemaService applicationSchemaService = proxy.getApplicationSchemaService();
            List<ResourceDescriptor> resources = applicationSchemaService.getFiles(application);
            appendFilesToProxyApiKeyData(resources);
            return false;
        } catch (HttpException ex) {
            throw ex;
        } catch (ApplicationTypeResourceException ex) {
            throw new HttpException(HttpStatus.FORBIDDEN, ex.getMessage() + " : " + ex.getResourceUri());
        } catch (Exception e) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void appendFilesToProxyApiKeyData(List<ResourceDescriptor> resources) {
        ApiKeyData apiKeyData = context.getProxyApiKeyData();
        for (ResourceDescriptor resource : resources) {
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
