package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.annotation.Nullable;

/**
 * Cross-upstream affinity for OpenAI Responses API {@code reasoning} items. When a deployment has at least
 * one explicitly configured upstream, a follow-up turn that echoes an earlier turn's {@code reasoning} item
 * (by its {@code id}, its {@code encrypted_content}, or both) must be routed back to the exact upstream that
 * produced it, otherwise the provider rejects the whole request. Outgoing items are stamped with the originating
 * {@link Model#getUpstreams()} entry's {@link com.epam.aidial.core.config.Upstream#getId()} ("upstream config
 * id" below - not to be confused with the provider's own response id string); incoming items are decoded back
 * to that id to force routing, then restored to their original, provider-native shape before forwarding
 * upstream.
 */
@UtilityClass
public class EncryptedContentAffinityUtil {

    private static final String ID_WRAP_PREFIX = "dialenc_";
    private static final String CONTENT_WRAP_PREFIX = "dialenc:";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public boolean hasConfiguredUpstreams(Deployment deployment) {
        // Even though there is no ambiguity when there is only one upstream,
        // we still require the upstream to be explicitly configured
        // in case the upstream list grows.
        return deployment instanceof Model model && !model.getUpstreams().isEmpty();
    }

    public boolean isEncryptedItem(JsonNode item) {
        return item != null && item.isObject() && item.path("encrypted_content").isTextual();
    }

    public boolean isReasoningItem(JsonNode item) {
        return item != null && item.isObject() && "reasoning".equals(item.path("type").asText());
    }

    /**
     * Wraps the {@code id} of a reasoning output item in place, and its {@code encrypted_content} too when
     * present. The {@code id} is wrapped even without {@code encrypted_content} (e.g. when
     * {@code include=reasoning.encrypted_content} was not requested) so a later turn that echoes the item back
     * by id alone still carries affinity. No-op if the item is not a reasoning item.
     */
    public void wrapOutputItem(JsonNode item, String encryptedUpstreamId) {
        if (!isReasoningItem(item) || !(item instanceof ObjectNode object)) {
            return;
        }
        JsonNode idNode = object.path("id");
        if (idNode.isTextual()) {
            object.put("id", wrapId(encryptedUpstreamId, idNode.asText()));
        }
        if (isEncryptedItem(object)) {
            object.put("encrypted_content", wrapContent(encryptedUpstreamId, object.path("encrypted_content").asText()));
        }
    }

    public void wrapOutputArray(JsonNode output, String encryptedUpstreamId) {
        if (!(output instanceof ArrayNode array)) {
            return;
        }
        for (JsonNode item : array) {
            wrapOutputItem(item, encryptedUpstreamId);
        }
    }

    /**
     * Unwraps every wrapped {@code id}/{@code encrypted_content} field found in {@code input}, mutating it in
     * place back to the provider-native shape, and returns the upstream config id they agreed on.
     *
     * @return the resolved upstream config id, or {@code null} if nothing wrapped was found
     * @throws HttpException 400 {@code conflicting_encrypted_content_affinity} if items disagree
     */
    @Nullable
    public String resolveAndUnwrap(ArrayNode input) {
        String resolvedUpstreamId = null;
        for (JsonNode item : input) {
            if (!(item instanceof ObjectNode object)) {
                continue;
            }
            String upstreamFromId = unwrapId(object);
            String upstreamFromContent = unwrapContent(object);
            for (String candidate : new String[] {upstreamFromId, upstreamFromContent}) {
                if (candidate == null) {
                    continue;
                }
                if (resolvedUpstreamId == null) {
                    resolvedUpstreamId = candidate;
                } else if (!resolvedUpstreamId.equals(candidate)) {
                    throw conflictingAffinityException();
                }
            }
        }
        return resolvedUpstreamId;
    }

    @Nullable
    private String unwrapId(ObjectNode object) {
        JsonNode idNode = object.path("id");
        if (!idNode.isTextual() || !idNode.asText().startsWith(ID_WRAP_PREFIX)) {
            return null;
        }
        String wrapped = idNode.asText().substring(ID_WRAP_PREFIX.length());
        try {
            byte[] decoded = DECODER.decode(wrapped);
            JsonNode payload = ProxyUtil.MAPPER.readTree(decoded);
            String encryptedUpstreamId = payload.path("u").asText(null);
            String originalId = payload.path("o").asText(null);
            if (encryptedUpstreamId == null || originalId == null) {
                return null;
            }
            object.put("id", originalId);
            return encryptedUpstreamId;
        } catch (Exception e) {
            // malformed/garbage wrapper - treat as not wrapped, pass through untouched
            return null;
        }
    }

    @Nullable
    private String unwrapContent(ObjectNode object) {
        JsonNode contentNode = object.path("encrypted_content");
        if (!contentNode.isTextual() || !contentNode.asText().startsWith(CONTENT_WRAP_PREFIX)) {
            return null;
        }
        String wrapped = contentNode.asText().substring(CONTENT_WRAP_PREFIX.length());
        int separator = wrapped.indexOf(';');
        if (separator < 0) {
            return null;
        }
        String encodedUpstreamId = wrapped.substring(0, separator);
        String originalContent = wrapped.substring(separator + 1);
        try {
            String encryptedUpstreamId = new String(DECODER.decode(encodedUpstreamId), StandardCharsets.UTF_8);
            object.put("encrypted_content", originalContent);
            return encryptedUpstreamId;
        } catch (IllegalArgumentException e) {
            // malformed/garbage wrapper - treat as not wrapped, pass through untouched
            return null;
        }
    }

    @SneakyThrows
    private String wrapId(String encryptedUpstreamId, String originalId) {
        ObjectNode payload = ProxyUtil.MAPPER.createObjectNode();
        payload.put("u", encryptedUpstreamId);
        payload.put("o", originalId);
        return ID_WRAP_PREFIX + ENCODER.encodeToString(ProxyUtil.MAPPER.writeValueAsBytes(payload));
    }

    private String wrapContent(String encryptedUpstreamId, String originalContent) {
        return CONTENT_WRAP_PREFIX
                + ENCODER.encodeToString(encryptedUpstreamId.getBytes(StandardCharsets.UTF_8))
                + ";" + originalContent;
    }

    @SneakyThrows
    private HttpException conflictingAffinityException() {
        ErrorData response = new ErrorData();
        String message = "Conflicting encrypted content affinity across input items.";
        response.getError().setMessage(message);
        response.getError().setDisplayMessage(message);
        response.getError().setCode("conflicting_encrypted_content_affinity");
        response.getError().setType("invalid_request_error");
        return new HttpException(HttpStatus.BAD_REQUEST, ProxyUtil.MAPPER.writeValueAsString(response));
    }

    @SneakyThrows
    public HttpException upstreamUnavailableException(String encryptedUpstreamId) {
        ErrorData response = new ErrorData();
        String message = "Upstream '%s' referenced by encrypted content is no longer available.".formatted(encryptedUpstreamId);
        response.getError().setMessage(message);
        response.getError().setDisplayMessage(message);
        response.getError().setCode("encrypted_content_upstream_unavailable");
        response.getError().setType("invalid_request_error");
        return new HttpException(HttpStatus.CONFLICT, ProxyUtil.MAPPER.writeValueAsString(response));
    }
}
