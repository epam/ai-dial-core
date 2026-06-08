package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

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
}
