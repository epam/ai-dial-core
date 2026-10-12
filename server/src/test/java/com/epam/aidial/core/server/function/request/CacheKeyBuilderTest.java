package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheKeyBuilderTest {

    @Test
    void testUpdate_hashesSortedNodeWithoutChangingIt() throws Exception {
        String json = "{\"z\": [{\"y\": 1, \"b\": \"Привет 😀 \\\"q\\\"\"}, 2.5, null], \"a\": {\"d\": true, \"c\": \"x\"}}";
        JsonNode node = ProxyUtil.MAPPER.readTree(json);
        // the keys before update streamed the sorted node: they must not change
        String sorted = JsonUtil.sort(ProxyUtil.MAPPER.readTree(json)).toString();
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(sorted.getBytes(StandardCharsets.UTF_8)));

        CacheKeyBuilder builder = new CacheKeyBuilder();
        builder.update(node);

        assertEquals(expected, builder.buildKey("p", false).hash());
        assertEquals(ProxyUtil.MAPPER.readTree(json).toString(), node.toString());
    }
}
