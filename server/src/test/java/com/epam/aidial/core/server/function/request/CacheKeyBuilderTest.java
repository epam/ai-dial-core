package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CacheKeyBuilderTest {

    @Test
    void testUpdate_ignoresLayoutAndPropertyOrder() throws Exception {
        String json = "{\"z\": [{\"y\": 1, \"b\": \"Привет 😀 \\\"q\\\"\"}, 2.5, null], \"a\": {\"d\": true, \"c\": \"x\"}}";
        String reordered = "{\"a\":{\"c\":\"x\",\"d\":true},\"z\":[{\"b\":\"Привет 😀 \\\"q\\\"\",\"y\":1},2.5,null]}";
        JsonNode node = ProxyUtil.MAPPER.readTree(json);

        assertEquals(key(reordered), key(node));
        assertEquals(ProxyUtil.MAPPER.readTree(json).toString(), node.toString());
    }

    @Test
    void testUpdate_tellsStructuresApart() throws Exception {
        assertNotEquals(key("[\"ab\"]"), key("[\"a\", \"b\"]"));
        assertNotEquals(key("[[\"a\"], \"b\"]"), key("[[\"a\", \"b\"]]"));
        assertNotEquals(key("{\"a\": \"b\"}"), key("[\"a\", \"b\"]"));
        assertNotEquals(key("\"1\""), key("1"));
        assertNotEquals(key("1"), key("1.0"));
        assertNotEquals(key("\"true\""), key("true"));
        assertNotEquals(key("\"null\""), key("null"));
    }

    @Test
    void testUpdate_accumulatesAcrossCalls() throws Exception {
        JsonNode first = ProxyUtil.MAPPER.readTree("{\"b\": 1, \"a\": 2}");
        JsonNode second = ProxyUtil.MAPPER.readTree("\"x\"");
        CacheKeyBuilder builder = new CacheKeyBuilder();
        builder.update(first);
        String afterFirst = builder.buildKey("p", false).hash();
        builder.update(second);
        String afterSecond = builder.buildKey("p", false).hash();

        assertEquals(key(first), afterFirst);
        assertNotEquals(afterFirst, afterSecond);
        CacheKeyBuilder reversed = new CacheKeyBuilder();
        reversed.update(second);
        reversed.update(first);
        assertNotEquals(afterSecond, reversed.buildKey("p", false).hash());
    }

    @Test
    void testBuildKey_pinsTheCanonicalForm() throws Exception {
        // pins the persisted key format: changing it orphans every upstream cache entry in Redis
        byte[] canonical = {'{', 0, 0, 0, 1, 'a', '[', '"', 0, 0, 0, 1, 'x', '#', 0, 0, 0, 1, '1', ']', '}'};
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(canonical));

        assertEquals(expected, key("{\"a\": [\"x\", 1]}"));
    }

    @Test
    void testBuildKey_isLowercaseMd5Hex() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", new CacheKeyBuilder().buildKey("p", false).hash());
    }

    private static String key(String json) throws Exception {
        return key(ProxyUtil.MAPPER.readTree(json));
    }

    private static String key(JsonNode node) {
        CacheKeyBuilder builder = new CacheKeyBuilder();
        builder.update(node);
        return builder.buildKey("p", false).hash();
    }
}
