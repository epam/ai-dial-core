package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.service.dependency.ShortNameResolution.Origin;
import com.epam.aidial.core.server.service.dependency.ShortNameResolution.Outcome;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Resolves an application type schema or a catalog schema by its {@code $id}, with the same three signals as {@link ShortNameResolver}.
 * The blob name of a schema is arbitrary, so the platform-blob signal is {@link PlatformSchemaScan}, which is outside the snapshot.
 */
final class SchemaReferenceResolver {

    /**
     * @param descriptor the platform blob of an effective platform schema; {@code null} otherwise
     */
    record SchemaResolution(Outcome outcome, @Nullable ResourceDescriptor descriptor, @Nullable Origin origin) {
    }

    private static final SchemaResolution UNKNOWN = new SchemaResolution(Outcome.ORIGIN_UNKNOWN, null, null);

    private final Config merged;
    private final Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities;
    @Nullable
    private final Config fileConfig;
    private final PlatformSchemaScan platformSchemaScan;

    SchemaReferenceResolver(ConfigSnapshot snapshot, PlatformSchemaScan platformSchemaScan) {
        this.merged = snapshot.merged();
        this.invalidEntities = snapshot.invalidEntities();
        this.fileConfig = snapshot.file();
        this.platformSchemaScan = platformSchemaScan;
    }

    /**
     * Exactly one valid platform blob with the {@code $id} wins over a schema of the file configuration with that {@code $id}, whichever
     * body the merged map holds; the file schema is the origin only when no valid blob carries the {@code $id}. Several valid blobs, or
     * no file configuration, leave the origin unknown.
     *
     * @param type {@link ResourceTypes#APP_TYPE_SCHEMA} or {@link ResourceTypes#CATALOG_SCHEMA}
     */
    SchemaResolution resolve(ResourceTypes type, String schemaId) {
        String mergedBody = MergedConfigStore.getSchemaMapOf(merged, type).get(schemaId);
        List<String> blobNames = platformSchemaScan.listBlobNamesBySchemaId(type, merged).getOrDefault(schemaId, List.of());
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
        if (validBlobNames.isEmpty() && fileBody != null && fileBody.equals(mergedBody)) {
            return new SchemaResolution(Outcome.FOUND, null, Origin.FILE);
        }
        return UNKNOWN;
    }
}

