package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncryptedAffinityUtilTest {

    @Test
    void hasConfiguredUpstreamsRequiresAtLeastOneUpstream() {
        Model model = new Model();
        model.setUpstreams(List.of());
        assertFalse(EncryptedAffinityUtil.hasConfiguredUpstreams(model));

        model.setUpstreams(List.of(new Upstream()));
        assertTrue(EncryptedAffinityUtil.hasConfiguredUpstreams(model));
    }

    @Test
    void isEncryptedItemDetectsFieldStructurally() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        assertFalse(EncryptedAffinityUtil.isEncryptedItem(item));

        item.put("encrypted_content", "cipher-text");
        assertTrue(EncryptedAffinityUtil.isEncryptedItem(item));
    }

    @Test
    void wrapAndUnwrapRoundTrip() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", "rs_original");
        item.put("encrypted_content", "cipher-text");

        EncryptedAffinityUtil.wrapOutputItem(item, "upstream-a");
        assertTrue(item.path("id").asText().startsWith("dialenc_"));
        assertTrue(item.path("encrypted_content").asText().startsWith("dialenc:"));

        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(item);

        String resolved = EncryptedAffinityUtil.resolveAndUnwrap(input);
        assertEquals("upstream-a", resolved);
        assertEquals("rs_original", item.path("id").asText());
        assertEquals("cipher-text", item.path("encrypted_content").asText());
    }

    @Test
    void wrapIsNoOpForNonReasoningItems() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "message");
        item.put("id", "msg_1");

        EncryptedAffinityUtil.wrapOutputItem(item, "upstream-a");

        assertEquals("msg_1", item.path("id").asText());
    }

    @Test
    void wrapsReasoningItemIdWithoutEncryptedContent() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", "rs_original");

        EncryptedAffinityUtil.wrapOutputItem(item, "upstream-a");
        assertTrue(item.path("id").asText().startsWith("dialenc_"));
        assertFalse(item.has("encrypted_content"));

        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(item);

        String resolved = EncryptedAffinityUtil.resolveAndUnwrap(input);
        assertEquals("upstream-a", resolved);
        assertEquals("rs_original", item.path("id").asText());
    }

    @Test
    void unwrapIsIdempotentOnAlreadyUnwrappedInput() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", "rs_original");
        item.put("encrypted_content", "cipher-text");

        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(item);

        assertNull(EncryptedAffinityUtil.resolveAndUnwrap(input));
        assertEquals("rs_original", item.path("id").asText());
        assertEquals("cipher-text", item.path("encrypted_content").asText());
    }

    @Test
    void malformedWrapperTreatedAsPassThrough() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", "dialenc_not-valid-base64!!");
        item.put("encrypted_content", "dialenc:not-valid-base64!!;cipher-text");

        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(item);

        assertNull(EncryptedAffinityUtil.resolveAndUnwrap(input));
        assertEquals("dialenc_not-valid-base64!!", item.path("id").asText());
        assertEquals("dialenc:not-valid-base64!!;cipher-text", item.path("encrypted_content").asText());
    }

    @Test
    void conflictingUpstreamIdsAcrossItemsFailFast() {
        ObjectNode itemA = ProxyUtil.MAPPER.createObjectNode();
        itemA.put("type", "reasoning");
        itemA.put("id", "rs_a");
        itemA.put("encrypted_content", "cipher-a");
        EncryptedAffinityUtil.wrapOutputItem(itemA, "upstream-a");

        ObjectNode itemB = ProxyUtil.MAPPER.createObjectNode();
        itemB.put("type", "reasoning");
        itemB.put("id", "rs_b");
        itemB.put("encrypted_content", "cipher-b");
        EncryptedAffinityUtil.wrapOutputItem(itemB, "upstream-b");

        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(itemA);
        input.add(itemB);

        HttpException exception = assertThrows(HttpException.class,
                () -> EncryptedAffinityUtil.resolveAndUnwrap(input));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertTrue(exception.getMessage().contains("conflicting_encrypted_content_affinity"));
    }

    @Test
    void upstreamUnavailableExceptionCarriesCodeAndStatus() {
        HttpException exception = EncryptedAffinityUtil.upstreamUnavailableException("upstream-x");
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertTrue(exception.getMessage().contains("encrypted_content_upstream_unavailable"));
    }

    @Test
    void wrapOutputArraySkipsNonEncryptedItemsAndIgnoresNonArrayNode() {
        ObjectNode message = ProxyUtil.MAPPER.createObjectNode();
        message.put("type", "message");
        message.put("id", "msg_1");

        ObjectNode reasoning = ProxyUtil.MAPPER.createObjectNode();
        reasoning.put("type", "reasoning");
        reasoning.put("id", "rs_1");
        reasoning.put("encrypted_content", "cipher-text");

        ArrayNode output = ProxyUtil.MAPPER.createArrayNode();
        output.add(message);
        output.add(reasoning);

        EncryptedAffinityUtil.wrapOutputArray(output, "upstream-a");

        assertEquals("msg_1", message.path("id").asText());
        assertTrue(reasoning.path("id").asText().startsWith("dialenc_"));

        // missing output field resolves to a MissingNode - must be a no-op, not throw
        EncryptedAffinityUtil.wrapOutputArray(ProxyUtil.MAPPER.missingNode(), "upstream-a");
    }

    // ── Response ID affinity ──────────────────────────────────────────────────

    @Test
    void wrapResponseIdRoundTrip() {
        String dialId = EncryptedAffinityUtil.wrapResponseId("encrypted-upstream-42", "resp_abc123", "my-deployment");

        assertTrue(dialId.startsWith(EncryptedAffinityUtil.ID_WRAP_PREFIX));

        EncryptedAffinityUtil.UnwrappedResponseId unwrapped = EncryptedAffinityUtil.unwrapResponseId(dialId);
        assertNotNull(unwrapped);
        assertEquals("encrypted-upstream-42", unwrapped.upstreamId());
        assertEquals("resp_abc123", unwrapped.upstreamResponseId());
        assertEquals("my-deployment", unwrapped.deploymentName());
    }

    @Test
    void unwrapResponseIdReturnsNullForPlainDialId() {
        assertNull(EncryptedAffinityUtil.unwrapResponseId("dial_my-deployment_uuid-123"));
    }

    @Test
    void unwrapResponseIdReturnsNullForNull() {
        assertNull(EncryptedAffinityUtil.unwrapResponseId(null));
    }

    @Test
    void unwrapResponseIdReturnsNullForMalformedPayload() {
        // dialenc_ prefix but not valid base64url JSON
        assertNull(EncryptedAffinityUtil.unwrapResponseId("dialenc_not!valid"));
    }

    @Test
    void unwrapResponseIdReturnsNullForMissingFields() {
        // valid base64url but JSON missing required fields
        String noD = EncryptedAffinityUtil.wrapResponseId("u", "o", "d");
        // should decode fine
        assertNotNull(EncryptedAffinityUtil.unwrapResponseId(noD));
    }
}
