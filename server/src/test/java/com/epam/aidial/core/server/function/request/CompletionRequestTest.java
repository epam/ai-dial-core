package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompletionRequestTest {
    @Test
    void testCollectAttachedFiles_ChatRequest() throws IOException {
        String body = """
                {
                  "modelId": "model",
                  "messages": [
                    {
                      "content": "test",
                      "role": "user",
                      "custom_content": {
                      }
                    },
                    {
                      "content": "I'm sorry, but your message is unclear. Could you please provide more details or context?",
                      "role": "assistant"
                    },
                    {
                      "content": "what file is?",
                      "role": "user",
                      "custom_content": {
                        "attachments": [
                          {
                            "type": "application/octet-stream",
                            "title": "Dockerfile",
                            "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile"
                          }
                        ]
                      }
                    },
                    {
                      "content": null
                    },
                    {
                      "content": "The file you provided is a Dockerfile.",
                      "role": "assistant",
                      "custom_content": {
                        "attachments": [
                          {
                            "index": 0,
                            "type": "text/markdown",
                            "title": "[1] 'Dockerfile'",
                            "data": "FROM gradle:8.2.0",
                            "reference_url": "b1/Dockerfile"
                          },
                          {
                            "index": 1,
                            "type": "text/markdown",
                            "title": "[2] 'Dockerfile'",
                            "data": "* /app/config/ RUN mkdir /app/log && chown -R appuser:appuser /app",
                            "reference_url": "b1/Dockerfile"
                          },
                          {
                            "index": 2,
                            "type": "text/markdown",
                            "title": "[3] 'Dockerfile'",
                            "data": "USER appuser",
                            "reference_url": "b1/Dockerfile"
                          }
                        ]
                      }
                    },
                    {
                      "content": [
                        {"type": "text", "text": "Compare these files?"},
                        {"type": "image_url"},
                        {"type": "image_url", "image_url": null},
                        {"type": "image_url", "image_url": {}},
                        {"type": "image_url", "image_url": {"url": null}},
                        {"type": "image_url", "image_url": {"url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/README.md"}}
                      ],
                      "role": "user",
                      "custom_content": {
                        "attachments": [
                          {
                            "type": "application/octet-stream",
                            "title": "LICENSE",
                            "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/LICENSE"
                          },
                          {
                            "type": "binary/octet-stream",
                            "title": "Dockerfile",
                            "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile"
                          },
                          {
                            "type": "application/vnd.dial.metadata+json",
                            "title": ".dockerignore",
                            "url": "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/.dockerignore"
                          }
                        ],
                        "stages": [
                            {
                                "index": 0,
                                "name": "stage1",
                                "status": "completed",
                                "attachments": [
                                    {
                                        "type": "application/octet-stream",
                                        "title": "LICENSE",
                                        "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/stage0_file0"
                                    },
                                    {
                                        "type": "application/octet-stream",
                                        "title": "LICENSE",
                                        "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/stage0_file1"
                                    }
                                ]
                            }
                        ]
                      }
                    }
                  ],
                  "id": "id"
                }
                """;
        CompletionRequest request = request(body);
        Set<String> expected = Set.of(
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/LICENSE",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/README.md",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/.dockerignore",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/stage0_file0",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/stage0_file1");

        Set<String> actual = request.collectAttachments();

        assertEquals(expected, actual);
    }

    @Test
    void testCollectAttachedFiles_Fail() throws IOException {
        String body = """
                {
                  "modelId": "model",
                  "messages": [
                    {
                      "content": "test",
                      "role": "user",
                      "custom_content": {
                        "attachments": [
                          {
                            "type": "application/vnd.dial.metadata+json",
                            "title": ".dockerignore",
                            "url": "metadatata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/.dockerignore"
                          }
                        ]
                      }
                    }
                  ],
                  "id": "id"
                }
                """;

        CompletionRequest request = request(body);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, request::collectAttachments);

        assertEquals("Url of metadata attachment must start with metadata/: metadatata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/.dockerignore", error.getMessage());
    }

    @Test
    void testCollectAttachedFiles_EmbeddingRequest_valid() throws IOException {
        String content = """
                {
                  "input": "some input",
                  "custom_input": [
                    "test text 1",
                    {
                      "type": "image/png",
                      "data": "data:image/png;base64,iVBORw0KGg"
                    },
                    {
                      "type": "image/png",
                      "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/image.png"
                    },
                    [
                      "test text 2",
                      {
                        "type": "image/png",
                        "data": "data:image/png;base64,iVBORw0KGg"
                      },
                      {
                        "type": "video/mp4",
                        "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b2/video.mp4"
                      }
                    ]
                  ],
                  "user": "user_id"
                }
                """;
        CompletionRequest request = request(content);
        Set<String> expected = Set.of(
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/image.png",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b2/video.mp4");

        Set<String> actual = request.collectAttachments();

        assertEquals(expected, actual);
    }

    @Test
    void testCollectAttachedFiles_EmbeddingRequest_invalid() throws IOException {
        String content = """
                {
                  "input": "some input",
                  "custom_input": "invalid_custom_input",
                  "user": "user_id"
                }
                """;
        CompletionRequest request = request(content);

        Set<String> actual = request.collectAttachments();

        assertEquals(Set.of(), actual);
    }

    @Test
    public void testCollectAttachmentsFromResponse_ChatSingleResponse() throws JsonProcessingException {
        String response = """
                {
                  "id": "chatcmpl-7VfMTgj3ljKdGKS2BEIwloII3IoO0",
                  "object": "chat.completion",
                  "created": 1687781517,
                  "model": "gpt-35-turbo",
                  "choices": [
                    {
                      "index": 0,
                      "finish_reason": "stop",
                      "message": {
                        "role": "assistant",
                        "content": "some text",
                        "custom_content": {
                           "attachments": [
                              {
                               "type": "application/octet-stream",
                               "title": "LICENSE",
                               "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/file1.txt"
                              },
                              {
                                "type": "application/octet-stream",
                                "title": "LICENSE",
                                "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/file2.txt"
                              }
                           ]
                        }
                      }
                    }
                  ],
                  "usage" : {
                    "junk_string": "junk",
                    "junk_integer" : 1,
                    "junk_float" : 1.0,
                    "junk_null" : null,
                    "junk_true" : true,
                    "junk_false" : false,
                    "completion_tokens": 33,
                    "prompt_tokens": 19,
                    "total_tokens": 52
                  }
                }
                """;
        Set<String> files =  new HashSet<>();
        ProxyUtil.collectAttachmentsFromResponse((ObjectNode) ProxyUtil.MAPPER.readTree(response), false, files::add);

        assertEquals(Set.of("files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/file1.txt",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/file2.txt"), files);

    }

    private static CompletionRequest request(String body) throws JsonProcessingException {
        return new CompletionRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
    }
}