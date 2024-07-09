package com.epam.aidial.core.service;

import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.experimental.UtilityClass;

import java.util.Map;

@UtilityClass
public class PublicationUtil {

    /**
     * Replaces conversation identity and attachment links after it has been copied from one location to another.
     * Replacing `id` and `folderId` - chat specific and may not be suitable for generic use-case.
     * Typical use-case: replace attachment links in conversation after publishing.
     *
     * @param conversationBody   - source conversation body
     * @param targetResource     - target resource link
     * @param attachmentsMapping - attachments map (sourceUrl -> targetUrl) to replace
     * @return conversation body after replacement
     */
    public String replaceConversationLinks(String conversationBody, ResourceDescription targetResource, Map<String, String> attachmentsMapping) {
        JsonObject conversation = replaceConversationIdentity(conversationBody, targetResource);

        if (attachmentsMapping.isEmpty()) {
            return conversation.toString();
        }

        JsonArray messages = conversation.getJsonArray("messages");
        replaceAttachments(messages, attachmentsMapping);

        JsonObject playback = conversation.getJsonObject("playback");
        if (playback != null) {
            JsonArray messagesStack = playback.getJsonArray("messagesStack");
            replaceAttachments(messagesStack, attachmentsMapping);
        }

        JsonObject replay = conversation.getJsonObject("replay");
        if (replay != null) {
            JsonArray messagesStack = replay.getJsonArray("replayUserMessagesStack");
            replaceAttachments(messagesStack, attachmentsMapping);
        }

        return conversation.toString();
    }

    public String replaceApplicationIdentity(String applicationBody, ResourceDescription targetResource) {
        JsonObject application = new JsonObject(applicationBody);
        application.put("name", targetResource.getUrl());

        return application.toString();
    }

    private void replaceAttachments(JsonArray messages, Map<String, String> attachmentsMapping) {
        if (messages == null || messages.isEmpty()) {
            return;
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
                if (url.startsWith(ProxyUtil.METADATA_PREFIX)) {
                    isMetadata = true;
                    url = url.substring(ProxyUtil.METADATA_PREFIX.length());
                }
                String toReplace = attachmentsMapping.get(url);
                if (toReplace != null) {
                    attachment.put("url", isMetadata ? ProxyUtil.METADATA_PREFIX + toReplace : toReplace);
                }
            }
        }
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
