package com.epam.aidial.core.util;

import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.data.Conversation;
import com.epam.aidial.core.data.Prompt;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyUtilTest {

    @Test
    public void testCollectAttachedFiles_ChatRequest() throws IOException {
        String content = """
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
                      "content": "Compare these files?",
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
        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(content.getBytes());
        ApiKeyData apiKeyData = new ApiKeyData();
        ProxyUtil.collectAttachedFiles(tree, link -> apiKeyData.getAttachedFiles().add(link));

        assertEquals(
                Set.of(
                        "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile",
                        "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/LICENSE",
                        "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/.dockerignore",
                        "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/stage0_file0",
                        "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/stage0_file1"
                ),
                apiKeyData.getAttachedFiles()
        );
    }

    @Test
    public void testCollectAttachedFiles_Fail() throws IOException {
        String content = """
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

        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(content.getBytes());
        ApiKeyData apiKeyData = new ApiKeyData();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ProxyUtil.collectAttachedFiles(tree, link -> apiKeyData.getAttachedFiles().add(link)));

        assertEquals("Url of metadata attachment must start with metadata/: metadatata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/.dockerignore", error.getMessage());
    }

    @Test
    public void testCollectAttachedFiles_EmbeddingRequest() throws IOException {
        String content = """
                {
                  "input": "some input",
                  "user": "user_id"
                }
                """;
        ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(content.getBytes());
        ApiKeyData apiKeyData = new ApiKeyData();
        ProxyUtil.collectAttachedFiles(tree, link -> apiKeyData.getAttachedFiles().add(link));

        assertTrue(apiKeyData.getAttachedFiles().isEmpty());
    }

    @Test
    public void testPromptSchemaValidation() {
        String validPromptJson = """
                {
                "id": "Id1",
                "folderId": "folder1",
                "name": "My awesome prompt",
                "content": "this is a content",
                "description": "description"
                }
                """;
        assertDoesNotThrow(() -> ProxyUtil.convertToObject(validPromptJson, Prompt.class));

        String missingRequiredField = """
                {
                "id": "Id1",
                "folderId": "folder1",
                "name": "My awesome prompt",
                "description": "description"
                }
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ProxyUtil.convertToObject(missingRequiredField, Prompt.class));
        assertEquals("Missing required property 'content'", error.getMessage());

        error = assertThrows(IllegalArgumentException.class, () -> ProxyUtil.convertToObject("12345", ProxyUtil.class));
        assertEquals("Provided payload do not match required schema", error.getMessage());
    }

    @Test
    public void testConversationSchemaValidation() {
        String validConversationJson = """
                {
                "id": "conversation_id",
                "name": "display_name",
                "model": {
                  "id": "model_id"
                  },
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "folder1",
                "messages": [
                  {
                  "role": "user",
                  "content": "content",
                  "custom_content": {"attachment_url": "some_url"},
                  "model": {"id": "model_id"},
                  "settings":
                    {
                    "prompt": "sysPrompt",
                    "temperature": 5,
                    "selectedAddons": ["A", "B", "C"],
                    "assistantModelId": "assistantId"
                    }
                  }
                ],
                "replay": {
                  "isReplay": true,
                  "replayUserMessagesStack": [],
                  "activeReplayIndex": 0
                  },
                "selectedAddons": ["R", "T", "G"],
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """;
        assertDoesNotThrow(() -> ProxyUtil.convertToObject(validConversationJson, Conversation.class));

        String missingRequiredField = """
                {
                "id": "conversation_id",
                "name": "display_name",
                "model": {
                  },
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "folder1",
                "messages": [
                  {
                  "role": "user",
                  "content": "content",
                  "custom_content": {"attachment_url": "some_url"},
                  "model": {"id": "model_id"},
                  "settings":
                    {
                    "prompt": "sysPrompt",
                    "temperature": 5,
                    "selectedAddons": ["A", "B", "C"],
                    "assistantModelId": "assistantId"
                    }
                  }
                ],
                "replay": {
                  "isReplay": true,
                  "replayUserMessagesStack": [],
                  "activeReplayIndex": 0
                  },
                "selectedAddons": ["R", "T", "G"],
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ProxyUtil.convertToObject(missingRequiredField, Conversation.class));
        assertEquals("Missing required property 'model.id'", error.getMessage());

        error = assertThrows(IllegalArgumentException.class, () -> ProxyUtil.convertToObject("12345", Conversation.class));
        assertEquals("Provided payload do not match required schema", error.getMessage());
    }

}
