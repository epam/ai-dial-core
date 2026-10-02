package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ResponseMetadata;
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
import javax.annotation.Nullable;

@Slf4j
public class ResponseMetadataService {
    private static final int PAGE_SIZE = 1000;
    private static final long DEFAULT_CHECK_PERIOD = 24 * 60 * 60 * 1000;
    private static final long DEFAULT_TTL = 30L * 24 * 60 * 60 * 1000;
    private static final long MAX_START_OFFSET = 4 * 60 * 60 * 1000;

    private final Vertx vertx;
    private final ResourceService resourceService;

    public ResponseMetadataService(Vertx vertx, ResourceService resourceService) {
        this.vertx = vertx;
        this.resourceService = resourceService;
    }

    public void init(AsyncTaskExecutor taskExecutor) {
        long offset = ThreadLocalRandom.current().nextLong(MAX_START_OFFSET + 1);
        vertx.setPeriodic(offset, DEFAULT_CHECK_PERIOD, ignored -> taskExecutor.submit(this::cleanExpiredMetadata));
    }

    public void saveMetadata(String dialId, ResponseMetadata metadata, EtagHeader etag) {
        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMetadataDescriptor(dialId);
        resourceService.putResource(descriptor, ProxyUtil.convertToString(metadata), etag);
    }

    @Nullable
    public ResponseMetadata getMetadata(String dialId) {
        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMetadataDescriptor(dialId);
        String json = resourceService.getResource(descriptor);
        return ProxyUtil.convertToObject(json, ResponseMetadata.class);
    }

    public void deleteMetadata(String dialId) {
        ResourceDescriptor descriptor = ResponseIdUtil.getResponseMetadataDescriptor(dialId);
        resourceService.deleteResource(descriptor, EtagHeader.ANY);
    }

    private Void cleanExpiredMetadata() {
        log.debug("Housekeeping: scanning for expired response metadata");
        try {
            ResourceDescriptor root = ResourceDescriptorFactory.fromDecoded(
                    ResourceTypes.RESPONSE_METADATA, ResponseIdUtil.RESPONSE_METADATA_BUCKET, ResponseIdUtil.RESPONSE_METADATA_BUCKET_LOCATION, null);
            cleanDialIdFolders(root);
        } catch (Throwable e) {
            log.warn("Housekeeping: failed to clean expired response metadata", e);
        }
        return null;
    }

    private void cleanDialIdFolders(ResourceDescriptor root) {
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
                        cleanItemsInDialIdFolder(item.getName());
                    }
                }
            }
            token = folder.getNextToken();
        } while (token != null);
    }

    private void cleanItemsInDialIdFolder(String dialId) {
        ResourceDescriptor subfolder = ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.RESPONSE_METADATA, ResponseIdUtil.RESPONSE_METADATA_BUCKET, ResponseIdUtil.RESPONSE_METADATA_BUCKET_LOCATION, dialId + "/");

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
                            deleteExpiredItem(dialId);
                        }
                    }
                }
            }
            token = folder.getNextToken();
        } while (token != null);
    }

    private void deleteExpiredItem(String dialId) {
        try {
            deleteMetadata(dialId);
            log.debug("Housekeeping: deleted expired response metadata {}", dialId);
        } catch (Throwable e) {
            log.warn("Housekeeping: failed to delete expired response metadata {}", dialId, e);
        }
    }
}
