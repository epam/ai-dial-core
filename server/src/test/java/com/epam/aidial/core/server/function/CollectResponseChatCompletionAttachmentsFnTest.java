package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class CollectResponseChatCompletionAttachmentsFnTest {
    @Mock
    private ProxyContext context;

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
        when(context.isStreamingRequest()).thenReturn(false);

        Set<String> files = new CollectResponseChatCompletionAttachmentsFn(null, context)
                .collectAttachments((ObjectNode) ProxyUtil.MAPPER.readTree(response));

        assertEquals(Set.of("files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/file1.txt",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/file2.txt"), files);

    }

    @Test
    public void testCollectAttachmentsFromResponse_ChatStreamingResponse() throws JsonProcessingException {
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
                      "delta": {
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
        when(context.isStreamingRequest()).thenReturn(true);

        Set<String> files = new CollectResponseChatCompletionAttachmentsFn(null, context)
                .collectAttachments((ObjectNode) ProxyUtil.MAPPER.readTree(response));

        assertEquals(Set.of("files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/file1.txt",
                "files/7G9WZNcoY26Vy9D7bEgbv6zqbJGfyDp9KZyEbJR4XMZt/b1/file2.txt"), files);

    }
}