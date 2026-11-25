package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.service.config.ConfigEntityCodec;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Binds the stored body of one resource to its entity, so that its references can be found. Nothing is logged or thrown that holds a value
 * of the body: a failure names the resource only.
 */
final class DependencyEntityBinder {

    private DependencyEntityBinder() {
    }

    /**
     * @param storedText the stored body, as read
     * @throws IllegalStateException if the text is not a JSON object or cannot be bound; the message names the resource only, never the
     *                               parser message, which can quote a value
     */
    public static Deployment bind(ResourceDescriptor descriptor, String storedText) {
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
            return ConfigEntityCodec.treeToEntity(tree, DependencyTypePolicy.resolveEntityClass(DependencyTypePolicy.resolveType(descriptor)));
        } catch (IllegalArgumentException e) {
            // no cause: the message names a field and can quote a scalar of the body
            throw newRefusalException(descriptor, "A stored body cannot be bound to its type");
        }
    }

    private static IllegalStateException newRefusalException(ResourceDescriptor descriptor, String rule) {
        return new IllegalStateException(rule + " (resource=" + DependencyResourceNames.buildKey(descriptor) + ")");
    }
}
