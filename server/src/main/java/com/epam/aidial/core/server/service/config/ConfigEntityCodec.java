package com.epam.aidial.core.server.service.config;

import com.epam.aidial.core.server.util.EncryptedFieldAnnotationIntrospector;
import com.epam.aidial.core.server.util.EncryptedFieldBlobModifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Pure JSON (de)serialization helpers for admin config entities, shared by
 * {@code ConfigResourceController}, {@code ConfigApplyService} and {@code ConfigValidateService}.
 * Deliberately dependency-free (no service/controller state) so both layers can depend on it.
 */
public final class ConfigEntityCodec {

    public static final JsonMapper BLOB_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .annotationIntrospector(new EncryptedFieldAnnotationIntrospector())
            .addModule(new SimpleModule().setSerializerModifier(new EncryptedFieldBlobModifier()))
            .build();

    private ConfigEntityCodec() {
    }

    public static <T> T treeToEntity(JsonNode node, Class<T> cls) {
        try {
            return BLOB_MAPPER.treeToValue(node, cls);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse entity at " + locationOf(e));
        }
    }

    public static String serializeForBlob(Object entity) {
        try {
            return BLOB_MAPPER.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize entity of type " + entity.getClass().getSimpleName());
        }
    }

    private static String locationOf(JsonProcessingException e) {
        return e.getLocation() == null
                ? "unknown location"
                : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
    }
}
