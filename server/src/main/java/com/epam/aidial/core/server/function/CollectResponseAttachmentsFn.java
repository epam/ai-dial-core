package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.ResourceAccessType;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.storage.BlobStorageUtil;
import com.epam.aidial.core.server.storage.ResourceDescription;
import com.epam.aidial.core.server.util.ProxyUtil;
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
        // Note. permission check: make sure that the target deployment has access to the resource only
        // we don't check other permissions like admin, share or publishing access since we give full permissions to the source deployment
        Map<ResourceDescription, Set<ResourceAccessType>> result = AccessService.getAppResourceAccess(Set.of(resource),
                context, context.getDeployment().getName());
        if (result.containsKey(resource)) {
            collectedUrls.add(resource.getUrl());
        }
    }

}
