package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.UpstreamInterface;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.epam.aidial.core.config.InterfaceType.ANTHROPIC_MESSAGES;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamExtraDataMergerTest {

    private static Upstream upstream(String extraData, String secretExtraData) {
        Upstream up = new Upstream();
        up.setExtraData(extraData);
        up.setSecretExtraData(secretExtraData);
        return up;
    }

    @Test
    void mergeBothNullReturnsNull() {
        assertNull(UpstreamExtraDataMerger.merge(upstream(null, null)));
    }

    @Test
    void mergeOnlyExtraDataReturnsVerbatimObject() {
        assertEquals("{\"region\":\"us\"}",
                UpstreamExtraDataMerger.merge(upstream("{\"region\":\"us\"}", null)));
    }

    @Test
    void mergeOnlyExtraDataReturnsVerbatimScalar() {
        assertEquals("opaque", UpstreamExtraDataMerger.merge(upstream("opaque", null)));
    }

    @Test
    void mergeOnlyExtraDataReturnsVerbatimArray() {
        assertEquals("[1,2]", UpstreamExtraDataMerger.merge(upstream("[1,2]", null)));
    }

    @Test
    void mergeOnlySecretExtraDataReturnsVerbatim() {
        assertEquals("token-value", UpstreamExtraDataMerger.merge(upstream(null, "token-value")));
    }

    @Test
    void mergeBothObjectsNoOverlapMerges() throws Exception {
        String merged = UpstreamExtraDataMerger.merge(
                upstream("{\"region\":\"us\"}", "{\"token\":\"abc\"}"));
        JsonNode node = ProxyUtil.MAPPER.readTree(merged);
        assertEquals("us", node.get("region").asText());
        assertEquals("abc", node.get("token").asText());
    }

    @Test
    void mergeBothObjectsOverlapSecretWins() throws Exception {
        String merged = UpstreamExtraDataMerger.merge(
                upstream("{\"k\":\"a\"}", "{\"k\":\"b\"}"));
        JsonNode node = ProxyUtil.MAPPER.readTree(merged);
        assertEquals("b", node.get("k").asText());
    }

    @Test
    void mergeBothNonObjectsThrows422() {
        HttpException ex = assertThrows(HttpException.class,
                () -> UpstreamExtraDataMerger.merge(upstream("\"a\"", "\"b\"")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void validateNoOverlapBothNullNoThrow() {
        UpstreamExtraDataMerger.validateNoOverlap(upstream(null, null));
    }

    @Test
    void validateNoOverlapOneNullNoThrow() {
        UpstreamExtraDataMerger.validateNoOverlap(upstream("{\"k\":\"a\"}", null));
        UpstreamExtraDataMerger.validateNoOverlap(upstream(null, "{\"k\":\"a\"}"));
    }

    @Test
    void validateNoOverlapDisjointNoThrow() {
        UpstreamExtraDataMerger.validateNoOverlap(
                upstream("{\"region\":\"us\"}", "{\"token\":\"x\"}"));
    }

    @Test
    void validateNoOverlapOverlapThrows422() {
        HttpException ex = assertThrows(HttpException.class,
                () -> UpstreamExtraDataMerger.validateNoOverlap(
                        upstream("{\"k\":\"a\"}", "{\"k\":\"b\"}")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(ex.getMessage().contains("k"), () -> "message should name overlapping key: " + ex.getMessage());
    }

    @Test
    void validateNoOverlapNonObjectThrows422() {
        HttpException ex = assertThrows(HttpException.class,
                () -> UpstreamExtraDataMerger.validateNoOverlap(upstream("\"a\"", "\"b\"")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void mergeForInterfaceCombinesInheritedExtraDataWithOverriddenSecret() throws Exception {
        Upstream up = upstream("{\"region\":\"us-east-1\",\"tenant\":\"acme\"}", null);
        UpstreamInterface anthropic = new UpstreamInterface();
        anthropic.setSecretExtraData("{\"region\":\"us-east-3\"}");
        up.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), anthropic));

        JsonNode merged = ProxyUtil.MAPPER.readTree(UpstreamExtraDataMerger.merge(up, ANTHROPIC_MESSAGES));

        // the override shadows the inherited key, and leaves the rest of the inherited object intact
        assertEquals("us-east-3", merged.get("region").asText());
        assertEquals("acme", merged.get("tenant").asText());
        // a sibling interface sees the upstream's data untouched
        assertEquals("{\"region\":\"us-east-1\",\"tenant\":\"acme\"}",
                UpstreamExtraDataMerger.merge(up, OPENAI_CHAT_COMPLETIONS));
    }

    @Test
    void validateNoOverlapCheckedWithinEachInterfaceButNotAcrossLevels() {
        // an interface declaring both halves with the same key is as ambiguous as an upstream doing it
        Upstream ambiguous = upstream(null, null);
        UpstreamInterface both = new UpstreamInterface();
        both.setExtraData("{\"k\":\"a\"}");
        both.setSecretExtraData("{\"k\":\"b\"}");
        ambiguous.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), both));

        HttpException ex = assertThrows(HttpException.class,
                () -> UpstreamExtraDataMerger.validateNoOverlap(ambiguous));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());

        // across levels the same key is an override, not an ambiguity
        Upstream overriding = upstream("{\"k\":\"a\"}", null);
        UpstreamInterface secretOnly = new UpstreamInterface();
        secretOnly.setSecretExtraData("{\"k\":\"b\"}");
        overriding.setInterfaces(Map.of(ANTHROPIC_MESSAGES.getValue(), secretOnly));

        UpstreamExtraDataMerger.validateNoOverlap(overriding);
    }
}
