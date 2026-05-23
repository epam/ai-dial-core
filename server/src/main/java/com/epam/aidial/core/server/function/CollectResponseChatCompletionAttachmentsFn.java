package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ChatUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

public class CollectResponseChatCompletionAttachmentsFn extends CollectResponseAttachmentsFn {
    public CollectResponseChatCompletionAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Set<String> collectAttachments(ObjectNode tree) {
        ArrayNode choices = (ArrayNode) tree.get("choices");
        if (choices == null) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (int i = 0; i < choices.size(); i++) {
            JsonNode choice = choices.get(i);
            String messageNodeName = context.isStreamingRequest() ? "delta" : "message";
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
                    String url = ChatUtil.readCustomAttachment(attachment);
                    if (url != null) {
                        result.add(url);
                    }
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
                        String url = ChatUtil.readCustomAttachment(attachment);
                        if (url != null) {
                            result.add(url);
                        }
                    }
                }
            }
            ArrayNode annotations = (ArrayNode) customContent.get("annotations");
            if (annotations != null) {
                for (int j = 0; j < annotations.size(); j++) {
                    JsonNode attachment = annotations.get(j).path("body").path("source").path("attachment");
                    String url = ChatUtil.readCustomAttachment(attachment);
                    if (url != null) {
                        result.add(url);
                    }
                }
            }
        }

        return result;
    }
}
