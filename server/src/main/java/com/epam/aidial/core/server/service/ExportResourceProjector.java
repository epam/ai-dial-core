package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.service.config.ConfigEntityCodec;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.UnaryOperator;

/**
 * Turns one resource of an export into the bytes of its archive entry, the way {@code ConfigResourceController} answers a single admin
 * GET: bind the stored text, decrypt the external service secrets, serialize with {@link EntityProjection} and remove the secrets from
 * that tree. No secret leaves: {@code @EncryptedField} values are not serialized, and {@code client_secret} and {@code code_verifier}
 * are removed, leaving the masked {@code client_secret_hint}.
 *
 * <p>Nothing is logged or thrown that holds a value of a resource: a failure names the resource only.
 */
public final class ExportResourceProjector {

    private final ApplicationService applicationService;
    private final ToolSetService toolSetService;

    public ExportResourceProjector(ApplicationService applicationService, ToolSetService toolSetService) {
        this.applicationService = applicationService;
        this.toolSetService = toolSetService;
    }

    /**
     * @param storedText the stored body, as read; secrets are still encrypted in the entity
     * @throws IllegalStateException if the text is not a JSON object or cannot be bound; the message names the resource only, never the
     *                               parser message, which can quote a value
     */
    public Deployment bind(ResourceDescriptor descriptor, String storedText) {
        JsonNode tree;
        try {
            tree = ConfigEntityCodec.BLOB_MAPPER.readTree(storedText);
        } catch (JsonProcessingException e) {
            // no cause: the message of the parser can hold a piece of the body
            throw newRefusalException(descriptor, "A stored body is not valid JSON");
        }
        if (tree == null || !tree.isObject()) {
            throw newRefusalException(descriptor, "A stored body is not a JSON object");
        }
        try {
            return ConfigEntityCodec.treeToEntity(tree, ExportTypePolicy.resolveEntityClass(ExportTypePolicy.resolveType(descriptor)));
        } catch (IllegalArgumentException e) {
            // no cause: the message names a field and can quote a scalar of the body
            throw newRefusalException(descriptor, "A stored body cannot be bound to its type");
        }
    }

    /**
     * The flow of the admin GET: decrypt (Application and ToolSet, not an entity of the file configuration, which holds no ciphertext),
     * serialize, remove the secrets, write. A secret that cannot be decrypted is dropped, so it has no hint, and the export goes on.
     *
     * @param entity changed (decrypted): use it again only to read its references
     * @throws IllegalStateException if the type has no rule that removes its secrets; the message names the resource only
     */
    public byte[] projectDeployment(ResourceDescriptor descriptor, Deployment entity) {
        UnaryOperator<ObjectNode> redaction = ExportTypePolicy.resolveRedaction(ExportTypePolicy.resolveType(descriptor));
        if (redaction == null) {
            throw newRefusalException(descriptor, "A resource of this type has no rule that removes its secrets");
        }
        if (!ExportFileEntities.isFile(descriptor)) {
            if (entity instanceof Application application) {
                applicationService.decryptExternalServiceSecretsForResponse(descriptor, application);
            } else if (entity instanceof ToolSet toolSet) {
                toolSetService.decryptAuthSettingsForResponse(descriptor, toolSet);
            }
        }
        return toBytes(descriptor, redaction.apply(EntityProjection.project(entity)));
    }

    /**
     * @throws IllegalStateException if the text is not a JSON object
     */
    public byte[] projectSchema(ResourceDescriptor descriptor, String storedText) {
        JsonNode tree;
        try {
            tree = ProxyUtil.MAPPER.readTree(storedText);
        } catch (JsonProcessingException e) {
            throw newRefusalException(descriptor, "A stored body is not valid JSON");
        }
        if (!(tree instanceof ObjectNode schema)) {
            throw newRefusalException(descriptor, "A stored body is not a JSON object");
        }
        if (ExportTypePolicy.resolveType(descriptor) == ResourceTypes.APP_TYPE_SCHEMA) {
            EntityProjection.removeSchemaUpstreamKeys(schema);
        }
        return toBytes(descriptor, schema);
    }

    private static byte[] toBytes(ResourceDescriptor descriptor, JsonNode tree) {
        try {
            return ProxyUtil.MAPPER.writeValueAsBytes(tree);
        } catch (JsonProcessingException e) {
            throw newRefusalException(descriptor, "The projection cannot be serialized");
        }
    }

    private static IllegalStateException newRefusalException(ResourceDescriptor descriptor, String rule) {
        return new IllegalStateException(rule + " (resource=" + ExportResourceNames.buildKey(descriptor) + ")");
    }
}
