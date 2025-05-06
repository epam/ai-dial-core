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

    public void replaceSchemaRichAppFiles(Application application, String targetApplicationUrl, Map<String, String> replacementLinks) {
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
            if (!resource.isFolder()) {
                // Exact match for file descriptors
                String replacement = replacementLinks.get(resource.getUrl());
                if (replacement == null) {
                    throw new IllegalStateException("Missing replacement link for file: " + resource.getUrl());
                }
                resultMapping.put(resource.getUrl(), extractFirstPathComponent(targetApplicationResourceFolderUrl, replacement));
            } else {
                // Match the first entry where the original resource path starts with the folder path
                String folderPath = resource.getUrl();
                Map.Entry<String, String> matchingEntry = replacementLinks.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(folderPath))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Missing replacement link for folder: " + folderPath));

                resultMapping.put(folderPath, extractFirstPathComponent(targetApplicationResourceFolderUrl, matchingEntry.getValue()) + ResourceDescriptor.PATH_SEPARATOR);
            }
        }

        return resultMapping;
    }

    private static String extractFirstPathComponent(String basePath, String fullPath) {
        int basePathIndex = fullPath.indexOf(basePath);
        if (basePathIndex == -1) {
            throw new IllegalStateException(
                    "Inconsistent paths processed while updating application own resources - base path '" + basePath + "' not found in full path '" + fullPath + "'");
        }

        // Extract the part of the path before basePath
        String prefixPath = fullPath.substring(0, basePathIndex);

        // Extract the relative path after basePath
        String relativePath = fullPath.substring(basePathIndex + basePath.length());
        String[] pathComponents = relativePath.split(ResourceDescriptor.PATH_SEPARATOR, 2);

        // Construct the valid full path
        String firstComponent = pathComponents.length > 0 ? pathComponents[0] : relativePath;
        return prefixPath + basePath + firstComponent;
    }

    private static void applyApplicationOwnResourcesMapping(Application application, Map<String, String> replacementLinks) {
        JsonNode customProperties = ProxyUtil.MAPPER.convertValue(application.getApplicationProperties(), JsonNode.class);
        JsonUtils.replaceTextNodes(customProperties, replacementLinks, null, null);
        Map<String, Object> customPropertiesMap = ProxyUtil.MAPPER.convertValue(customProperties, new TypeReference<>() {
        });
        application.setApplicationProperties(customPropertiesMap);
    }

}
