package com.epam.aidial.core.server.service.schemarichapps;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaUtils;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.epam.aidial.core.server.service.schemarichapps.TargetFolderUtil.getTargetFolderForCustomAppFiles;

public class ApplicationResourcesReplacementService {

    private final ConfigStore configStore;
    private final EncryptionService encryptionService;
    private final ResourceService resourceService;

    public ApplicationResourcesReplacementService(ConfigStore configStore, EncryptionService encryptionService, ResourceService resourceService) {
        this.configStore = configStore;
        this.encryptionService = encryptionService;
        this.resourceService = resourceService;
    }

    public void replaceSchemaRichAppOwnResources(Application application, String targetApplicationUrl, Map<String, String> replacementLinks) {
        if (application.getApplicationTypeSchemaId() == null) {
            return;
        }
        String targetApplicationResourceFolderUrl = getTargetFolderForCustomAppFiles(targetApplicationUrl, encryptionService);
        replacementLinks = extractApplicationOwnResourcesMapping(application, targetApplicationResourceFolderUrl, replacementLinks);
        applyApplicationOwnResourcesMapping(application, replacementLinks);
    }

    private Map<String, String> extractApplicationOwnResourcesMapping(Application application, String targetApplicationResourceFolderUrl, Map<String, String> replacementLinks) {
        List<ResourceDescriptor> applicationOwnResources = ApplicationTypeSchemaUtils.getFiles(
                configStore.get(), application, encryptionService, resourceService);

        Map<String, String> resultMapping = new HashMap<>();

        for (ResourceDescriptor resource : applicationOwnResources) {
            String resourceUrl = resource.getUrl();
            if (resource.isFolder()) {
                String folderReplacement = replacementLinks.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(resourceUrl))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Missing replacement link for folder: " + resourceUrl));
                resultMapping.put(resourceUrl, extractFirstPathComponent(targetApplicationResourceFolderUrl, folderReplacement) + ResourceDescriptor.PATH_SEPARATOR);
            } else {
                String fileReplacement = replacementLinks.get(resourceUrl);
                if (fileReplacement == null) {
                    throw new IllegalStateException("Missing replacement link for file: " + resourceUrl);
                }
                resultMapping.put(resourceUrl, extractFirstPathComponent(targetApplicationResourceFolderUrl, fileReplacement));
            }
        }

        return resultMapping;
    }

    private static String extractFirstPathComponent(String basePath, String fullPath) {
        int basePathIndex = fullPath.indexOf(basePath);
        if (basePathIndex == -1) {
            throw new IllegalStateException(
                    "Base path '" + basePath + "' not found in full path '" + fullPath + "'");
        }

        String prefixPath = fullPath.substring(0, basePathIndex);
        String relativePath = fullPath.substring(basePathIndex + basePath.length());
        String firstComponent = relativePath.split(ResourceDescriptor.PATH_SEPARATOR, 2)[0];

        return prefixPath + basePath + firstComponent;
    }

    private static void applyApplicationOwnResourcesMapping(Application application, Map<String, String> replacementLinks) {
        JsonNode customProperties = ProxyUtil.MAPPER.convertValue(application.getApplicationProperties(), JsonNode.class);
        replaceTextNodes(customProperties, replacementLinks, null, null);
        Map<String, Object> customPropertiesMap = ProxyUtil.MAPPER.convertValue(customProperties, new TypeReference<>() {
        });
        application.setApplicationProperties(customPropertiesMap);
    }

    private static void replaceTextNodes(JsonNode node, Map<String, String> replacementMap, JsonNode parent, String fieldName) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> replaceTextNodes(entry.getValue(), replacementMap, node, entry.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode childNode = node.get(i);
                if (childNode.isTextual()) {
                    String replacement = replacementMap.get(childNode.textValue());
                    if (replacement != null) {
                        ((ArrayNode) node).set(i, replacement);
                    }
                } else {
                    replaceTextNodes(childNode, replacementMap, node, String.valueOf(i));
                }
            }
        } else if (node.isTextual()) {
            String replacement = replacementMap.get(node.textValue());
            if (replacement != null && parent.isObject()) {
                ((ObjectNode) parent).put(fieldName, replacement);
            }
        }
    }

}
