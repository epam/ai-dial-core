package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.data.ResourceAccessType;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects attached files from the chat completion request and puts the result to API key data.
 * <p>
 *     Note. The function assigns a per-request key in the end of the processing.
 * </p>
 */
@Slf4j
public class CollectRequestAttachmentsFn extends BaseRequestFunction<ObjectNode> {
    public CollectRequestAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        ProxyUtil.collectAttachedFilesFromRequest(tree, this::processAttachedFile);
        // assign api key data after processing attachments
        ApiKeyData destApiKeyData = context.getProxyApiKeyData();
        proxy.getApiKeyStore().assignPerRequestApiKey(destApiKeyData);
        return false;
    }

    private void processAttachedFile(String url) {
        ResourceDescriptor resource = fromAnyUrl(url, proxy.getEncryptionService());
        if (resource == null) {
            return;
        }
        String resourceUrl = resource.getUrl();
        ApiKeyData sourceApiKeyData = context.getApiKeyData();
        ApiKeyData destApiKeyData = context.getProxyApiKeyData();
        AccessService accessService = proxy.getAccessService();
        if (sourceApiKeyData.getAttachedFiles().containsKey(resourceUrl) || accessService.hasReadAccess(resource, context)) {
            if (resource.isFolder()) {
                destApiKeyData.getAttachedFolders().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
            } else {
                destApiKeyData.getAttachedFiles().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
            }
        } else {
            throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the file %s".formatted(url));
        }
    }

}
