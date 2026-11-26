package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.util.HashUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import javax.annotation.Nullable;

/**
 * An entity of the file configuration has no blob, so the walk reads it from the file-sourced {@link Config} as it stands. Its
 * descriptor is a stand-in with the bucket location {@code file/}: it keys the visited set and is never used to read a blob.
 */
final class FileConfigEntities {

    private static final String BUCKET = "file";
    private static final String FILE_LOCATION = "file/";

    private FileConfigEntities() {
    }

    static ResourceDescriptor newDescriptor(ResourceTypes type, String name) {
        return ResourceDescriptorFactory.fromDecoded(type, BUCKET, FILE_LOCATION, name);
    }

    /**
     * @param schemaId the {@code $id} of a schema; its stand-in is named by the digest of it
     */
    static ResourceDescriptor newSchemaDescriptor(ResourceTypes type, String schemaId) {
        return newDescriptor(type, computeSchemaDigest(schemaId));
    }

    public static boolean isFile(ResourceDescriptor descriptor) {
        return FILE_LOCATION.equals(descriptor.getBucketLocation());
    }

    /**
     * @return the segment of the key and the entry name of a schema: the first 16 bytes of the digest of its {@code $id}, as hex
     */
    static String computeSchemaDigest(String schemaId) {
        return HashUtil.sha256Hex(schemaId).substring(0, 32);
    }

    /**
     * @return the entity that the file configuration holds, or {@code null} when it no longer holds it or the type has no file form;
     *         the entity is the live one and must not be changed
     */
    @Nullable
    static Deployment findDeployment(@Nullable Config fileConfig, ResourceDescriptor descriptor) {
        if (fileConfig == null) {
            return null;
        }
        return DependencyTypePolicy.findDeployment(fileConfig, DependencyTypePolicy.resolveType(descriptor), descriptor.getName());
    }
}
