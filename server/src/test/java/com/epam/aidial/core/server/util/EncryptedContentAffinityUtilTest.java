package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncryptedContentAffinityUtilTest {

    private static final Model MODEL = model("deploy-x", "upstream-a", "upstream-b", "upstream-c");

    private static Model model(String name, String... upstreamIds) {
        Model model = new Model();
        model.setName(name);
        model.setUpstreams(Arrays.stream(upstreamIds).map(id -> {
            Upstream upstream = new Upstream();
            upstream.setId(id);
            return upstream;
        }).toList());
        return model;
    }

    @Test
    void hasConfiguredUpstreamsRequiresAtLeastOneUpstream() {
        Model model = new Model();
        model.setUpstreams(List.of());
        assertFalse(EncryptedContentAffinityUtil.hasConfiguredUpstreams(model));

        model.setUpstreams(List.of(new Upstream()));
        assertTrue(EncryptedContentAffinityUtil.hasConfiguredUpstreams(model));
    }

    @Test
    void isEncryptedItemDetectsFieldStructurally() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        assertFalse(EncryptedContentAffinityUtil.isEncryptedItem(item));

        item.put("encrypted_content", "cipher-text");
        assertTrue(EncryptedContentAffinityUtil.isEncryptedItem(item));
    }

    @Test
    void wrapAndUnwrapRoundTrip() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", "rs_original");
        item.put("encrypted_content", "cipher-text");

        EncryptedContentAffinityUtil.wrapOutputItem(item, "upstream-a", "deploy-x");
        assertTrue(item.path("id").asText().startsWith("dialenc_"));
        assertTrue(item.path("encrypted_content").asText().startsWith("dialenc:"));

        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(item);

        String resolved = EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL);
        assertEquals("upstream-a", resolved);
        assertEquals("rs_original", item.path("id").asText());
        assertEquals("cipher-text", item.path("encrypted_content").asText());
    }

    @Test
    void wrapIsNoOpForNonReasoningItems() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "message");
        item.put("id", "msg_1");

        EncryptedContentAffinityUtil.wrapOutputItem(item, "upstream-a", "deploy-x");

        assertEquals("msg_1", item.path("id").asText());
    }

    @Test
    void wrapsReasoningItemIdWithoutEncryptedContent() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", "rs_original");

        EncryptedContentAffinityUtil.wrapOutputItem(item, "upstream-a", "deploy-x");
        assertTrue(item.path("id").asText().startsWith("dialenc_"));
        assertFalse(item.has("encrypted_content"));

        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(item);

        assertEquals("upstream-a", EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL));
    }

    @Test
    void unwrapIsIdempotentOnAlreadyUnwrappedInput() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", "rs_original");
        item.put("encrypted_content", "cipher-text");

        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(item);

        assertNull(EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL));
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

        assertNull(EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL));
        assertEquals("dialenc_not-valid-base64!!", item.path("id").asText());
        assertEquals("dialenc:not-valid-base64!!;cipher-text", item.path("encrypted_content").asText());
    }

    @Test
    void reasoningItemFromAnotherUpstreamIsDropped() {
        ObjectNode itemA = ProxyUtil.MAPPER.createObjectNode();
        itemA.put("type", "reasoning");
        itemA.put("id", "rs_a");
        itemA.put("encrypted_content", "cipher-a");
        EncryptedContentAffinityUtil.wrapOutputItem(itemA, "upstream-a", "deploy-x");

        ObjectNode itemB = ProxyUtil.MAPPER.createObjectNode();
        itemB.put("type", "reasoning");
        itemB.put("id", "rs_b");
        itemB.put("encrypted_content", "cipher-b");
        EncryptedContentAffinityUtil.wrapOutputItem(itemB, "upstream-b", "deploy-x");

        ObjectNode message = ProxyUtil.MAPPER.createObjectNode();
        message.put("role", "user");
        message.put("content", "Continue");

        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(itemA);
        input.add(itemB);
        input.add(message);

        String resolved = EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL);
        assertEquals("upstream-a", resolved);
        assertEquals(2, input.size());
        assertEquals("rs_a", input.get(0).path("id").asText());
        assertEquals("cipher-a", input.get(0).path("encrypted_content").asText());
        assertEquals("Continue", input.get(1).path("content").asText());
    }

    @Test
    void deploymentMismatchDoesNotThrow() {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", "rs_original");
        item.put("encrypted_content", "cipher-text");
        EncryptedContentAffinityUtil.wrapOutputItem(item, "upstream-a", "deploy-a");

        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(item);

        // a deployment name that differs from the model name only logs a warning
        String resolved = EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL);
        assertEquals("upstream-a", resolved);
        assertEquals("rs_original", item.path("id").asText());
        assertEquals("cipher-text", item.path("encrypted_content").asText());
    }

    @Test
    void wrapSseItemIdWrapsItemIdField() {
        ObjectNode event = ProxyUtil.MAPPER.createObjectNode();
        event.put("type", "response.reasoning_summary_part.added");
        event.put("item_id", "rs_original");
        event.put("output_index", 0);

        EncryptedContentAffinityUtil.wrapSseItemId(event, "upstream-a", "deploy-x");
        assertTrue(event.path("item_id").asText().startsWith("dialenc_"));

        // verify that the wrapped item_id round-trips through resolveAndUnwrap by checking it
        // decodes as a valid wrapped id wrapping "rs_original"
        ObjectNode syntheticItem = ProxyUtil.MAPPER.createObjectNode();
        syntheticItem.put("type", "reasoning");
        syntheticItem.put("id", event.path("item_id").asText());
        ArrayNode input = ProxyUtil.MAPPER.createArrayNode();
        input.add(syntheticItem);

        assertEquals("upstream-a", EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL));
        assertEquals("rs_original", syntheticItem.path("id").asText());
    }

    @Test
    void wrapSseItemIdIsNoOpWhenItemIdAbsent() {
        ObjectNode event = ProxyUtil.MAPPER.createObjectNode();
        event.put("type", "response.reasoning_summary_part.added");

        EncryptedContentAffinityUtil.wrapSseItemId(event, "upstream-a", "deploy-x");

        assertFalse(event.has("item_id"));
    }

    @Test
    void reasoningItemFromUnconfiguredUpstreamIsDropped() {
        ObjectNode removed = wrapped("rs_old", "cipher-old", "upstream-old");
        ObjectNode kept = wrapped("rs_a", "cipher-a", "upstream-a");
        ArrayNode input = array(removed, kept, user());

        String resolved = EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL);

        assertEquals("upstream-a", resolved);
        assertEquals(2, input.size());
        assertEquals("rs_a", input.get(0).path("id").asText());
        assertEquals("Continue", input.get(1).path("content").asText());
    }

    @Test
    void onlyUnconfiguredUpstreamItemsLeaveNoRouting() {
        ArrayNode input = array(wrapped("rs_old", "cipher-old", "upstream-old"), user());

        assertNull(EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL));
        assertEquals(1, input.size());
        assertEquals("Continue", input.get(0).path("content").asText());
    }

    @Test
    void selfInconsistentItemIsDroppedAndDoesNotPickTheWinner() {
        ObjectNode inconsistent = ProxyUtil.MAPPER.createObjectNode();
        inconsistent.put("type", "reasoning");
        inconsistent.put("id", "rs_x");
        inconsistent.put("encrypted_content", "cipher-x");
        EncryptedContentAffinityUtil.wrapOutputItem(inconsistent, "upstream-a", "deploy-x");
        ObjectNode other = wrapped("rs_y", "cipher-y", "upstream-b");
        inconsistent.put("encrypted_content", other.path("encrypted_content").asText());

        ObjectNode kept = wrapped("rs_b", "cipher-b", "upstream-b");
        ArrayNode input = array(inconsistent, kept);

        String resolved = EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL);

        assertEquals("upstream-b", resolved);
        assertEquals(1, input.size());
        assertEquals("rs_b", input.get(0).path("id").asText());
    }

    @Test
    void unstampedReasoningItemBeforeStampedOneIsKept() {
        ObjectNode plain = ProxyUtil.MAPPER.createObjectNode();
        plain.put("type", "reasoning");
        plain.put("id", "rs_plain");
        plain.put("encrypted_content", "cipher-plain");
        ArrayNode input = array(plain, wrapped("rs_a", "cipher-a", "upstream-a"));

        String resolved = EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL);

        assertEquals("upstream-a", resolved);
        assertEquals(2, input.size());
        assertEquals("rs_plain", input.get(0).path("id").asText());
        assertEquals("rs_a", input.get(1).path("id").asText());
    }

    @Test
    void withThreeUpstreamsOnlyTheFirstOneIsKept() {
        ArrayNode input = array(
                wrapped("rs_b1", "cipher-b1", "upstream-b"),
                wrapped("rs_a", "cipher-a", "upstream-a"),
                wrapped("rs_b2", "cipher-b2", "upstream-b"),
                wrapped("rs_c", "cipher-c", "upstream-c"),
                user());

        String resolved = EncryptedContentAffinityUtil.resolveAndUnwrap(input, MODEL);

        assertEquals("upstream-b", resolved);
        assertEquals(3, input.size());
        assertEquals("rs_b1", input.get(0).path("id").asText());
        assertEquals("rs_b2", input.get(1).path("id").asText());
        assertEquals("Continue", input.get(2).path("content").asText());
    }

    private static ObjectNode wrapped(String id, String content, String upstreamId) {
        ObjectNode item = ProxyUtil.MAPPER.createObjectNode();
        item.put("type", "reasoning");
        item.put("id", id);
        item.put("encrypted_content", content);
        EncryptedContentAffinityUtil.wrapOutputItem(item, upstreamId, "deploy-x");
        return item;
    }

    private static ObjectNode user() {
        ObjectNode message = ProxyUtil.MAPPER.createObjectNode();
        message.put("role", "user");
        message.put("content", "Continue");
        return message;
    }

    private static ArrayNode array(ObjectNode... items) {
        ArrayNode array = ProxyUtil.MAPPER.createArrayNode();
        for (ObjectNode item : items) {
            array.add(item);
        }
        return array;
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

        EncryptedContentAffinityUtil.wrapOutputArray(output, "upstream-a", "deploy-x");

        assertEquals("msg_1", message.path("id").asText());
        assertTrue(reasoning.path("id").asText().startsWith("dialenc_"));

        // missing output field resolves to a MissingNode - must be a no-op, not throw
        EncryptedContentAffinityUtil.wrapOutputArray(ProxyUtil.MAPPER.missingNode(), "upstream-a", "deploy-x");
    }
}
