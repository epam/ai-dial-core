package com.epam.aidial.core.server.service.policy;

import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.config.policy.PolicyAssignmentValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.experimental.UtilityClass;

/**
 * Strict JSON mapping of policies and assignments: unknown fields are rejected, and a number is never coerced
 * (a priority of {@code 10.5} or {@code "10"} is an error, not {@code 10}).
 */
@UtilityClass
public class PolicyRecordCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // an amount of 500 must not read back as 5E+2
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .build();

    public static Policy parsePolicy(JsonNode node) {
        return parse(node, Policy.class);
    }

    public static PolicyAssignment parseAssignment(JsonNode node) {
        if (node.isObject()) {
            String error = PolicyAssignmentValidator.rejectPolicyFieldsOn(node);
            if (error != null) {
                throw new IllegalArgumentException("Failed to parse PolicyAssignment: " + error);
            }
        }
        return parse(node, PolicyAssignment.class);
    }

    public static Policy readPolicy(String body) {
        return read(body, Policy.class);
    }

    public static PolicyAssignment readAssignment(String body) {
        return read(body, PolicyAssignment.class);
    }

    public static String write(Object record) {
        try {
            return MAPPER.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + record.getClass().getSimpleName());
        }
    }

    public static JsonNode toTree(Object record) {
        return MAPPER.valueToTree(record);
    }

    private static <T> T parse(JsonNode node, Class<T> type) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("Failed to parse " + type.getSimpleName() + ": a JSON object is expected");
        }
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(describe(type, e), e);
        }
    }

    private static <T> T read(String body, Class<T> type) {
        try {
            return MAPPER.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored " + type.getSimpleName() + " is malformed", e);
        }
    }

    private static String pathOf(JsonMappingException e) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : e.getPath()) {
            if (reference.getFieldName() == null) {
                path.append('[').append(reference.getIndex()).append(']');
            } else {
                path.append(path.isEmpty() ? "" : ".").append(reference.getFieldName());
            }
        }
        return path.toString();
    }

    private static String describe(Class<?> type, JsonProcessingException e) {
        String path = e instanceof JsonMappingException mapping ? pathOf(mapping) : "";
        String where = path.isEmpty() ? "" : " at \"" + path + "\"";
        String reason = e instanceof UnrecognizedPropertyException ? "unrecognized field"
                : e instanceof JsonMappingException mapping ? mapping.getOriginalMessage() : e.getMessage();
        return "Failed to parse " + type.getSimpleName() + where + ": " + reason;
    }
}
