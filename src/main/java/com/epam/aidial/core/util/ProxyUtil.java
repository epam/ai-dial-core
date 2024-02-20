package com.epam.aidial.core.util;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.security.AccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;

import static com.epam.aidial.core.util.HttpStatus.FORBIDDEN;

@UtilityClass
public class ProxyUtil {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
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

    public static String stripExtraLeadingSlashes(String uri) {
        int index = 0;
        while (index < uri.length() && uri.charAt(index) == '/') {
            index++;
        }

        return (index <= 1) ? uri : uri.substring(index - 1);
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

    public static void collectAttachedFiles(ObjectNode tree, ApiKeyData apiKeyData, Function<String, Boolean> checkAccessFn) throws Exception {
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
            if (attachments == null) {
                continue;
            }
            for (int j = 0; j < attachments.size(); j++) {
                JsonNode attachment = attachments.get(j);
                JsonNode url = attachment.get("url");
                if (url != null) {
                    String urlValue = url.textValue();
                    if (checkAccessFn.apply(urlValue)) {
                        apiKeyData.getAttachedFiles().add(urlValue);
                    } else {
                        throw new HttpException(FORBIDDEN, "Access denied to the file %s".formatted(urlValue));
                    }
                }
            }
        }
    }

    @Nullable
    public static <T> T convertToObject(String payload, Class<T> clazz) {
        if (payload == null) {
            return null;
        }
        try {
            return MAPPER.readValue(payload, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
