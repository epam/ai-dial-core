package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.epam.aidial.core.server.service.ExportTypePolicy.Content;
import com.epam.aidial.core.server.service.config.ConfigEntityCodec;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * An entity that the file configuration defines has no blob, so the export reads it from the file-sourced {@link Config} as it stands
 * and writes it as JSON, the same way for both phases. Its descriptor is a stand-in with the bucket location
 * {@link ExportResourceIdentity#FILE_LOCATION}: it keys the node of the closure and is never used to read a blob.
 */
public final class ExportFileEntities {

    private static final String BUCKET = "file";

    private ExportFileEntities() {
    }

    static ResourceDescriptor descriptorOf(ResourceTypes type, String name) {
        return ResourceDescriptorFactory.fromDecoded(type, BUCKET, ExportResourceIdentity.FILE_LOCATION, name);
    }

    /**
     * @param schemaId the {@code $id} of a schema; its stand-in is named by the digest of it, which is the segment of its key
     */
    static ResourceDescriptor descriptorOfSchema(ResourceTypes type, String schemaId) {
        return descriptorOf(type, ExportResourceIdentity.schemaDigest(schemaId));
    }

    public static boolean isFile(ResourceDescriptor descriptor) {
        return ExportResourceIdentity.FILE_LOCATION.equals(descriptor.getBucketLocation());
    }

    /**
     * The representation of an entity of the file configuration (design Decision 24): the tree that the blob mapper makes of the
     * entity of a Model, Interceptor, ToolSet or Application, or the parse of the configured text of a schema. The tree holds the
     * plaintext secrets of the entity until the redactor removes them, so it is never logged or kept.
     *
     * @return the tree, or {@code null} when the file configuration no longer holds the entity or the type has no file form
     */
    @Nullable
    public static JsonNode readTree(@Nullable Config fileConfig, ResourceDescriptor descriptor) {
        if (fileConfig == null) {
            return null;
        }
        ResourceTypes type = ExportTypePolicy.typeOf(descriptor);
        if (ExportTypePolicy.contentOf(type) == Content.SCHEMA) {
            Map<String, String> schemas = type == ResourceTypes.APP_TYPE_SCHEMA ? fileConfig.getApplicationTypeSchemas() : fileConfig.getCatalogSchemas();
            for (Map.Entry<String, String> schema : schemas.entrySet()) {
                if (ExportResourceIdentity.schemaDigest(schema.getKey()).equals(descriptor.getName())) {
                    return parse(schema.getValue());
                }
            }
            return null;
        }
        Object entity = entityOf(fileConfig, type, descriptor.getName());
        return entity == null ? null : ConfigEntityCodec.BLOB_MAPPER.valueToTree(entity);
    }

    private static JsonNode parse(String text) {
        try {
            return ProxyUtil.MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            // no cause: the message of the parser can hold a piece of the document
            throw new IllegalStateException("A schema of the file configuration is not valid JSON");
        }
    }

    @Nullable
    private static Object entityOf(Config fileConfig, ResourceTypes type, String name) {
        return switch (type) {
            case APPLICATION -> fileConfig.getApplications().get(name);
            case MODEL -> fileConfig.getModels().get(name);
            case TOOL_SET -> fileConfig.getToolsets().get(name);
            case INTERCEPTOR -> fileConfig.getInterceptors().get(name);
            default -> null;
        };
    }
}
