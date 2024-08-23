package com.epam.aidial.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;


@UtilityClass
public class RequestUtil {

  private static final String FIELD_MESSAGES = "messages";
  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_ROLE = "role";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_IMAGE_URL = "image_url";
  private static final String FIELD_URL = "url";
  private static final String FIELD_ATTACHMENTS = "attachments";
  private static final String FIELD_CUSTOM_CONTENT = "custom_content";

  public ObjectNode convertToDialFormat(ObjectNode tree) {
    ArrayNode messages = (ArrayNode) tree.get(FIELD_MESSAGES);
    if (messages == null) {
      return tree;
    }
    ArrayNode updatedMessages = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < messages.size(); i++) {
      JsonNode message = messages.get(i);
      JsonNode content = message.get(FIELD_CONTENT);
      if (content.isArray()) {
        ObjectNode dialMessage = convertToDialMessage(message, content);
        updatedMessages.add(dialMessage);
      } else {
        updatedMessages.add(message);
      }
    }
    tree.set(FIELD_MESSAGES, updatedMessages);
    return tree;
  }

  private ObjectNode convertToDialMessage(JsonNode message, JsonNode content) {
    ObjectNode dialMessage = JsonNodeFactory.instance.objectNode();
    ArrayNode attachments = JsonNodeFactory.instance.arrayNode();
    dialMessage.set(FIELD_ROLE, message.get(FIELD_ROLE));

    for (int i = 0; i < content.size(); i++) {
      JsonNode item = content.get(i);
      JsonNode type = item.get(FIELD_TYPE);
      if (type == null) {
        continue;
      }
      switch (type.textValue()) {
        case "text" -> dialMessage.set(FIELD_CONTENT, item.get(FIELD_TEXT));
        case "image_url" -> {
          ObjectNode attachment = JsonNodeFactory.instance.objectNode();
          attachment.put(FIELD_TYPE, "image/png");

          JsonNode imageUrlNode = item.get(FIELD_IMAGE_URL);
          if (imageUrlNode == null) {
            continue;
          }
          attachment.set(FIELD_URL, imageUrlNode.get(FIELD_URL));
          attachments.add(attachment);
        }
        default -> {
          /*
           * more cases can be added later
           */
        }
      }
    }
    if (attachments.size() > 0) {
      ObjectNode customContent = JsonNodeFactory.instance.objectNode();
      customContent.set(FIELD_ATTACHMENTS, attachments);
      dialMessage.set(FIELD_CUSTOM_CONTENT, customContent);
    }
    return dialMessage;
  }

}
