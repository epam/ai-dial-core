package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.service.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.ShortNameResolution.Outcome;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Resolves an application type schema or a catalog schema by its {@code $id}, with the same three signals as {@link ShortNameResolver}.
 * The blob name of a schema is arbitrary, so the platform-blob signal is a scan of the platform schema blobs, once per schema type, one
 * body at a time under the deadline, keeping only the {@code $id}; the scan is outside the snapshot. Not thread-safe; serves one export.
 */
public final class SchemaReferenceResolver {

    /**
     * @param descriptor the platform blob of an effective platform schema; {@code null} otherwise
     */
    public record SchemaResolution(Outcome outcome, @Nullable ResourceDescriptor descriptor, @Nullable Origin origin) {
    }

    private static final SchemaResolution UNKNOWN = new SchemaResolution(Outcome.ORIGIN_UNKNOWN, null, null);

    /** The blobs of one page that the scan lists; the bodies are read one by one, not with the page. */
    private static final int SCAN_PAGE_SIZE = 100;

    private final Config merged;
    private final Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities;
    @Nullable
    private final Config fileConfig;
    private final ResourceService resourceService;
    private final ExportLimits.Deadline deadline;
    private final Map<ResourceTypes, Map<String, List<String>>> blobNamesBySchemaId = new EnumMap<>(ResourceTypes.class);

    public SchemaReferenceResolver(Config merged, Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities,
                                   @Nullable Config fileConfig, ResourceService resourceService, ExportLimits.Deadline deadline) {
        this.deadline = deadline;
        this.merged = merged;
        this.invalidEntities = invalidEntities;
        this.fileConfig = fileConfig;
        this.resourceService = resourceService;
    }

    /**
     * Exactly one valid platform blob with the {@code $id} wins over a schema of the file configuration with that {@code $id}, whichever
     * body the merged map holds; the file schema is the origin only when no valid blob carries the {@code $id}. Several valid blobs, or
     * no file configuration, leave the origin unknown.
     *
     * @param type {@link ResourceTypes#APP_TYPE_SCHEMA} or {@link ResourceTypes#CATALOG_SCHEMA}
     */
    public SchemaResolution resolve(ResourceTypes type, String schemaId) {
        String mergedBody = MergedConfigStore.getSchemaMapOf(merged, type).get(schemaId);
        List<String> blobNames = blobNamesBySchemaId.computeIfAbsent(type, this::scanPlatformBlobs).getOrDefault(schemaId, List.of());
        Map<String, InvalidEntityRecord> invalidOfType = invalidEntities.getOrDefault(type, Map.of());
        boolean hasSkipped = false;
        List<String> validBlobNames = new ArrayList<>();
        for (String blobName : blobNames) {
            InvalidEntityRecord entry = invalidOfType.get(MergedConfigStore.canonicalId(type, ResourceDescriptor.PLATFORM_BUCKET, blobName));
            if (entry == null) {
                validBlobNames.add(blobName);
            } else {
                hasSkipped = true;
            }
        }

        if (mergedBody == null) {
            if (hasSkipped && validBlobNames.isEmpty()) {
                return new SchemaResolution(Outcome.SKIPPED, null, null);
            }
            return new SchemaResolution(validBlobNames.isEmpty() ? Outcome.NOT_FOUND : Outcome.ORIGIN_UNKNOWN, null, null);
        }
        if (fileConfig == null) {
            return UNKNOWN;
        }
        String fileBody = MergedConfigStore.getSchemaMapOf(fileConfig, type).get(schemaId);
        if (validBlobNames.size() == 1) {
            ResourceDescriptor descriptor = ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PLATFORM_BUCKET,
                    ResourceDescriptor.PLATFORM_LOCATION, validBlobNames.get(0));
            return new SchemaResolution(Outcome.FOUND, descriptor, Origin.PLATFORM);
        }
        if (validBlobNames.isEmpty() && fileBody != null && fileBody == mergedBody) {
            return new SchemaResolution(Outcome.FOUND, null, Origin.FILE);
        }
        return UNKNOWN;
    }

    private Map<String, List<String>> scanPlatformBlobs(ResourceTypes type) {
        if (!ExportTypePolicy.isBound(type, ResourceDescriptor.PLATFORM_BUCKET)) {
            return Map.of(); // schemas of this type are never stored in the platform bucket, so there is nothing to scan
        }
        ResourceDescriptor folder = ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, "");
        Map<String, List<String>> blobNamesBySchemaIdOfType = new HashMap<>();
        String nextToken = null;
        do {
            deadline.check();
            ResourceFolderMetadata page = resourceService.getFolderMetadata(folder, nextToken, SCAN_PAGE_SIZE, true);
            if (page == null) {
                break;
            }
            for (MetadataBase node : page.getItems()) {
                if (node.getNodeType() == NodeType.ITEM) {
                    indexBlob(type, (ResourceItemMetadata) node, blobNamesBySchemaIdOfType);
                }
            }
            nextToken = page.getNextToken();
        } while (nextToken != null);
        return blobNamesBySchemaIdOfType;
    }

    /** Reads one body at a time and keeps its {@code $id}, so that the platform schemas are never all in memory together. */
    private void indexBlob(ResourceTypes type, ResourceItemMetadata item, Map<String, List<String>> blobNamesBySchemaIdOfType) {
        deadline.check();
        String blobName = item.getParentPath() == null ? item.getName()
                : item.getParentPath() + ResourceDescriptor.PATH_SEPARATOR + item.getName();
        String body = resourceService.getResource(ResourceDescriptorFactory.fromDecoded(type, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, blobName));
        String schemaId = body == null ? null : extractSchemaId(body);
        if (schemaId != null) {
            blobNamesBySchemaIdOfType.computeIfAbsent(schemaId, id -> new ArrayList<>()).add(blobName);
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
