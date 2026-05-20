package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Vertx;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.annotation.Nullable;

@Slf4j
@AllArgsConstructor
public class ResponseMappingService {
    private static final int PAGE_SIZE = 1000;
    private static final long DEFAULT_CHECK_PERIOD = 3_600_000L;
    private static final long DEFAULT_TTL = 30L * 24 * 60 * 60 * 1000;

    private final ResourceService resourceService;

    public void init(Vertx vertx, AsyncTaskExecutor taskExecutor) {
        vertx.setPeriodic(DEFAULT_CHECK_PERIOD, DEFAULT_CHECK_PERIOD, ignored -> taskExecutor.submit(this::cleanExpiredMappings));
    }

    public String encodeResponseId(String deploymentName, String uuid) {
        String combined = deploymentName + "/" + uuid;
        return "resp_dial_" + Base64.getUrlEncoder().withoutPadding().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
    }

    public void saveMapping(String dialResponseId, ResponseMapping mapping) {
        ResourceDescriptor descriptor = getDescriptor(dialResponseId);
        resourceService.putResource(descriptor, ProxyUtil.convertToString(mapping), EtagHeader.NEW_ONLY);
    }

    @Nullable
    public ResponseMapping getMapping(String dialResponseId) {
        ResourceDescriptor descriptor = getDescriptor(dialResponseId);
        String json = resourceService.getResource(descriptor);
        return ProxyUtil.convertToObject(json, ResponseMapping.class);
    }

    public void deleteMapping(String dialResponseId) {
        ResourceDescriptor descriptor = getDescriptor(dialResponseId);
        resourceService.deleteResource(descriptor, EtagHeader.ANY);
    }

    ResourceDescriptor getDescriptor(String dialResponseId) {
        String prefix = "resp_dial_";
        if (!dialResponseId.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid response id: " + dialResponseId);
        }
        String encoded = dialResponseId.substring(prefix.length());
        String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        int slash = decoded.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Invalid response id: " + dialResponseId);
        }
        String deploymentName = decoded.substring(0, slash);
        String uuid = decoded.substring(slash + 1);
        String relativePath = deploymentName + "/" + uuid;
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.RESPONSE_MAPPING,
                ResourceDescriptor.PRIVATE_BUCKET, ResourceDescriptor.PRIVATE_LOCATION, relativePath);
    }

    private Void cleanExpiredMappings() {
        log.debug("Housekeeping: scanning for expired response mappings");
        try {
            ResourceDescriptor root = ResourceDescriptorFactory.fromDecoded(
                    ResourceTypes.RESPONSE_MAPPING,
                    ResourceDescriptor.PRIVATE_BUCKET,
                    ResourceDescriptor.PRIVATE_LOCATION,
                    null);
            cleanDeploymentSubfolders(root);
        } catch (Throwable e) {
            log.warn("Housekeeping: failed to clean expired response mappings", e);
        }
        return null;
    }

    private void cleanDeploymentSubfolders(ResourceDescriptor root) {
        String token = null;
        do {
            ResourceFolderMetadata folder = resourceService.getFolderMetadata(root, token, PAGE_SIZE, false);
            if (folder == null) {
                break;
            }
            List<? extends MetadataBase> items = folder.getItems();
            if (items != null) {
                for (MetadataBase item : items) {
                    if (item.getNodeType() == NodeType.FOLDER) {
                        cleanItemsInDeploymentFolder(item.getName());
                    }
                }
            }
            token = folder.getNextToken();
        } while (token != null);
    }

    private void cleanItemsInDeploymentFolder(String deploymentName) {
        ResourceDescriptor subfolder = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.RESPONSE_MAPPING,
                ResourceDescriptor.PRIVATE_BUCKET,
                ResourceDescriptor.PRIVATE_LOCATION,
                deploymentName + "/");

        long now = System.currentTimeMillis();
        String token = null;
        do {
            ResourceFolderMetadata folder = resourceService.getFolderMetadata(subfolder, token, PAGE_SIZE, false);
            if (folder == null) {
                break;
            }
            List<? extends MetadataBase> items = folder.getItems();
            if (items != null) {
                for (MetadataBase item : items) {
                    if (item.getNodeType() == NodeType.ITEM && item instanceof ResourceItemMetadata itemMeta) {
                        Long createdAt = itemMeta.getCreatedAt();
                        if (createdAt == null) {
                            // S3 provides Last-Modified header only
                            createdAt = itemMeta.getUpdatedAt();
                        }
                        if (createdAt != null && createdAt + DEFAULT_TTL < now) {
                            deleteExpiredItem(deploymentName, item.getName());
                        }
                    }
                }
            }
            token = folder.getNextToken();
        } while (token != null);
    }

    private void deleteExpiredItem(String deploymentName, String uuid) {
        try {
            ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(
                    ResourceTypes.RESPONSE_MAPPING,
                    ResourceDescriptor.PRIVATE_BUCKET,
                    ResourceDescriptor.PRIVATE_LOCATION,
                    deploymentName + "/" + uuid);
            resourceService.deleteResource(descriptor, EtagHeader.ANY);
            log.debug("Housekeeping: deleted expired response mapping {}/{}", deploymentName, uuid);
        } catch (Throwable e) {
            log.warn("Housekeeping: failed to delete expired response mapping {}/{}", deploymentName, uuid, e);
        }
    }
}
