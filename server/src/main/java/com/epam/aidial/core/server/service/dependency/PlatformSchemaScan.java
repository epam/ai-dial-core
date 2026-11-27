package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * The platform blobs of the schemas of one type by the {@code $id} that their bodies carry. The blob name of a schema is arbitrary, so
 * finding a schema means reading every blob of its type, one body at a time, and keeping only the {@code $id}.
 *
 * <p>A scan is kept for the {@link Config} that the walk resolved against, compared by identity, and dropped once nothing else holds that
 * {@code Config}: every publication of the configuration, a write of a schema included, replaces it, so the next walk scans again, and a
 * walk that still holds the older one keeps its own entry. Walks that need the same scan wait for one of them to make it. A write that
 * bypasses the platform is seen with the next publication, as the configuration is.
 */
public final class PlatformSchemaScan {

    /** The blobs of one page that the scan lists; the bodies are read one by one, not with the page. */
    private static final int SCAN_PAGE_SIZE = 100;

    private final ResourceService resourceService;
    /** A value holds no reference to its key, or the key would never be collected. */
    private final Map<ResourceTypes, Cache<Config, Map<String, List<String>>>> scansByType = new ConcurrentHashMap<>();

    public PlatformSchemaScan(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    /**
     * It blocks: call it from a worker thread.
     *
     * @param type   {@link ResourceTypes#APP_TYPE_SCHEMA} or {@link ResourceTypes#CATALOG_SCHEMA}
     * @param merged the configuration of the walk, which decides whether an earlier scan is still the answer
     * @return the names of the blobs by schema {@code $id}; read-only
     */
    @SneakyThrows
    Map<String, List<String>> listBlobNamesBySchemaId(ResourceTypes type, Config merged) {
        Cache<Config, Map<String, List<String>>> scans = scansByType.computeIfAbsent(type, key -> CacheBuilder.newBuilder().weakKeys().build());
        try {
            return scans.get(merged, () -> scan(type));
        } catch (ExecutionException | UncheckedExecutionException e) {
            throw e.getCause();
        }
    }

    /** How many scans are kept, after the ones whose {@code Config} nothing holds any more are dropped. */
    @VisibleForTesting
    long countKeptScans() {
        long count = 0;
        for (Cache<Config, Map<String, List<String>>> scans : scansByType.values()) {
            scans.cleanUp();
            count += scans.size();
        }
        return count;
    }

    private Map<String, List<String>> scan(ResourceTypes type) {
        if (!DependencyTypePolicy.isBound(type, ResourceDescriptor.PLATFORM_BUCKET)) {
            return Map.of(); // schemas of this type are never stored in the platform bucket, so there is nothing to scan
        }
        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, "");
        Map<String, List<String>> blobNamesBySchemaId = new HashMap<>();
        String nextToken = null;
        do {
            ResourceFolderMetadata page = resourceService.getFolderMetadata(folder, nextToken, SCAN_PAGE_SIZE, true);
            if (page == null) {
                break;
            }
            for (MetadataBase node : page.getItems()) {
                if (node.getNodeType() == NodeType.ITEM) {
                    indexBlob(type, (ResourceItemMetadata) node, blobNamesBySchemaId);
                }
            }
            nextToken = page.getNextToken();
        } while (nextToken != null);
        blobNamesBySchemaId.replaceAll((schemaId, blobNames) -> List.copyOf(blobNames));
        return Map.copyOf(blobNamesBySchemaId);
    }

    /** Reads one body at a time and keeps its {@code $id}, so that the platform schemas are never all in memory together. */
    private void indexBlob(ResourceTypes type, ResourceItemMetadata item, Map<String, List<String>> blobNamesBySchemaId) {
        String blobName = item.getParentPath() == null ? item.getName()
                : item.getParentPath() + ResourceDescriptor.PATH_SEPARATOR + item.getName();
        String body = resourceService.getResource(ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, blobName));
        String schemaId = body == null ? null : extractSchemaId(body);
        if (schemaId != null) {
            blobNamesBySchemaId.computeIfAbsent(schemaId, id -> new ArrayList<>()).add(blobName);
        }
    }

    @Nullable
    private static String extractSchemaId(String body) {
        try {
            return MergedConfigStore.extractSchemaId(ProxyUtil.MAPPER.readTree(body));
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
