package com.epam.aidial.core.server.function.request;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatCompletionRequestTest {
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
        ChatCompletionRequest request = request(body);
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

        ChatCompletionRequest request = request(body);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, request::collectAttachments);

        assertEquals("Url of metadata attachment must start with metadata/: metadatata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/.dockerignore", error.getMessage());
    }

    @Test
    void testCollectAttachedFiles_AnnotationCitationAttachment() throws IOException {
        String body = """
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": "Summarize the document.",
                      "custom_content": {
                        "annotations": [
                          {
                            "index": 0,
                            "body": {
                              "source": {
                                "attachment": {
                                  "type": "application/pdf",
                                  "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/docs/report.pdf"
                                }
                              }
                            }
                          },
                          {
                            "index": 1,
                            "body": {
                              "title": "Pure annotation - no source"
                            }
                          },
                          {
                            "index": 2
                          }
                        ]
                      }
                    }
                  ]
                }
                """;
        ChatCompletionRequest request = request(body);
        Set<String> actual = request.collectAttachments();
        assertEquals(Set.of("files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/docs/report.pdf"), actual);
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
        ChatCompletionRequest request = request(content);
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
        ChatCompletionRequest request = request(content);

        Set<String> actual = request.collectAttachments();

        assertEquals(Set.of(), actual);
    }

    @Test
    void testCollectSkills_ChatRequest() throws IOException {
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
                      "content": "use the summarizer skill",
                      "role": "user",
                      "custom_content": {
                        "skills": [
                          {
                            "url": "skills/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/summarizer"
                          }
                        ]
                      }
                    },
                    {
                      "content": "Sure, using the summarizer skill.",
                      "role": "assistant",
                      "custom_content": {
                        "skills": [
                          {
                            "url": "skills/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/translator"
                          }
                        ]
                      }
                    }
                  ],
                  "id": "id"
                }
                """;
        ChatCompletionRequest request = request(body);
        Set<String> expected = Set.of(
                "skills/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/summarizer",
                "skills/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/translator");

        Set<String> actual = request.collectSkills();

        assertEquals(expected, actual);
    }

    @Test
    void testCollectSkills_NoSkills() throws IOException {
        String body = """
                {
                  "messages": [
                    {
                      "content": "test",
                      "role": "user",
                      "custom_content": {
                        "attachments": [
                          {
                            "type": "application/octet-stream",
                            "url": "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;
        ChatCompletionRequest request = request(body);

        assertEquals(Set.of(), request.collectSkills());
    }

    @Test
    void testCollectSkills_MissingUrl_Fail() throws IOException {
        String body = """
                {
                  "messages": [
                    {
                      "content": "test",
                      "role": "user",
                      "custom_content": {
                        "skills": [
                          {
                            "title": "summarizer"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;
        ChatCompletionRequest request = request(body);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, request::collectSkills);

        assertEquals("Missing url in skill attachment.", error.getMessage());
    }

    @Test
    void testBuildCacheKeys_leavesTreeUnchanged() throws IOException {
        // keys are computed on sorted properties without reordering the tree
        String body = "{\"model\": \"m\", \"messages\": [{\"role\": \"user\", \"content\": [{\"type\": \"text\", \"text\": \"hi\"}]}]}";
        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(body);

        new ChatCompletionRequest(tree).buildCacheKeys(List.of("prefix.body.messages"));

        assertEquals(ProxyUtil.MAPPER.readTree(body).toString(), tree.toString());
    }

    @Test
    void testBuildCacheKeys_hashesNonObjectMessage() throws Exception {
        ChatCompletionRequest request = request("{\"messages\": [\"x\", {\"role\": \"user\"}]}");

        List<CacheKey> keys = request.buildCacheKeys(List.of("prefix.body.messages"));

        assertEquals(List.of("prefix.body.messages[0]", "prefix.body.messages[1]"), keys.stream().map(CacheKey::path).toList());
        assertEquals(sha256("\"x\""), keys.get(0).hash());
        assertEquals(sha256("\"x\"\"user\""), keys.get(1).hash());
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static ChatCompletionRequest request(String body) throws JsonProcessingException {
        return new ChatCompletionRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
    }
}