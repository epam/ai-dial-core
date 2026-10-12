package com.epam.aidial.core.server.util;

import com.epam.aidial.core.storage.data.MetadataBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

@UtilityClass
public class ChatUtil {
    private static final String CUSTOM_FIELDS_NODE = "custom_fields";

    public Set<String> collectAttachments(JsonNode root, List<String> paths) {
        return JsonUtil.collectStrings(root, paths, ChatUtil::readAttachment);
    }

    public Set<String> collectCustomAttachments(JsonNode node, List<String> paths) {
        return JsonUtil.collectStrings(node, paths, ChatUtil::readCustomAttachment);
    }

    public Set<String> collectCustomSkills(JsonNode node, List<String> paths) {
        return JsonUtil.collectStrings(node, paths, ChatUtil::readCustomSkill);
    }

    private String readAttachment(JsonNode node) {
        if (!node.isTextual()) {
            throw new IllegalArgumentException("Invalid attachment.");
        }
        String value = node.textValue();
        return StringUtils.isBlank(value) ? null : node.textValue();
    }

    public String readCustomAttachment(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String type = node.path("type").asText();
        String url = node.path("url").asText();
        if (StringUtils.isBlank(url)) {
            return null;
        }

        if (MetadataBase.MIME_TYPE.equals(type)) {
            if (!url.startsWith(ProxyUtil.METADATA_PREFIX)) {
                throw new IllegalArgumentException("Url of metadata attachment must start with metadata/: " + url);
            }
            url = url.substring(ProxyUtil.METADATA_PREFIX.length());
        }
        return url;
    }

    public String readCustomSkill(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("Invalid skill attachment.");
        }
        String url = node.path("url").asText();
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException("Missing url in skill attachment.");
        }
        return url;
    }

    /**
     * @return whether the node changed
     */
    public boolean removeInterceptorConfiguration(ObjectNode node) {
        ObjectNode customFields = (ObjectNode) node.get(CUSTOM_FIELDS_NODE);
        if (customFields == null) {
            return false;
        }
        boolean removed = customFields.remove("interceptor_configuration") != null;
        if (customFields.isEmpty()) {
            node.remove(CUSTOM_FIELDS_NODE);
            return true;
        }
        return removed;
    }

    /**
     * @return whether the node changed
     */
    public boolean applyDefaults(ObjectNode node, Map<String, Object> defaults) {
        boolean changed = false;
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            String key = e.getKey();
            JsonNode defaultValue = ProxyUtil.MAPPER.convertValue(e.getValue(), JsonNode.class);
            changed |= JsonUtil.applyDefault(node, key, defaultValue);
        }
        return changed;
    }
}
