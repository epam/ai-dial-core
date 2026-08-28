package com.epam.aidial.core.server.service.config;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure JSON (de)serialization helpers for admin config entities, shared by
 * {@code ConfigResourceController}, {@code ConfigApplyService} and {@code ConfigValidateService}.
 * Deliberately dependency-free (no service/controller state) so both layers can depend on it.
 */
public final class ConfigEntityCodec {

    private ConfigEntityCodec() {
    }

    public static <T> T treeToEntity(JsonNode node, Class<T> cls) {
        try {
            return ProxyUtil.BLOB_MAPPER.treeToValue(node, cls);
        } catch (JsonProcessingException e) {
            // Same rationale as parseJsonBody — the mapping error can embed the offending
            // field value, including secrets in a partially-typed request body.
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Failed to parse entity at " + locationOf(e));
        }
    }

    public static String serializeForBlob(Object entity) {
        try {
            return ProxyUtil.BLOB_MAPPER.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            // writeValueAsString failures don't carry a useful JsonLocation and the message
            // can echo entity field values — surface only the entity class to keep the trail.
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize entity of type " + entity.getClass().getSimpleName());
        }
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }
}
