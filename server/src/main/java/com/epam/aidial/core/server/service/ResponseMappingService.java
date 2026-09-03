package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.util.ResponseIdUtil;
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
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import javax.annotation.Nullable;

@Slf4j
public class ResponseMappingService {
    private static final int PAGE_SIZE = 1000;
    private static final long DEFAULT_CHECK_PERIOD = 24 * 60 * 60 * 1000;
    private static final long DEFAULT_TTL = 30L * 24 * 60 * 60 * 1000;
    private static final long MAX_START_OFFSET = 4 * 60 * 60 * 1000;

    private final Vertx vertx;
    private final Supplier<String> generator;
    private final ResourceService resourceService;

    public ResponseMappingService(Vertx vertx, Supplier<String> generator, ResourceService resourceService) {
        this.vertx = vertx;
        this.generator = generator;
        this.resourceService = resourceService;
    }

    public void init(AsyncTaskExecutor taskExecutor) {
        long offset = ThreadLocalRandom.current().nextLong(MAX_START_OFFSET + 1);
        vertx.setPeriodic(offset, DEFAULT_CHECK_PERIOD, ignored -> taskExecutor.submit(this::cleanExpiredMappings));
    }

    public String saveMapping(ProxyContext context, ResponseMapping mapping) {
        String dialId = ResponseIdUtil.createResponseId(context.getDeployment().getName(), generator.get());
        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMappingDescriptor(dialId);
        resourceService.putResource(descriptor, ProxyUtil.convertToString(mapping), EtagHeader.NEW_ONLY);
        return dialId;
    }

    @Nullable
    public ResponseMapping getMapping(String dialId) {
        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMappingDescriptor(dialId);
        String json = resourceService.getResource(descriptor);
        return ProxyUtil.convertToObject(json, ResponseMapping.class);
    }

    public void deleteMapping(String dialId) {
        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMappingDescriptor(dialId);
        resourceService.deleteResource(descriptor, EtagHeader.ANY);
    }

    private Void cleanExpiredMappings() {
        log.debug("Housekeeping: scanning for expired response mappings");
        try {
            ResourceDescriptor root = ResourceDescriptorFactory.fromDecoded(
                    ResourceTypes.RESPONSE_MAPPING, ResourceDescriptor.RESPONSE_MAPPINGS_BUCKET, ResourceDescriptor.RESPONSE_MAPPINGS_LOCATION, null);
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
                ResourceTypes.RESPONSE_MAPPING, ResourceDescriptor.RESPONSE_MAPPINGS_BUCKET, ResourceDescriptor.RESPONSE_MAPPINGS_LOCATION, deploymentName + "/");

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
            ResourceDescriptor descriptor = ResponseIdUtil.getResponseMappingDescriptor(ResponseIdUtil.createResponseId(deploymentName, uuid));
            resourceService.deleteResource(descriptor, EtagHeader.ANY);
            log.debug("Housekeeping: deleted expired response mapping {}/{}", deploymentName, uuid);
        } catch (Throwable e) {
            log.warn("Housekeeping: failed to delete expired response mapping {}/{}", deploymentName, uuid, e);
        }
    }
}
