package com.epam.aidial.core.function;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.data.AutoSharedData;
import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
public class CollectResponseAttachmentsFn extends BaseResponseFunction {
    public CollectResponseAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<Void> apply(ObjectNode tree) {
        try {
            Set<String> result = new HashSet<>();
            ProxyUtil.collectAttachmentsFromResponse(tree, context.isStreamingRequest(), url -> processAttachedFile(url, result));
            if (result.isEmpty()) {
                return Future.succeededFuture();
            }
            String perRequestKey = context.getApiKeyData().getPerRequestKey();
            return proxy.getApiKeyStore().updatePerRequestApiKey(perRequestKey, json -> updateAutoSharedAttachments(json, result, perRequestKey));
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }



    private String updateAutoSharedAttachments(String json, Set<String> collectedUrls, String key) {
        ApiKeyData apiKeyData = ProxyUtil.convertToObject(json, ApiKeyData.class);
        if (apiKeyData == null) {
            String errorMsg = String.format("Per request API key is not found: %s", key);
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        for (String url : collectedUrls) {
            if (BlobStorageUtil.isFolder(url)) {
                apiKeyData.getAttachedFolders().put(url, new AutoSharedData(ResourceAccessType.ALL));
            } else {
                apiKeyData.getAttachedFiles().put(url, new AutoSharedData(ResourceAccessType.ALL));
            }
        }
        return ProxyUtil.convertToString(apiKeyData);
    }

    private void processAttachedFile(String url, Set<String> collectedUrls) {
        ResourceDescription resource = fromAnyUrl(url, proxy.getEncryptionService());
        if (resource == null) {
            return;
        }
        Map<ResourceDescription, Set<ResourceAccessType>> result = AccessService.getAppResourceAccess(Set.of(resource),
                context, context.getDeployment().getName());
        if (result.containsKey(resource)) {
            collectedUrls.add(url);
        }
    }

}
