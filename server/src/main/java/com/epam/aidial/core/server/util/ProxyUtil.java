package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@UtilityClass
@Slf4j
public class ProxyUtil {
    // Default response mapper. No introspector override here: @JsonProperty(WRITE_ONLY) on every
    // @EncryptedField is honored, dropping the field from serialized output. BLOB_MAPPER below
    // overrides to READ_WRITE so the blob-write path can persist the ciphertext.
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    public static final JsonMapper BLOB_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .annotationIntrospector(new EncryptedFieldAnnotationIntrospector())
            .addModule(new SimpleModule().setSerializerModifier(new EncryptedFieldBlobModifier()))
            .build();

    private static final MultiMap TRACE_HEADERS = MultiMap.caseInsensitiveMultiMap()
            .add("traceparent", "whatever")
            .add("tracestate", "whatever");
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
            .add(Proxy.HEADER_API_KEY, "whatever")
            .add(Proxy.HEADER_X_API_KEY, "whatever");
    public static final String METADATA_PREFIX = "metadata/";

    public static void copyHeaders(MultiMap from, MultiMap to) {
        copyHeaders(from, to, MultiMap.caseInsensitiveMultiMap());
    }

    public static void copyHeaders(MultiMap from, MultiMap to, MultiMap excludeHeaders) {
        for (Map.Entry<String, String> entry : from.entries()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (!HOP_BY_HOP_HEADERS.contains(key) && !TRACE_HEADERS.contains(key) && !excludeHeaders.contains(key)) {
                to.add(key, value);
            }
        }
    }

    public static void setOverrideNameHeader(MultiMap headers, Deployment deployment) {
        if (deployment == null) {
            return;
        }
        String overrideName = deployment.getOverrideName();
        if (overrideName != null) {
            headers.set(Proxy.HEADER_OVERRIDE_NAME, overrideName);
        }
    }

    public static void setOverrideNameHeader(HttpClientRequest request, Deployment deployment) {
        setOverrideNameHeader(request.headers(), deployment);
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
    public static <T> T convertToObject(String payload, Class<T> clazz) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(payload, clazz);
        } catch (JsonProcessingException e) {
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

    @Nullable
    public static <T> T convertToObject(byte[] payload, Class<T> clazz) {
        if (payload == null) {
            return null;
        }
        try {
            return MAPPER.readValue(payload, clazz);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Nullable
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

    /**
     * Parses the payload into a JSON tree for {@link #hasTopLevelField(JsonNode, String...)} checks,
     * so a body probed for several fields is parsed once. Returns null for blank or unparseable payloads.
     */
    public static JsonNode parseTree(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(payload);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Returns true if the JSON payload is an object containing any of the given top-level field names.
     * Lets a write path tell "field omitted" apart from "field sent (even empty)", which a deserialized
     * POJO cannot. Returns false for null or non-object payloads.
     */
    public static boolean hasTopLevelField(JsonNode payload, String... fieldNames) {
        if (payload == null || !payload.isObject()) {
            return false;
        }
        for (String fieldName : fieldNames) {
            if (payload.has(fieldName)) {
                return true;
            }
        }
        return false;
    }

    public static <T> boolean processChain(T item, List<BaseRequestFunction<T>> chain) {
        boolean result = false;
        for (BaseRequestFunction<T> fn : chain) {
            if (fn.apply(item)) {
                result = true;
            }
        }
        return result;
    }

    public static EtagHeader etag(HttpServerRequest request) {
        return EtagHeader.fromHeader(request.getHeader(HttpHeaders.IF_MATCH), request.getHeader(HttpHeaders.IF_NONE_MATCH), request.method().name());
    }

    public record MetadataQuery(String token, int limit, boolean recursive) {
    }

    public static MetadataQuery metadataQuery(HttpServerRequest request) {
        String token = request.getParam("token");
        int limit = Integer.parseInt(request.getParam("limit", "100"));
        boolean recursive = Boolean.parseBoolean(request.getParam("recursive", "false"));
        if (limit < 0 || limit > 1000) {
            throw new IllegalArgumentException("Limit is out of allowed range");
        }
        return new MetadataQuery(token, limit, recursive);
    }

    public String generateReference() {
        return UUID.randomUUID().toString();
    }

    @Nullable
    public String getClientIpAddress(HttpServerRequest request, int proxyCount) {
        String val = request.getHeader("X-Forwarded-For");
        if (StringUtils.isEmpty(val) || proxyCount == 0) {
            return getRealClientRemoteAddress(request);
        }
        String[] ips = val.split(",");
        int index = ips.length - proxyCount;
        if (index < 0) {
            return getRealClientRemoteAddress(request);
        }
        return ips[index];
    }

    @Nullable
    private String getRealClientRemoteAddress(HttpServerRequest request) {
        SocketAddress socketAddress = request.connection().remoteAddress(true);
        if (socketAddress == null || !socketAddress.isInetSocket()) {
            return null;
        }
        return socketAddress.host();
    }

    public void copyResponse(HttpServerResponse response, HttpClientResponse proxyResponse) {
        response.setStatusCode(proxyResponse.statusCode());
        copyHeaders(proxyResponse.headers(), response.headers());
    }

    public void handleChunkedResponse(HttpServerResponse response, HttpClientResponse proxyResponse) {
        response.setChunked(true);
        copyResponse(response, proxyResponse);
        String contentType = proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE);
        if (Strings.CI.contains(contentType, "text/event-stream")) {
            response.putHeader("X-Accel-Buffering", "no");
        }
    }

    public ObjectNode parseObject(Buffer buffer) throws IOException {
        try (InputStream stream = new ByteBufInputStream(buffer.getByteBuf())) {
            JsonNode node = ProxyUtil.MAPPER.readTree(stream);
            if (!node.isObject()) {
                throw new IllegalArgumentException("Invalid json object");
            }
            return (ObjectNode) node;
        }
    }
}
