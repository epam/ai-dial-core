package com.epam.aidial.core.service;

import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.experimental.UtilityClass;

import java.util.Map;

@UtilityClass
public class PublicationUtil {

    private static final String METADATA_PREFIX = "metadata/";

    public String replaceLinks(String conversationBody, ResourceDescription targetResource, Map<String, String> attachmentsMapping) {
        JsonObject conversation = replaceConversationIdentity(conversationBody, targetResource);

        if (attachmentsMapping.isEmpty()) {
            return conversation.toString();
        }

        JsonArray messages = conversation.getJsonArray("messages");
        if (messages == null || messages.isEmpty()) {
            return conversation.toString();
        }

        for (int i = 0; i < messages.size(); i++) {
            JsonObject message = messages.getJsonObject(i);
            JsonObject customContent = message.getJsonObject("custom_content");
            if (customContent == null) {
                continue;
            }
            JsonArray attachments = customContent.getJsonArray("attachments");
            if (attachments == null || attachments.isEmpty()) {
                continue;
            }
            for (int j = 0; j < attachments.size(); j++) {
                JsonObject attachment = attachments.getJsonObject(j);
                String url = attachment.getString("url");
                if (url == null) {
                    continue;
                }
                boolean isMetadata = false;
                if (url.startsWith(METADATA_PREFIX)) {
                    isMetadata = true;
                    url = url.substring(METADATA_PREFIX.length());
                }
                String toReplace = attachmentsMapping.get(url);
                if (toReplace != null) {
                    attachment.put("url", isMetadata ? METADATA_PREFIX + toReplace : toReplace);
                }
            }
        }

        return conversation.toString();
    }

    private JsonObject replaceConversationIdentity(String conversationBody, ResourceDescription targetResource) {
        JsonObject conversation = new JsonObject(conversationBody);
        ResourceDescription folderLink = targetResource.getParent();
        String folderUrl = folderLink == null
                ? targetResource.getType().getGroup() + BlobStorageUtil.PATH_SEPARATOR + targetResource.getBucketName()
                : folderLink.getDecodedUrl();
        if (folderUrl.charAt(folderUrl.length() - 1) == '/') {
            folderUrl = folderUrl.substring(0, folderUrl.length() - 1);
        }
        // id and folderId are chat specific
        conversation.put("id", targetResource.getDecodedUrl());
        conversation.put("folderId", folderUrl);
        return conversation;
    }
}
