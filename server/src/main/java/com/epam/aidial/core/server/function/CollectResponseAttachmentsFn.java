package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
public abstract class CollectResponseAttachmentsFn extends BaseResponseFunction {
    public CollectResponseAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<JsonNode> apply(JsonNode tree) {
        String perRequestKey = context.getApiKeyData().getPerRequestKey();
        if (perRequestKey == null || !tree.isObject()) {
            return Future.succeededFuture(tree);
        }
        ObjectNode objectNode = (ObjectNode) tree;
        try {
            Map<String, Set<ResourceAccessType>> permittedAttachments = new HashMap<>();
            Set<String> attachments = collectAttachments(objectNode);
            for (String attachment : attachments) {
                processAttachedFile(attachment, permittedAttachments);
            }
            if (permittedAttachments.isEmpty()) {
                return Future.succeededFuture(tree);
            }
            return proxy.getTaskExecutor().submit(() -> {
                proxy.getApiKeyStore().updatePerRequestApiKey(perRequestKey, json -> updateAutoSharedAttachments(json, permittedAttachments, perRequestKey));
                return tree;
            }).map(stored -> {
                // an origin plugin's in-process calls share this record and never read the stored key again: without
                // this its next call would not see the grant. Back on the request's context, where the calls read it
                addAttachments(context.getApiKeyData(), permittedAttachments);
                return stored;
            });
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }


    protected abstract Set<String> collectAttachments(ObjectNode tree);

    private String updateAutoSharedAttachments(String json, Map<String, Set<ResourceAccessType>> permittedAttachments, String key) {
        ApiKeyData apiKeyData = ProxyUtil.convertToObject(json, ApiKeyData.class);
        if (apiKeyData == null) {
            String errorMsg = String.format("Per request API key is not found: %s", key);
            log.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        addAttachments(apiKeyData, permittedAttachments);
        return ProxyUtil.convertToString(apiKeyData);
    }

    /**
     * Copy on write: the key record of an origin plugin's pass is shared by its in-process calls, which read these maps
     * on virtual threads while another call's grant lands here, so the maps a reader holds are never changed.
     */
    @VisibleForTesting
    static void addAttachments(ApiKeyData apiKeyData, Map<String, Set<ResourceAccessType>> permittedAttachments) {
        Map<String, AutoSharedData> files = new HashMap<>(apiKeyData.getAttachedFiles());
        Map<String, AutoSharedData> folders = new HashMap<>(apiKeyData.getAttachedFolders());
        for (Map.Entry<String, Set<ResourceAccessType>> entry : permittedAttachments.entrySet()) {
            String url = entry.getKey();
            Set<ResourceAccessType> permissions = entry.getValue();
            if (ResourceUtil.isFolder(url)) {
                folders.put(url, new AutoSharedData(permissions));
            } else {
                files.put(url, new AutoSharedData(permissions));
            }
        }
        apiKeyData.setAttachedFiles(files);
        apiKeyData.setAttachedFolders(folders);
    }

    private void processAttachedFile(String url, Map<String, Set<ResourceAccessType>> permittedAttachments) {
        ResourceDescriptor resource = fromAnyUrl(url, proxy.getEncryptionService());
        if (resource == null) {
            return;
        }
        String sourceDeployment = context.getApiKeyData().getSourceDeployment();
        if (context.getConfig().getInterceptors().containsKey(sourceDeployment)) {
            // Note. permission check: make sure that the target deployment has access to the resource only
            // we don't check other permissions like admin, share or publishing access since we give full permissions to the source deployment
            Map<ResourceDescriptor, Set<ResourceAccessType>> result = AccessService.getAppResourceAccess(Set.of(resource),
                    context, context.getDeployment().getName());
            if (result.containsKey(resource)) {
                permittedAttachments.put(resource.getUrl(), ResourceAccessType.ALL);
            }
        } else {
            // build context with API key data of the target deployment
            ProxyContext deploymentContext = context.copyWith(context.getProxyApiKeyData());
            AccessService accessService = proxy.getAccessService();
            boolean readable = resource.isFolder()
                    ? accessService.hasReadAccessToEverythingUnder(resource, deploymentContext)
                    : accessService.hasReadAccess(resource, deploymentContext);
            if (readable) {
                permittedAttachments.put(resource.getUrl(), ResourceAccessType.READ_ONLY);
            }
        }
    }

}
