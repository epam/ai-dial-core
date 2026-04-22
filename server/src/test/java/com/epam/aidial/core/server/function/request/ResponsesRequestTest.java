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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesRequestTest {
    @Test
    void testGetModel() throws JsonProcessingException {
        String body = """
                {
                    "model": "test-model"
                }
                """;

        ResponsesRequest request = request(body);

        assertEquals("test-model", request.getModel());
    }

    @Test
    void testSetModel() throws JsonProcessingException {
        String body = """
                {
                    "model": "old-model"
                }
                """;
        ResponsesRequest request = request(body);

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

        ResponsesRequest request = request(body);

        assertTrue(request.isStreaming());
    }

    @Test
    void testIsStreamingFalse() throws JsonProcessingException {
        String body = """
                {
                    "stream": false
                }
                """;
        ResponsesRequest request = request(body);

        assertFalse(request.isStreaming());
    }

    @Test
    void testIsStreamingFalseWhenEmpty() throws JsonProcessingException {
        String body = """
                {
                }
                """;

        ResponsesRequest request = request(body);

        assertFalse(request.isStreaming());
    }

    @Test
    void testSerialize() throws JsonProcessingException {
        String expected = """
                {"model":"test-model","stream":true}""";
        ResponsesRequest request = request(expected);

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

        ResponsesRequest request = request(body);
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

        ResponsesRequest request = request(body);
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

        ResponsesRequest request = request(body);
        Set<String> expected = Set.of("https://example.com/app-file1.txt");

        Set<String> actual = request.collectAppAttachments(List.of("$.app_attachments[*].url"));

        assertEquals(expected, actual);
    }

    private static ResponsesRequest request(String body) throws JsonProcessingException {
        return new ResponsesRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
    }
}