package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesApiRequestTest {

    private static final List<String> NODE_ORDER = List.of("prefix.body.tools", "prefix.body.system", "prefix.body.messages");

    @Test
    void testBuildCacheKeys_stringSystemAndContent() throws JsonProcessingException {
        String body = """
                {
                    "system": "You are a helpful assistant",
                    "messages": [
                        {"role": "user", "content": "Hello"}
                    ]
                }
                """;

        List<CacheKey> keys = request(body).buildCacheKeys(NODE_ORDER);

        assertEquals(2, keys.size());
        assertEquals(List.of("prefix.body.system[0]", "prefix.body.messages[0].content[0]"),
                keys.stream().map(CacheKey::path).toList());
    }

    @Test
    void testBuildCacheKeys_arraySystemAndBlockContent() throws JsonProcessingException {
        String body = """
                {
                    "system": [
                        {"type": "text", "text": "block 1"},
                        {"type": "text", "text": "block 2", "cache_control": {"type": "ephemeral"}}
                    ],
                    "messages": [
                        {"role": "user", "content": [
                            {"type": "text", "text": "hi"},
                            {"type": "text", "text": "there", "cache_control": {"type": "ephemeral"}}
                        ]}
                    ]
                }
                """;

        List<CacheKey> keys = request(body).buildCacheKeys(NODE_ORDER);

        assertEquals(4, keys.size());
        assertEquals(List.of(
                        "prefix.body.system[0]",
                        "prefix.body.system[1]",
                        "prefix.body.messages[0].content[0]",
                        "prefix.body.messages[0].content[1]"),
                keys.stream().map(CacheKey::path).toList());
        assertFalse(keys.get(0).hasBreakpoint());
        assertTrue(keys.get(1).hasBreakpoint());
        assertFalse(keys.get(2).hasBreakpoint());
        assertTrue(keys.get(3).hasBreakpoint());
    }

    @Test
    void testBuildCacheKeys_toolsIncluded() throws JsonProcessingException {
        String body = """
                {
                    "tools": [
                        {"name": "get_weather", "description": "..."}
                    ],
                    "messages": [
                        {"role": "user", "content": "Hello"}
                    ]
                }
                """;

        List<CacheKey> keys = request(body).buildCacheKeys(NODE_ORDER);

        assertEquals(List.of("prefix.body.tools[0]", "prefix.body.messages[0].content[0]"),
                keys.stream().map(CacheKey::path).toList());
    }

    @Test
    void testBuildCacheKeys_cacheControlExcludedFromDigest() throws JsonProcessingException {
        String withoutBreakpoint = """
                {
                    "messages": [
                        {"role": "user", "content": [{"type": "text", "text": "hi"}]}
                    ]
                }
                """;
        String withBreakpointMovedForward = """
                {
                    "messages": [
                        {"role": "user", "content": [{"type": "text", "text": "hi", "cache_control": {"type": "ephemeral"}}]}
                    ]
                }
                """;

        String hashWithout = request(withoutBreakpoint).buildCacheKeys(NODE_ORDER).get(0).hash();
        String hashWith = request(withBreakpointMovedForward).buildCacheKeys(NODE_ORDER).get(0).hash();

        assertEquals(hashWithout, hashWith);
    }

    @Test
    void testBuildCacheKeys_systemChangeAltersMessageHash() throws JsonProcessingException {
        String bodyA = """
                {
                    "system": "prompt A",
                    "messages": [
                        {"role": "user", "content": "Hello"}
                    ]
                }
                """;
        String bodyB = """
                {
                    "system": "prompt B",
                    "messages": [
                        {"role": "user", "content": "Hello"}
                    ]
                }
                """;

        String hashA = lastHash(request(bodyA).buildCacheKeys(NODE_ORDER));
        String hashB = lastHash(request(bodyB).buildCacheKeys(NODE_ORDER));

        assertNotEquals(hashA, hashB);
    }

    @Test
    void testBuildCacheKeys_roleChangeAltersHash() throws JsonProcessingException {
        String userBody = """
                {
                    "messages": [
                        {"role": "user", "content": "Hello"}
                    ]
                }
                """;
        String assistantBody = """
                {
                    "messages": [
                        {"role": "assistant", "content": "Hello"}
                    ]
                }
                """;

        String userHash = lastHash(request(userBody).buildCacheKeys(NODE_ORDER));
        String assistantHash = lastHash(request(assistantBody).buildCacheKeys(NODE_ORDER));

        assertNotEquals(userHash, assistantHash);
    }

    @Test
    void testBuildCacheKeys_appendingTurnLeavesEarlierPrefixesStable() throws JsonProcessingException {
        String twoTurns = """
                {
                    "messages": [
                        {"role": "user", "content": "Hello"},
                        {"role": "assistant", "content": "Hi there"}
                    ]
                }
                """;
        String threeTurns = """
                {
                    "messages": [
                        {"role": "user", "content": "Hello"},
                        {"role": "assistant", "content": "Hi there"},
                        {"role": "user", "content": "Follow up"}
                    ]
                }
                """;

        List<CacheKey> keysTwo = request(twoTurns).buildCacheKeys(NODE_ORDER);
        List<CacheKey> keysThree = request(threeTurns).buildCacheKeys(NODE_ORDER);

        assertEquals(keysTwo.get(0).hash(), keysThree.get(0).hash());
        assertEquals(keysTwo.get(1).hash(), keysThree.get(1).hash());
    }

    @Test
    void testBuildCacheKeys_unsupportedNodeIsSkipped() throws JsonProcessingException {
        String body = """
                {
                    "messages": [
                        {"role": "user", "content": "Hello"}
                    ]
                }
                """;

        List<CacheKey> keys = request(body).buildCacheKeys(List.of("prefix.body.input", "prefix.body.messages"));

        assertEquals(1, keys.size());
        assertEquals("prefix.body.messages[0].content[0]", keys.get(0).path());
    }

    private static String lastHash(List<CacheKey> keys) {
        return keys.get(keys.size() - 1).hash();
    }

    private static MessagesApiRequest request(String body) throws JsonProcessingException {
        return new MessagesApiRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
    }
}
