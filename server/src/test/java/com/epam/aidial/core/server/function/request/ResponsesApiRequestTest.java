package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesApiRequestTest {

    private static final List<String> NODE_ORDER = List.of("prefix.body.tools", "prefix.body.instructions", "prefix.body.input");

    @Test
    void testGetModel() throws JsonProcessingException {
        String body = """
                {
                    "model": "test-model"
                }
                """;

        ResponsesApiRequest request = request(body);

        assertEquals("test-model", request.getModel());
    }

    @Test
    void testSetModel() throws JsonProcessingException {
        String body = """
                {
                    "model": "old-model"
                }
                """;
        ResponsesApiRequest request = request(body);

        request.setModel("new-model");

        assertEquals("new-model", request.getModel());
    }

    @Test
    void testIsStreamingTrue() throws JsonProcessingException {
        String body = """
                {
                    "stream": true
                }
                """;

        ResponsesApiRequest request = request(body);

        assertTrue(request.isStreaming());
    }

    @Test
    void testIsStreamingFalse() throws JsonProcessingException {
        String body = """
                {
                    "stream": false
                }
                """;
        ResponsesApiRequest request = request(body);

        assertFalse(request.isStreaming());
    }

    @Test
    void testIsStreamingFalseWhenEmpty() throws JsonProcessingException {
        String body = """
                {
                }
                """;

        ResponsesApiRequest request = request(body);

        assertFalse(request.isStreaming());
    }

    @Test
    void testSerialize() throws JsonProcessingException {
        String expected = """
                {"model":"test-model","stream":true}""";
        ResponsesApiRequest request = request(expected);

        String actual = new String(request.serialize());

        assertEquals(expected, actual);
    }

    @Test
    void testApplyDefaults() throws JsonProcessingException {
        String body = """
                {
                    "field": "kept-value"
                }
                """;
        Model model = new Model();
        model.setResponsesDefaults(
                Map.of(
                        "field", "skipped-value",
                        "another-field", "added-value"));

        ResponsesApiRequest request = request(body);
        request.applyDefaults(model);
        String expected = """
                {"field":"kept-value","another-field":"added-value"}""";

        String actual = new String(request.serialize());

        assertEquals(expected, actual);
    }

    @Test
    void testCollectAttachments() throws JsonProcessingException {
        String body = """
                {
                    "input": [
                        {
                            "type": "message",
                            "content": [
                                {
                                    "type": "input_image",
                                    "image_url": "https://example.com/from-message.png"
                                },
                                {
                                    "type": "input_file",
                                    "file_url": "https://example.com/from-message.pdf"
                                }
                            ]
                        },
                        {
                            "type": "function_call_output",
                            "output": [
                                {
                                    "type": "input_image",
                                    "image_url": "https://example.com/from-function.jpg"
                                },
                                {
                                    "type": "input_file",
                                    "file_url": "https://example.com/from-function.csv"
                                }
                            ]
                        },
                        {
                            "type": "custom_tool_call_output",
                            "output": [
                                {
                                    "type": "input_image",
                                    "image_url": "https://example.com/from-tool.jpg"
                                },
                                {
                                    "type": "input_file",
                                    "file_url": "https://example.com/from-tool.csv"
                                }
                            ]
                        },
                        {
                            "type": "computer_call_output",
                            "output": {
                                "type": "computer_screenshot",
                                "image_url": "https://example.com/screenshot.png"
                            }
                        }
                    ],
                    "tools": [
                        {
                            "type": "image_generation",
                            "input_image_mask": {
                                "image_url": "https://example.com/mask.png"
                            }
                        }
                    ]
                }
                """;

        ResponsesApiRequest request = request(body);
        Set<String> expected = Set.of(
                "https://example.com/from-message.png",
                "https://example.com/from-message.pdf",
                "https://example.com/from-function.jpg",
                "https://example.com/from-function.csv",
                "https://example.com/from-tool.jpg",
                "https://example.com/from-tool.csv",
                "https://example.com/screenshot.png",
                "https://example.com/mask.png");

        Set<String> actual = request.collectAttachments();

        assertEquals(expected, actual);
    }

    @Test
    void testCollectAppAttachments() throws JsonProcessingException {
        String body = """
                {
                    "app_attachments": [
                        {
                            "url": "https://example.com/app-file1.txt"
                        }
                    ]
                }
                """;

        ResponsesApiRequest request = request(body);
        Set<String> expected = Set.of("https://example.com/app-file1.txt");

        Set<String> actual = request.collectAppAttachments(List.of("$.app_attachments[*].url"));

        assertEquals(expected, actual);
    }

    @Test
    void testBuildCacheKeys_stringInstructionsAndInput() throws JsonProcessingException {
        String body = """
                {
                    "instructions": "Be concise",
                    "input": "Hello"
                }
                """;

        List<CacheKey> keys = request(body).buildCacheKeys(NODE_ORDER);

        assertEquals(2, keys.size());
        assertEquals(List.of("prefix.body.instructions[0]", "prefix.body.input[0]"),
                keys.stream().map(CacheKey::path).toList());
        assertFalse(keys.get(0).hasBreakpoint());
        assertFalse(keys.get(1).hasBreakpoint());
    }

    @Test
    void testBuildCacheKeys_arrayInputAndTools() throws JsonProcessingException {
        String body = """
                {
                    "tools": [
                        {"type": "function", "name": "get_weather"}
                    ],
                    "input": [
                        {"role": "user", "content": "Hi"},
                        {"role": "assistant", "content": "Hello"}
                    ]
                }
                """;

        List<CacheKey> keys = request(body).buildCacheKeys(NODE_ORDER);

        assertEquals(List.of("prefix.body.tools[0]", "prefix.body.input[0]", "prefix.body.input[1]"),
                keys.stream().map(CacheKey::path).toList());
    }

    @Test
    void testBuildCacheKeys_appendingInputLeavesEarlierPrefixesStable() throws JsonProcessingException {
        String oneTurn = """
                {
                    "input": [
                        {"role": "user", "content": "Hi"}
                    ]
                }
                """;
        String twoTurns = """
                {
                    "input": [
                        {"role": "user", "content": "Hi"},
                        {"role": "assistant", "content": "Hello"}
                    ]
                }
                """;

        List<CacheKey> keysOne = request(oneTurn).buildCacheKeys(NODE_ORDER);
        List<CacheKey> keysTwo = request(twoTurns).buildCacheKeys(NODE_ORDER);

        assertEquals(keysOne.get(0).hash(), keysTwo.get(0).hash());
    }

    @Test
    void testBuildCacheKeys_instructionsChangeAltersInputHash() throws JsonProcessingException {
        String bodyA = """
                {
                    "instructions": "A",
                    "input": "Hi"
                }
                """;
        String bodyB = """
                {
                    "instructions": "B",
                    "input": "Hi"
                }
                """;

        String hashA = request(bodyA).buildCacheKeys(NODE_ORDER).get(1).hash();
        String hashB = request(bodyB).buildCacheKeys(NODE_ORDER).get(1).hash();

        assertNotEquals(hashA, hashB);
    }

    @Test
    void testBuildCacheKeys_unsupportedNodeIsSkipped() throws JsonProcessingException {
        String body = """
                {
                    "input": "Hi"
                }
                """;

        List<CacheKey> keys = request(body).buildCacheKeys(List.of("prefix.body.messages", "prefix.body.input"));

        assertEquals(1, keys.size());
        assertEquals("prefix.body.input[0]", keys.get(0).path());
    }

    private static ResponsesApiRequest request(String body) throws JsonProcessingException {
        return new ResponsesApiRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
    }
}