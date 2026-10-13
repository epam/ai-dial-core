package com.epam.aidial.core.server.function.request;

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
        CacheKeyBuilder builder = new CacheKeyBuilder();
        builder.update(node);

        // the keys before update streamed the sorted node: they must not change
        assertEquals(sha1("{\"a\":{\"c\":\"x\",\"d\":true},\"z\":[{\"b\":\"Привет 😀 \\\"q\\\"\",\"y\":1},2.5,null]}"),
                builder.buildKey("p", false).hash());
        assertEquals(ProxyUtil.MAPPER.readTree(json).toString(), node.toString());
    }

    @Test
    void testUpdate_accumulatesAcrossCalls() throws Exception {
        CacheKeyBuilder builder = new CacheKeyBuilder();
        builder.update(ProxyUtil.MAPPER.readTree("{\"b\": 1, \"a\": 2}"));
        builder.update(ProxyUtil.MAPPER.readTree("\"x\""));

        assertEquals(sha1("{\"a\":2,\"b\":1}\"x\""), builder.buildKey("p", false).hash());
    }

    @Test
    void testUpdate_escapesLoneSurrogate() throws Exception {
        // String.getBytes wrote '?' for it before, so such a prompt gets a new key once
        CacheKeyBuilder builder = new CacheKeyBuilder();
        builder.update(ProxyUtil.MAPPER.getNodeFactory().textNode("a" + (char) 0xD83D + "b"));

        assertEquals(sha1("\"a\\uD83Db\""), builder.buildKey("p", false).hash());
    }

    private static String sha1(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void testBuildKey_isLowercaseSha1Hex() {
        // pins the persisted key format: changing it orphans every upstream cache entry in Redis
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", new CacheKeyBuilder().buildKey("p", false).hash());
    }
}
