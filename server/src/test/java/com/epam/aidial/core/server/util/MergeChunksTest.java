package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MergeChunksTest {

    @Test
    public void testMerge() throws IOException {
        try (InputStream in = MergeChunksTest.class.getResourceAsStream("/merge_chunks-test-cases.json")) {
            ArrayNode tests = (ArrayNode) ProxyUtil.MAPPER.readTree(in);
            for (JsonNode test : tests) {
                ArrayNode chunks = (ArrayNode) test.get("chunks");
                JsonNode error = test.get("error");
                JsonNode res = test.get("result");
                JsonNode desc = test.get("description");
                if (error != null) {
                    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> MergeChunks.merge(toChunks(chunks)), desc == null ? "Fail" : desc.asText());
                    assertEquals(e.getMessage(), error.asText(), desc == null ? "Fail" : desc.asText());
                } else {
                    assertEquals(res, MergeChunks.merge(toChunks(chunks)), desc == null ? "Fail" : desc.asText());
                }
            }
        }

    }

    private List<JsonNode> toChunks(ArrayNode arrays) {
        List<JsonNode> chunks = new ArrayList<>();
        for (var node : arrays) {
            chunks.add(node);
        }
        return chunks;
    }
}
