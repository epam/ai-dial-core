package com.epam.aidial.core.util;

import com.epam.aidial.core.config.ApiKeyData;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ProxyUtilTest {

    @Test
    public void testCollectAttachedFiles() throws IOException {
        String content = """
                {
                  "modelId": "model",
                  "messages": [
                    {
                      "content": "test",
                      "role": "user"
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
                            "url": "b1/Dockerfile"
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
                            "url": "b1/LICENSE"
                          },
                          {
                            "type": "binary/octet-stream",
                            "title": "Dockerfile",
                            "url": "b1/Dockerfile"
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
        ProxyUtil.collectAttachedFiles(tree, apiKeyData);

        assertEquals(Set.of("b1/Dockerfile", "b1/LICENSE"), apiKeyData.getAttachedFiles());
    }
}
