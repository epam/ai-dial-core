package com.epam.aidial.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MergeChunksTest {

    @Test
    public void testMerge() {
        check();
    }

    private void check(String s, String t, String expected) throws JsonProcessingException {
        JsonNode source = ProxyUtil.MAPPER.readTree(s);
        JsonNode target = ProxyUtil.MAPPER.readTree(t);
        JsonNode res = MergeChunks.merge(target, source, new ArrayDeque<>());
        assertEquals(res.toString(), expected);
    }
}
