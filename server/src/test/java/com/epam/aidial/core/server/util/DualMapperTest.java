package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the dual-mapper invariants for slice 2S.10:
 * {@link ProxyUtil#MAPPER} masks {@code @EncryptedField} values as {@code "***"} (and preserves
 * {@code @JsonProperty(WRITE_ONLY)} for null values), while {@link ProxyUtil#BLOB_MAPPER} emits
 * the raw value verbatim — including {@code ENC[...]} envelopes round-tripped through blob
 * storage.
 */
class DualMapperTest {

    @Test
    void apiMapperMasksKeyKey() throws Exception {
        Key k = new Key();
        k.setKey("plain-secret");
        k.setProject("p");

        String json = ProxyUtil.MAPPER.writeValueAsString(k);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);

        assertEquals("***", node.get("key").asText());
        assertEquals("p", node.get("project").asText());
    }

    @Test
    void apiMapperMasksUpstreamKeyAndExtraData() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");
        up.setKey("plain-key");
        up.setExtraData("extra");

        String json = ProxyUtil.MAPPER.writeValueAsString(up);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);

        assertEquals("http://x", node.get("endpoint").asText());
        assertEquals("***", node.get("key").asText());
        assertEquals("***", node.get("extraData").asText());
    }

    @Test
    void apiMapperPreservesWriteOnlyForNullKey() throws Exception {
        // Upstream.key carries @JsonProperty(WRITE_ONLY); when null, the masking modifier
        // mirrors the WRITE_ONLY shape and skips emission entirely.
        Upstream up = new Upstream();
        up.setEndpoint("http://x");

        String json = ProxyUtil.MAPPER.writeValueAsString(up);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);

        assertFalse(node.has("key"), () -> "key must be absent when null: " + json);
    }

    @Test
    void apiMapperEmitsNullForUnsetExtraData() throws Exception {
        // Upstream.extraData has no @JsonProperty(WRITE_ONLY), so null serializes as null.
        Upstream up = new Upstream();
        up.setEndpoint("http://x");

        String json = ProxyUtil.MAPPER.writeValueAsString(up);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);

        assertTrue(node.has("extraData"));
        assertTrue(node.get("extraData").isNull());
    }

    @Test
    void blobMapperEmitsCiphertextVerbatim() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");
        up.setKey("ENC[abcd]");
        up.setExtraData("ENC[efgh]");

        String json = ProxyUtil.BLOB_MAPPER.writeValueAsString(up);
        JsonNode node = ProxyUtil.BLOB_MAPPER.readTree(json);

        assertEquals("ENC[abcd]", node.get("key").asText());
        assertEquals("ENC[efgh]", node.get("extraData").asText());
    }

    @Test
    void blobMapperRoundTripsExtraData() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");
        up.setKey("ENC[k]");
        up.setExtraData("ENC[xd]");

        String json = ProxyUtil.BLOB_MAPPER.writeValueAsString(up);
        Upstream restored = ProxyUtil.BLOB_MAPPER.readValue(json, Upstream.class);

        assertEquals("ENC[k]", restored.getKey());
        assertEquals("ENC[xd]", restored.getExtraData());
    }

    @Test
    void apiMapperMasksUpstreamKeysInsideModel() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");
        up.setKey("plain");
        up.setExtraData("xd");
        Model model = new Model();
        model.setUpstreams(List.of(up));

        String json = ProxyUtil.MAPPER.writeValueAsString(model);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);
        JsonNode upstream = node.get("upstreams").get(0);

        assertEquals("***", upstream.get("key").asText());
        assertEquals("***", upstream.get("extraData").asText());
    }
}
