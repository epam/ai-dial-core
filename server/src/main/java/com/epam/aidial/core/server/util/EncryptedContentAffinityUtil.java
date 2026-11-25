package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Cross-upstream affinity for OpenAI Responses API {@code reasoning} items. When a deployment has at least
 * one explicitly configured upstream, a follow-up turn that echoes an earlier turn's {@code reasoning} item
 * (by its {@code id}, its {@code encrypted_content}, or both) must be routed back to the exact upstream that
 * produced it, otherwise the provider rejects the whole request. Outgoing items are stamped with the originating
 * {@link Model#getUpstreams()} entry's {@link com.epam.aidial.core.config.Upstream#getId()} ("upstream config
 * id" below - not to be confused with the provider's own response id string); incoming items are decoded back
 * to that id to force routing, then restored to their original, provider-native shape before forwarding
 * upstream. Reasoning items the winning upstream could not decrypt are dropped.
 */
@Slf4j
@UtilityClass
public class EncryptedContentAffinityUtil {

    private static final String ID_WRAP_PREFIX = "dialenc_";
    private static final String CONTENT_WRAP_PREFIX = "dialenc:";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private record ResolvedAffinity(@Nullable String upstreamId, @Nullable String deploymentName) {
        private static final ResolvedAffinity EMPTY = new ResolvedAffinity(null, null);
    }

    /**
     * The outcome of {@link #resolveAndUnwrap}.
     *
     * @param upstreamId      the upstream config id to route to; {@code null} if no kept item is stamped
     * @param deploymentNames the deployment names recorded in the stamps of the kept items
     */
    public record Resolution(@Nullable String upstreamId, Set<String> deploymentNames) {
        public static final Resolution NONE = new Resolution(null, Set.of());
    }

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
    public void wrapOutputItem(JsonNode item, String encryptedUpstreamId, String deploymentName) {
        if (!isReasoningItem(item) || !(item instanceof ObjectNode object)) {
            return;
        }
        JsonNode idNode = object.path("id");
        if (idNode.isTextual()) {
            object.put("id", wrapId(encryptedUpstreamId, deploymentName, idNode.asText()));
        }
        if (isEncryptedItem(object)) {
            object.put("encrypted_content", wrapContent(encryptedUpstreamId, deploymentName, object.path("encrypted_content").asText()));
        }
    }

    public void wrapSseItemId(ObjectNode event, String encryptedUpstreamId, String deploymentName) {
        JsonNode itemIdNode = event.path("item_id");
        if (itemIdNode.isTextual()) {
            event.put("item_id", wrapId(encryptedUpstreamId, deploymentName, itemIdNode.asText()));
        }
    }

    public void wrapOutputArray(JsonNode output, String encryptedUpstreamId, String deploymentName) {
        if (!(output instanceof ArrayNode array)) {
            return;
        }
        for (JsonNode item : array) {
            wrapOutputItem(item, encryptedUpstreamId, deploymentName);
        }
    }

    /**
     * Unwraps every wrapped {@code id}/{@code encrypted_content} field found in {@code input}, mutating it in
     * place back to the provider-native shape, and picks the upstream the request is routed to.
     *
     * <p>The winner is the upstream config id of the first kept item. A {@code reasoning} item is removed from
     * {@code input} when the winning upstream could not decrypt it: it is stamped by an upstream that is not in
     * {@code configuredUpstreamIds}, by an upstream other than the winner, or by two different upstreams (its
     * {@code id} and its {@code encrypted_content} disagree). This happens when a client drops reasoning items from
     * a turn, so that turn is routed to an arbitrary upstream whose reasoning is then echoed together with the earlier
     * turns' reasoning, or when an upstream was removed from the model. One warning is logged per request.
     *
     * @param configuredUpstreamIds the ids of the model's current {@code upstreams}
     * @return the upstream to route to and the deployment names of the kept stamps; {@link Resolution#NONE} if
     *     no kept item is stamped
     */
    public Resolution resolveAndUnwrap(ArrayNode input, Set<String> configuredUpstreamIds) {
        Set<String> deploymentNames = new LinkedHashSet<>();
        Set<String> droppedUpstreamIds = new LinkedHashSet<>();
        String winningUpstreamId = null;
        int dropped = 0;
        for (int i = 0; i < input.size(); i++) {
            if (!(input.get(i) instanceof ObjectNode object) || !isReasoningItem(object)) {
                continue;
            }
            ResolvedAffinity byId = unwrapId(object);
            ResolvedAffinity byContent = unwrapContent(object);
            String idUpstream = byId.upstreamId();
            String contentUpstream = byContent.upstreamId();
            boolean consistent = idUpstream == null || contentUpstream == null || idUpstream.equals(contentUpstream);
            String stamped = idUpstream != null ? idUpstream : contentUpstream;
            boolean keep = consistent && (stamped == null
                    || configuredUpstreamIds.contains(stamped) && (winningUpstreamId == null || winningUpstreamId.equals(stamped)));
            if (!keep) {
                dropped++;
                addIfNotNull(droppedUpstreamIds, idUpstream);
                addIfNotNull(droppedUpstreamIds, contentUpstream);
                input.remove(i--);
                continue;
            }
            if (stamped != null) {
                winningUpstreamId = stamped;
                addIfNotNull(deploymentNames, byId.deploymentName());
                addIfNotNull(deploymentNames, byContent.deploymentName());
            }
        }
        if (dropped > 0) {
            log.warn("Dropped {} reasoning item(s) stamped by upstreams {}: the request is routed to upstream '{}', which cannot decrypt them",
                    dropped, droppedUpstreamIds, winningUpstreamId);
        }
        return winningUpstreamId == null ? Resolution.NONE : new Resolution(winningUpstreamId, Set.copyOf(deploymentNames));
    }

    private void addIfNotNull(Set<String> set, @Nullable String value) {
        if (value != null) {
            set.add(value);
        }
    }

    private ResolvedAffinity unwrapId(ObjectNode object) {
        JsonNode idNode = object.path("id");
        if (!idNode.isTextual() || !idNode.asText().startsWith(ID_WRAP_PREFIX)) {
            return ResolvedAffinity.EMPTY;
        }
        String wrapped = idNode.asText().substring(ID_WRAP_PREFIX.length());
        try {
            byte[] decoded = DECODER.decode(wrapped);
            JsonNode payload = ProxyUtil.MAPPER.readTree(decoded);
            String encryptedUpstreamId = payload.path("u").asText(null);
            String originalId = payload.path("o").asText(null);
            if (encryptedUpstreamId == null || originalId == null) {
                return ResolvedAffinity.EMPTY;
            }
            object.put("id", originalId);
            return new ResolvedAffinity(encryptedUpstreamId, payload.path("d").asText(null));
        } catch (Exception e) {
            // malformed/garbage wrapper - treat as not wrapped, pass through untouched
            return ResolvedAffinity.EMPTY;
        }
    }

    private ResolvedAffinity unwrapContent(ObjectNode object) {
        JsonNode contentNode = object.path("encrypted_content");
        if (!contentNode.isTextual() || !contentNode.asText().startsWith(CONTENT_WRAP_PREFIX)) {
            return ResolvedAffinity.EMPTY;
        }
        String wrapped = contentNode.asText().substring(CONTENT_WRAP_PREFIX.length());
        int separator = wrapped.indexOf(';');
        if (separator < 0) {
            return ResolvedAffinity.EMPTY;
        }
        String encodedPayload = wrapped.substring(0, separator);
        String originalContent = wrapped.substring(separator + 1);
        try {
            byte[] decoded = DECODER.decode(encodedPayload);
            JsonNode payload = ProxyUtil.MAPPER.readTree(decoded);
            String encryptedUpstreamId = payload.path("u").asText(null);
            if (encryptedUpstreamId == null) {
                return ResolvedAffinity.EMPTY;
            }
            object.put("encrypted_content", originalContent);
            return new ResolvedAffinity(encryptedUpstreamId, payload.path("d").asText(null));
        } catch (Exception e) {
            // malformed/garbage wrapper - treat as not wrapped, pass through untouched
            return ResolvedAffinity.EMPTY;
        }
    }

    @SneakyThrows
    private String wrapId(String encryptedUpstreamId, String deploymentName, String originalId) {
        ObjectNode payload = ProxyUtil.MAPPER.createObjectNode();
        payload.put("u", encryptedUpstreamId);
        payload.put("d", deploymentName);
        payload.put("o", originalId);
        return ID_WRAP_PREFIX + ENCODER.encodeToString(ProxyUtil.MAPPER.writeValueAsBytes(payload));
    }

    @SneakyThrows
    private String wrapContent(String encryptedUpstreamId, String deploymentName, String originalContent) {
        ObjectNode payload = ProxyUtil.MAPPER.createObjectNode();
        payload.put("u", encryptedUpstreamId);
        payload.put("d", deploymentName);
        return CONTENT_WRAP_PREFIX
                + ENCODER.encodeToString(ProxyUtil.MAPPER.writeValueAsBytes(payload))
                + ";" + originalContent;
    }
}
