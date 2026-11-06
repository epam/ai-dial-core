package com.epam.aidial.core.server.service.config;

import com.epam.aidial.core.server.util.EncryptedFieldAnnotationIntrospector;
import com.epam.aidial.core.server.util.EncryptedFieldBlobModifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.util.stream.Collectors;

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
            String path = pathOf(e);
            String suffix = path.isEmpty() ? "" : " at \"" + path + "\"";
            throw new IllegalArgumentException("Failed to parse " + cls.getSimpleName() + suffix + ": " + reasonOf(e), e);
        }
    }

    public static String serializeForBlob(Object entity) {
        try {
            return BLOB_MAPPER.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize entity of type " + entity.getClass().getSimpleName());
        }
    }

    private static String pathOf(JsonProcessingException e) {
        if (e instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            return jme.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .collect(Collectors.joining("."));
        }
        return "";
    }

    private static String reasonOf(JsonProcessingException e) {
        // the field is already named by the "at ..." prefix (built from the same getPath()), so this
        // only needs to say what went wrong, not repeat which field
        if (e instanceof UnrecognizedPropertyException) {
            return "unrecognized field";
        }
        return (e instanceof JsonMappingException jme) ? jme.getOriginalMessage() : e.getMessage();
    }
}
