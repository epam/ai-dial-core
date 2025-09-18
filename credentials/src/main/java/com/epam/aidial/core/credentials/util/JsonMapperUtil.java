package com.epam.aidial.core.credentials.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.stream.Collectors;

@UtilityClass
@Slf4j
public class JsonMapperUtil {

    public static final JsonMapper MAPPER = JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .configure(MapperFeature.DEFAULT_VIEW_INCLUSION, false)
                .build();

    public static String convertToString(Object data) {
        if (data == null) {
            return null;
        }

        try {
            return MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static <T> T convertToObject(String payload, TypeReference<T> type) {
        if (payload == null) {
            return null;
        }
        try {
            return MAPPER.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static <T> T convertToObject(String payload, Class<T> clazz) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(payload, clazz);
        } catch (JsonProcessingException e) {
            log.warn("Failed to convert payload to the object", e);
            if (e instanceof MismatchedInputException mismatchedInputException
                    && mismatchedInputException.getPath() != null
                    && !mismatchedInputException.getPath().isEmpty()) {
                String missingField = mismatchedInputException.getPath().stream()
                        .map(JsonMappingException.Reference::getFieldName)
                        .collect(Collectors.joining("."));
                throw new IllegalArgumentException("Missing required property '%s'".formatted(missingField));
            }
            throw new IllegalArgumentException("Provided payload do not match required schema");
        }
    }

    public static <T> T convertToObject(byte[] payload, Class<T> clazz) {
        if (payload == null) {
            return null;
        }
        try {
            return MAPPER.readValue(payload, clazz);
        } catch (IOException e) {
            log.warn("Failed to convert payload to the object", e);
            if (e instanceof MismatchedInputException mismatchedInputException && mismatchedInputException.getPath() != null && !mismatchedInputException.getPath().isEmpty()) {
                String missingField = mismatchedInputException.getPath().stream()
                        .map(JsonMappingException.Reference::getFieldName)
                        .collect(Collectors.joining("."));
                throw new IllegalArgumentException("Missing required property '%s'".formatted(missingField));
            }
            throw new IllegalArgumentException("Provided payload do not match required schema");
        }
    }

}
