package com.epam.aidial.core.service;

import com.epam.aidial.core.ResourceBaseTest;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PublicationUtilTest {

    @Test
    void testConversationIdReplacement() {
        ResourceDescription targetResource1 = ResourceDescription.fromDecoded(ResourceType.CONVERSATION, "bucketName", "bucket/location/", "conversation");
        verifyJson("""
                {
                "id": "conversations/bucketName/conversation",
                "name": "display_name",
                "model": {"id": "model_id"},
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "conversations/bucketName",
                "messages": [],
                "selectedAddons": ["R", "T", "G"],
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """, PublicationUtil.replaceConversationLinks(ResourceBaseTest.CONVERSATION_BODY_1, targetResource1, Map.of()));

        ResourceDescription targetResource2 = ResourceDescription.fromDecoded(ResourceType.CONVERSATION, "bucketName", "bucket/location/", "folder1/conversation");
        verifyJson("""
                {
                "id": "conversations/bucketName/folder1/conversation",
                "name": "display_name",
                "model": {"id": "model_id"},
                "prompt": "system prompt",
                "temperature": 1,
                "folderId": "conversations/bucketName/folder1",
                "messages": [],
                "selectedAddons": ["R", "T", "G"],
                "assistantModelId": "assistantId",
                "lastActivityDate": 4848683153
                }
                """, PublicationUtil.replaceConversationLinks(ResourceBaseTest.CONVERSATION_BODY_1, targetResource2, Map.of()));
    }

    @Test
    void testAttachmentLinksReplacement() {
        ResourceDescription targetResource = ResourceDescription.fromDecoded(ResourceType.CONVERSATION, "bucketName", "bucket/location/", "conversation");
        String conversationBody = """
                {
                    "id": "conversations/bucketName2/folder1/conversation",
                    "name": "display_name",
                    "model": {"id": "model_id"},
                    "prompt": "system prompt",
                    "temperature": 1,
                    "folderId": "conversations/bucketName2/folder1",
                    "messages": [
                          {
                          "content": "The file you provided is a Dockerfile.",
                          "role": "assistant",
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
                                "url": "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                              }
                            ]
                          }
                        }
                    ],
                    "selectedAddons": ["R", "T", "G"],
                    "assistantModelId": "assistantId",
                    "lastActivityDate": 4848683153,
                    "playback": {
                        "messagesStack": [
                        {
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
                                    "url": "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                                }
                                ]
                            }
                        }
                        ]
                    },
                    "replay": {
                        "replayUserMessagesStack": [
                        {
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
                                    "url": "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                                }
                                ]
                            }
                        }
                        ]
                    }
                }
                """;

        verifyJson("""
                {
                    "id": "conversations/bucketName/conversation",
                    "name": "display_name",
                    "model": {"id": "model_id"},
                    "prompt": "system prompt",
                    "temperature": 1,
                    "folderId": "conversations/bucketName",
                    "messages": [
                          {
                          "content": "The file you provided is a Dockerfile.",
                          "role": "assistant",
                          "custom_content": {
                            "attachments": [
                              {
                                "type": "application/octet-stream",
                                "title": "LICENSE",
                                "url": "files/public/License"
                              },
                              {
                                "type": "binary/octet-stream",
                                "title": "Dockerfile",
                                "url": "files/public/Dockerfile"
                              },
                              {
                                "type": "application/vnd.dial.metadata+json",
                                "title": ".dockerignore",
                                "url": "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                              }
                            ]
                          }
                        }
                    ],
                    "selectedAddons": ["R", "T", "G"],
                    "assistantModelId": "assistantId",
                    "lastActivityDate": 4848683153,
                    "playback" : {
                        "messagesStack" : [ {
                          "custom_content" : {
                            "attachments" : [ {
                              "type" : "application/octet-stream",
                              "title" : "LICENSE",
                              "url" : "files/public/License"
                            }, {
                              "type" : "binary/octet-stream",
                              "title" : "Dockerfile",
                              "url" : "files/public/Dockerfile"
                            }, {
                              "type" : "application/vnd.dial.metadata+json",
                              "title" : ".dockerignore",
                              "url" : "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                            } ]
                          }
                        } ]
                    },
                    "replay" : {
                        "replayUserMessagesStack" : [ {
                          "custom_content" : {
                            "attachments" : [ {
                              "type" : "application/octet-stream",
                              "title" : "LICENSE",
                              "url" : "files/public/License"
                            }, {
                              "type" : "binary/octet-stream",
                              "title" : "Dockerfile",
                              "url" : "files/public/Dockerfile"
                            }, {
                              "type" : "application/vnd.dial.metadata+json",
                              "title" : ".dockerignore",
                              "url" : "metadata/files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/"
                            } ]
                          }
                    } ]
                    }
                }
                """, PublicationUtil.replaceConversationLinks(conversationBody, targetResource, Map.of(
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/LICENSE", "files/public/License",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile", "files/public/Dockerfile")));

        verifyJson("""
                {
                    "id": "conversations/bucketName/conversation",
                    "name": "display_name",
                    "model": {"id": "model_id"},
                    "prompt": "system prompt",
                    "temperature": 1,
                    "folderId": "conversations/bucketName",
                    "messages": [
                          {
                          "content": "The file you provided is a Dockerfile.",
                          "role": "assistant",
                          "custom_content": {
                            "attachments": [
                              {
                                "type": "application/octet-stream",
                                "title": "LICENSE",
                                "url": "files/public/License"
                              },
                              {
                                "type": "binary/octet-stream",
                                "title": "Dockerfile",
                                "url": "files/public/Dockerfile"
                              },
                              {
                                "type": "application/vnd.dial.metadata+json",
                                "title": ".dockerignore",
                                "url": "metadata/files/public/attachments/"
                              }
                            ]
                          }
                        }
                    ],
                    "selectedAddons": ["R", "T", "G"],
                    "assistantModelId": "assistantId",
                    "lastActivityDate": 4848683153,
                    "playback" : {
                        "messagesStack" : [ {
                          "custom_content" : {
                            "attachments" : [ {
                              "type" : "application/octet-stream",
                              "title" : "LICENSE",
                              "url" : "files/public/License"
                            }, {
                              "type" : "binary/octet-stream",
                              "title" : "Dockerfile",
                              "url" : "files/public/Dockerfile"
                            }, {
                              "type" : "application/vnd.dial.metadata+json",
                              "title" : ".dockerignore",
                              "url" : "metadata/files/public/attachments/"
                            } ]
                          }
                        } ]
                      },
                      "replay" : {
                        "replayUserMessagesStack" : [ {
                          "custom_content" : {
                            "attachments" : [ {
                              "type" : "application/octet-stream",
                              "title" : "LICENSE",
                              "url" : "files/public/License"
                            }, {
                              "type" : "binary/octet-stream",
                              "title" : "Dockerfile",
                              "url" : "files/public/Dockerfile"
                            }, {
                              "type" : "application/vnd.dial.metadata+json",
                              "title" : ".dockerignore",
                              "url" : "metadata/files/public/attachments/"
                            } ]
                          }
                        } ]
                      }
                    }
                }
                """, PublicationUtil.replaceConversationLinks(conversationBody, targetResource, Map.of(
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/LICENSE", "files/public/License",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/Dockerfile", "files/public/Dockerfile",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/", "files/public/attachments/")));
    }

    @Test
    void testReplaceApplicationIdentity() {
        String application = """
                {
                "name":"applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/my-custom-application",
                "endpoint":"http://application1/v1/completions",
                "display_name":"My Custom Application",
                "display_version":"1.0",
                "icon_url":"http://application1/icon.svg",
                "description":"My Custom Application Description",
                "forward_auth_token":false,
                "defaults": {}
                }
                """;
        ResourceDescription targetResource1 = ResourceDescription.fromDecoded(ResourceType.APPLICATION, "bucketName", "bucket/location/", "my-app");
        verifyJson("""
                {
                "name":"applications/bucketName/my-app",
                "endpoint":"http://application1/v1/completions",
                "display_name":"My Custom Application",
                "display_version":"1.0",
                "icon_url":"http://application1/icon.svg",
                "description":"My Custom Application Description",
                "forward_auth_token":false,
                "defaults": {}
                }
                """, PublicationUtil.replaceApplicationIdentity(application, targetResource1));
    }

    private static void verifyJson(String expected, String actual) {
        try {
            assertEquals(ProxyUtil.MAPPER.readTree(expected).toPrettyString(), ProxyUtil.MAPPER.readTree(actual).toPrettyString());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
