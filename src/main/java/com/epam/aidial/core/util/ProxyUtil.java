package com.epam.aidial.core.util;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.function.BaseFunction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@UtilityClass
@Slf4j
public class ProxyUtil {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    public static final JsonMapper SNAKE_CASE_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private static final MultiMap HOP_BY_HOP_HEADERS = MultiMap.caseInsensitiveMultiMap()
            .add(HttpHeaders.CONNECTION, "whatever")
            .add(HttpHeaders.KEEP_ALIVE, "whatever")
            .add(HttpHeaders.HOST, "whatever")
            .add(HttpHeaders.PROXY_AUTHENTICATE, "whatever")
            .add(HttpHeaders.PROXY_AUTHORIZATION, "whatever")
            .add("te", "whatever")
            .add("trailer", "whatever")
            .add(HttpHeaders.TRANSFER_ENCODING, "whatever")
            .add(HttpHeaders.UPGRADE, "whatever")
            .add(HttpHeaders.CONTENT_LENGTH, "whatever")
            .add(HttpHeaders.ACCEPT_ENCODING, "whatever")
            .add(Proxy.HEADER_API_KEY, "whatever");
    public static final String METADATA_PREFIX = "metadata/";

    public static void copyHeaders(MultiMap from, MultiMap to) {
        copyHeaders(from, to, MultiMap.caseInsensitiveMultiMap());
    }

    public static void copyHeaders(MultiMap from, MultiMap to, MultiMap excludeHeaders) {
        for (Map.Entry<String, String> entry : from.entries()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (!HOP_BY_HOP_HEADERS.contains(key) && !excludeHeaders.contains(key)) {
                to.add(key, value);
            }
        }
    }

    public static int contentLength(HttpServerRequest request, int defaultValue) {
        return contentLength(request.headers(), defaultValue);
    }

    public static int contentLength(HttpClientResponse request, int defaultValue) {
        MultiMap header = request.headers();
        return contentLength(header, defaultValue);
    }

    private static int contentLength(MultiMap header, int defaultValue) {
        String text = header.get(HttpHeaders.CONTENT_LENGTH);
        if (text != null) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return defaultValue;
    }

    public static void collectAttachedFiles(ObjectNode tree, Consumer<String> consumer) {
        ArrayNode messages = (ArrayNode) tree.get("messages");
        if (messages == null) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            JsonNode customContent = message.get("custom_content");
            if (customContent == null) {
                continue;
            }
            ArrayNode attachments = (ArrayNode) customContent.get("attachments");
            if (attachments != null) {
                for (int j = 0; j < attachments.size(); j++) {
                    JsonNode attachment = attachments.get(j);
                    collectAttachedFile(attachment, consumer);
                }
            }
            ArrayNode stages = (ArrayNode) customContent.get("stages");
            if (stages != null) {
                for (int j = 0; j < stages.size(); j++) {
                    JsonNode stage = stages.get(j);
                    attachments = (ArrayNode) stage.get("attachments");
                    if (attachments == null) {
                        continue;
                    }
                    for (int k = 0; k < attachments.size(); k++) {
                        JsonNode attachment = attachments.get(k);
                        collectAttachedFile(attachment, consumer);
                    }
                }
            }
        }
    }

    private static void collectAttachedFile(JsonNode attachment, Consumer<String> consumer) {
        JsonNode urlNode = attachment.get("url");
        if (urlNode == null) {
            return;
        }

        String url = urlNode.textValue();

        JsonNode typeNode = attachment.get("type");
        if (typeNode != null && typeNode.textValue().equals(MetadataBase.MIME_TYPE)) {
            if (!url.startsWith(METADATA_PREFIX)) {
                throw new IllegalArgumentException("Url of metadata attachment must start with metadata/: " + url);
            }
            url = url.substring(METADATA_PREFIX.length());
        }

        consumer.accept(url);
    }

    public static <T> T convertToObject(Buffer json, Class<T> clazz) {
        try {
            String text = json.toString(StandardCharsets.UTF_8);
            return MAPPER.readValue(text, clazz);
        } catch (Throwable e) {
            throw new IllegalArgumentException("Failed to parse json: " + e.getMessage());
        }
    }

    @Nullable
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

    @Nullable
    public static <T> T convertToObject(String payload, Class<T> clazz, boolean snakeCase) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return snakeCase ? SNAKE_CASE_MAPPER.readValue(payload, clazz) : MAPPER.readValue(payload, clazz);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert payload to the object", e);
            if (e instanceof MismatchedInputException mismatchedInputException && mismatchedInputException.getPath() != null && !mismatchedInputException.getPath().isEmpty()) {
                String missingField = mismatchedInputException.getPath().stream()
                        .map(JsonMappingException.Reference::getFieldName)
                        .collect(Collectors.joining("."));
                throw new IllegalArgumentException("Missing required property '%s'".formatted(missingField));
            }
            throw new IllegalArgumentException("Provided payload do not match required schema");
        }
    }

    @Nullable
    public static <T> T convertToObject(String payload, Class<T> clazz) {
        return convertToObject(payload, clazz, false);
    }

    @Nullable
    public static String convertToString(Object data, boolean snakeCase) {
        if (data == null) {
            return null;
        }

        try {
            return snakeCase ? SNAKE_CASE_MAPPER.writeValueAsString(data) : MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Nullable
    public static String convertToString(Object data) {
        return convertToString(data, false);
    }

    public static <T> Throwable processChain(T item, List<BaseFunction<T>> chain) {
        for (BaseFunction<T> fn : chain) {
            Throwable error = fn.apply(item);
            if (error != null) {
                return error;
            }
        }
        return null;
    }
}
