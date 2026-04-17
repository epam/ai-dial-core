package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void testUpdate() throws JsonProcessingException {
        String body = """
                {
                    "field": "old-value"
                }
                """;

        ResponsesRequest request = request(body);
        request.update("field", node -> new TextNode("new-value"));
        request.update("new-field", node -> new TextNode("added-value"));
        String expected = """
                {"field":"new-value","new-field":"added-value"}""";

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
                                    "image_url": "https://example.com/a.png"
                                },
                                {
                                    "type": "input_file",
                                    "file_url": "https://example.com/report.pdf"
                                }
                            ]
                        },
                        {
                            "type": "function_call_output",
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
                            "type": "code_interpreter_call",
                            "outputs": [
                                {
                                    "type": "image",
                                    "url": "https://example.com/code-output.png"
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
                "https://example.com/a.png",
                "https://example.com/report.pdf",
                "https://example.com/from-tool.jpg",
                "https://example.com/from-tool.csv",
                "https://example.com/code-output.png",
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