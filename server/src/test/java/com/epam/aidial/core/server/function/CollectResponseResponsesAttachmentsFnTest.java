package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectResponseResponsesAttachmentsFnTest {
    @Mock
    private ProxyContext context;

    @Test
    void testCollectAttachmentsNonStreaming() {
        String body = """
                {
                    "output": [
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
                        }
                    ]
                }
                """;
        when(context.isStreamingRequest()).thenReturn(false);

        ObjectNode node = parse(body);
        CollectResponseResponsesAttachmentsFn function = new CollectResponseResponsesAttachmentsFn(null, context);
        Set<String> expected = Set.of(
                "https://example.com/code-output.png",
                "https://example.com/from-function.jpg",
                "https://example.com/from-function.csv",
                "https://example.com/from-tool.jpg",
                "https://example.com/from-tool.csv");

        Set<String> actual = function.collectAttachments(node);

        assertEquals(expected, actual);
    }

    @Test
    void testCollectAttachmentsStreaming() {
        List<String> events = List.of("""
                {
                    "type": "response.output_item.done",
                    "item": {
                        "type": "code_interpreter_call",
                        "outputs": [
                            {
                                "type": "image",
                                "url": "https://example.com/code-output.png"
                            }
                        ]
                    }
                }
                """, """
                {
                    "type": "response.output_item.done",
                    "item": {
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
                    }
                }
                """, """
                {
                    "type": "response.output_item.done",
                    "item": {
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
                    }
                }
                """);
        when(context.isStreamingRequest()).thenReturn(true);

        CollectResponseResponsesAttachmentsFn function = new CollectResponseResponsesAttachmentsFn(null, context);
        Set<String> expected = Set.of(
                "https://example.com/code-output.png",
                "https://example.com/from-function.jpg",
                "https://example.com/from-function.csv",
                "https://example.com/from-tool.jpg",
                "https://example.com/from-tool.csv");

        Set<String> actual = events.stream().map(CollectResponseResponsesAttachmentsFnTest::parse)
                .map(function::collectAttachments)
                .flatMap(Set::stream)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(expected, actual);
    }

    @SneakyThrows
    private static ObjectNode parse(String body) {
        return (ObjectNode) ProxyUtil.MAPPER.readTree(body);
    }
}