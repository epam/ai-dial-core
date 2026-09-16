package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.epam.aidial.core.server.service.config.ConfigEntityCodec.BLOB_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the dual-mapper invariants after slice U.4 retired the {@code "***"} masking sentinel:
 * {@link ProxyUtil#MAPPER} drops {@code @EncryptedField} fields on serialization (via
 * {@code @JsonProperty(WRITE_ONLY)}), and {@link com.epam.aidial.core.server.service.config.ConfigEntityCodec#BLOB_MAPPER} emits the raw value
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
    void apiMapperKeepsExtraDataDropsKeyAndSecretExtraData() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");
        up.setKey("plain-key");
        up.setExtraData("extra");
        up.setSecretExtraData("secret");

        String json = ProxyUtil.MAPPER.writeValueAsString(up);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);

        assertEquals("http://x", node.get("endpoint").asText());
        assertFalse(node.has("key"), () -> "key must be absent: " + json);
        assertEquals("extra", node.get("extraData").asText(), () -> "extraData must be visible: " + json);
        assertFalse(node.has("secretExtraData"), () -> "secretExtraData must be absent: " + json);
    }

    @Test
    void apiMapperDropsNullExtraDataAndSecrets() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");

        String json = ProxyUtil.MAPPER.writeValueAsString(up);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);

        assertFalse(node.has("key"), () -> "key must be absent when null: " + json);
        assertFalse(node.has("extraData"), () -> "extraData must be absent when null: " + json);
        assertFalse(node.has("secretExtraData"), () -> "secretExtraData must be absent when null: " + json);
    }

    @Test
    void blobMapperEmitsCiphertextVerbatim() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");
        up.setKey("ENC[abcd]");
        up.setExtraData("plain-extra");
        up.setSecretExtraData("ENC[efgh]");

        String json = BLOB_MAPPER.writeValueAsString(up);
        JsonNode node = BLOB_MAPPER.readTree(json);

        assertEquals("ENC[abcd]", node.get("key").asText());
        assertEquals("plain-extra", node.get("extraData").asText());
        assertEquals("ENC[efgh]", node.get("secretExtraData").asText());
    }

    @Test
    void blobMapperRoundTripsSecretExtraData() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");
        up.setKey("ENC[k]");
        up.setExtraData("plain-extra");
        up.setSecretExtraData("ENC[xd]");

        String json = BLOB_MAPPER.writeValueAsString(up);
        Upstream restored = BLOB_MAPPER.readValue(json, Upstream.class);

        assertEquals("ENC[k]", restored.getKey());
        assertEquals("plain-extra", restored.getExtraData());
        assertEquals("ENC[xd]", restored.getSecretExtraData());
    }

    @Test
    void apiMapperDropsUpstreamSecretsInsideModel() throws Exception {
        Upstream up = new Upstream();
        up.setEndpoint("http://x");
        up.setKey("plain");
        up.setExtraData("xd");
        up.setSecretExtraData("sxd");
        Model model = new Model();
        model.setUpstreams(List.of(up));

        String json = ProxyUtil.MAPPER.writeValueAsString(model);
        JsonNode node = ProxyUtil.MAPPER.readTree(json);
        JsonNode upstream = node.get("upstreams").get(0);

        assertFalse(upstream.has("key"), () -> "upstream key must be absent: " + upstream);
        assertEquals("xd", upstream.get("extraData").asText(), () -> "upstream extraData must be visible: " + upstream);
        assertFalse(upstream.has("secretExtraData"), () -> "upstream secretExtraData must be absent: " + upstream);
    }
}
