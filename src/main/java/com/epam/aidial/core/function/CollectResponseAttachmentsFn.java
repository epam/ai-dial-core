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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.epam.aidial.core.util.ProxyUtil.collectAttachedFile;

@Slf4j
public class CollectResponseAttachmentsFn extends BaseResponseFunction {
    public CollectResponseAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<Void> apply(ObjectNode tree) {
        try {
            Set<String> attachments = collectAttachments(tree);
            if (attachments.isEmpty()) {
                return Future.succeededFuture();
            }
            String perRequestKey = context.getApiKeyData().getPerRequestKey();
            return proxy.getApiKeyStore().updatePerRequestApiKey(perRequestKey, json -> updateApiKeyData(json, attachments, perRequestKey));
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    private Set<String> collectAttachments(ObjectNode tree) {
        ArrayNode choices = (ArrayNode) tree.get("choices");
        if (choices == null) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        boolean isStream = context.isStreamingRequest();
        for (int i = 0; i < choices.size(); i++) {
            JsonNode choice = choices.get(i);
            String messageNodeName = isStream ? "delta" : "message";
            JsonNode message = choice.get(messageNodeName);
            if (message == null) {
                continue;
            }
            JsonNode customContent = message.get("custom_content");
            if (customContent == null) {
                continue;
            }
            ArrayNode attachments = (ArrayNode) customContent.get("attachments");
            if (attachments != null) {
                for (int j = 0; j < attachments.size(); j++) {
                    JsonNode attachment = attachments.get(j);
                    collectAttachedFile(attachment, url -> processAttachedFile(url, result));
                }
            }
            ArrayNode stages = (ArrayNode) customContent.get("stages");
            if (stages != null) {
                for (int j = 0; j < stages.size(); j++) {
                    JsonNode stage = stages.get(j);
                    attachments = (ArrayNode) stage.get("attachments");
                    if (attachments == null) {
                        continue;
                    }
                    for (int k = 0; k < attachments.size(); k++) {
                        JsonNode attachment = attachments.get(k);
                        collectAttachedFile(attachment, url -> processAttachedFile(url, result));
                    }
                }
            }
        }
        return result;
    }

    private String updateApiKeyData(String json, Set<String> collectedUrls, String key) {
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
        ResourceDescription resource = ResourceDescription.fromAnyUrl(url, proxy.getEncryptionService());
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
