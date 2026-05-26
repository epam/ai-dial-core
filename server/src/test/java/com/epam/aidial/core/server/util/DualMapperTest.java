package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the dual-mapper invariants after slice U.4 retired the {@code "***"} masking sentinel:
 * {@link ProxyUtil#MAPPER} drops {@code @EncryptedField} fields on serialization (via
 * {@code @JsonProperty(WRITE_ONLY)}), and {@link ProxyUtil#BLOB_MAPPER} emits the raw value
 * verbatim — including {@code ENC[...]} envelopes round-tripped through blob storage.
 */
class DualMapperTest {

    @Test
    void apiMapperDropsKeyKey() throws Exception {
        Key k = new Key();
        k.setKey("plain-secret");
        k.setProject("p");

        String json = ProxyUtil.MAPPER.writeValueAsString(k);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);

        assertFalse(node.has("key"), () -> "key must be absent on response: " + json);
        assertEquals("p", node.get("project").asText());
    }

    @Test
    void apiMapperDropsUpstreamKeyAndExtraData() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");
        up.setKey("plain-key");
        up.setExtraData("extra");

        String json = ProxyUtil.MAPPER.writeValueAsString(up);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);

        assertEquals("http://x", node.get("endpoint").asText());
        assertFalse(node.has("key"), () -> "key must be absent: " + json);
        assertFalse(node.has("extraData"), () -> "extraData must be absent: " + json);
    }

    @Test
    void apiMapperDropsNullSecrets() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");

        String json = ProxyUtil.MAPPER.writeValueAsString(up);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);

        assertFalse(node.has("key"), () -> "key must be absent when null: " + json);
        assertFalse(node.has("extraData"), () -> "extraData must be absent when null: " + json);
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
    void apiMapperDropsUpstreamSecretsInsideModel() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");
        up.setKey("plain");
        up.setExtraData("xd");
        Model model = new Model();
        model.setUpstreams(List.of(up));

        String json = ProxyUtil.MAPPER.writeValueAsString(model);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);
        JsonNode upstream = node.get("upstreams").get(0);

        assertFalse(upstream.has("key"), () -> "upstream key must be absent: " + upstream);
        assertFalse(upstream.has("extraData"), () -> "upstream extraData must be absent: " + upstream);
    }
}
