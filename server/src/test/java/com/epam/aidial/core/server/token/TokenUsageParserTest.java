package com.epam.aidial.core.server.token;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@SuppressWarnings("checkstyle:LineLength")
class TokenUsageParserTest {

    @Test
    void testValidBatchResponse() {
        valid("""
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
                        "content": "As an AI language model, I do not have emotions like humans. However, I am functioning well and ready to assist you. How can I help you today?"
                      }
                    }
                  ],
                  "usage" \t\r\n : \t\r\n {
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
                """, 33, 19, 0, 52);
    }

    @Test
    void testValidStreamResponse() {
        valid("""
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"As"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" an"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" AI"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" language"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" model"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":","}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" I"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" don"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"'t"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" have"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" emotions"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":","}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" but"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" I"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"'m"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" functioning"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" perfectly"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" well"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"."}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" How"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" can"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" I"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" assist"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" you"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" today"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"?"}}],"usage":null}

                 data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":"stop","delta":{}}],
                         "usage" \n\t\r : \n\t\r {
                             "junk_string": "junk",
                             "junk_integer" : 1,
                             "junk_float" : 1.0,
                             "junk_null" : null,
                             "junk_true" : true,
                             "junk_false" : false,
                             "completion_tokens": 10,
                             "prompt_tokens": 20,
                             "total_tokens": 30
                           }
                       }
                 data:
                       {
                        "id": "1d84aa54-e476-405d-9713-386bdfc85993",
                        "object": "chat.completion.chunk",
                        "created": "1687222196",
                        "statistics": {
                          "usage_per_model": [
                            {
                              "index": 0,
                              "name": "text-embedding-ada-002",
                              "prompt_tokens": 23,
                              "total_tokens": 23
                            },
                            {
                              "index": 1,
                              "name": "gpt-4",
                              "prompt_tokens": 123,
                              "completion_tokens": 17,
                              "total_tokens": 140
                            }
                          ]
                        }
                       }

                 data: [DONE]

                """, 10, 20, 0, 30);
    }

    @Test
    void testValidStreamResponseWithMultipleUsages() {
        valid("""
                data: {"id":"eb69ae53-055b-4182-af8f-47f5f3ce810c","object":"chat.completion.chunk","created":1714665540,"model":"dbrx-instruct-032724","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}],"usage":{"prompt_tokens":226,"completion_tokens":1,"total_tokens":227}}

                data: {"id":"eb69ae53-055b-4182-af8f-47f5f3ce810c","object":"chat.completion.chunk","created":1714665540,"model":"dbrx-instruct-032724","choices":[{"index":0,"delta":{"role":"assistant","content":" today"},"finish_reason":null}],"usage":{"prompt_tokens":226,"completion_tokens":25,"total_tokens":251}}

                data: {"id":"eb69ae53-055b-4182-af8f-47f5f3ce810c","object":"chat.completion.chunk","created":1714665540,"model":"dbrx-instruct-032724","choices":[{"index":0,"delta":{"role":"assistant","content":"."},"finish_reason":null}],"usage":{"prompt_tokens":226,"completion_tokens":26,"total_tokens":252}}

                data: {"id":"eb69ae53-055b-4182-af8f-47f5f3ce810c","object":"chat.completion.chunk","created":1714665540,"model":"dbrx-instruct-032724","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":"stop"}],"usage":{"prompt_tokens":226,"completion_tokens":26,"total_tokens":252,"prompt_tokens_details":{"cached_tokens": 100,"audio_tokens": 0}}}

                data: [DONE]

                """, 26, 226, 100, 252);
    }

    @Test
    void testValidResponseWithDetails() {
        valid("""
                {
                  "id": "eb69ae53-055b-4182-af8f-47f5f3ce810c",
                  "object": "chat.completion",
                  "created": 1687222196,
                  "model": "gpt-4o-2024-05-13",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "some content",
                        "refusal": null
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "foo": [{"a": {}}],
                    "prompt_tokens": 1420,
                    "completion_tokens": 119,
                    "total_tokens": 1539,
                    "prompt_tokens_details": {
                      "cached_tokens": 1024,
                      "cache_write_tokens": 256,
                      "audio_tokens": 0
                    },
                    "completion_tokens_details": {
                      "reasoning_tokens": 64,
                      "audio_tokens": 0,
                      "accepted_prediction_tokens": 0,
                      "rejected_prediction_tokens": 0,
                      "some_custom_field": {
                        "some_nested_field": "some_value"
                      }
                    }
                  }
                }
                """, 119, 1420, 1024, 256, 64, 1539);
    }

    private void valid(String body, long completion, long prompt, long cachedPrompt, long total) {
        valid(body, completion, prompt, cachedPrompt, 0, 0, total);
    }

    private void valid(String body, long completion, long prompt, long cachedPrompt, long cacheWritePrompt, long reasoning, long total) {
        TokenUsage usage = TokenUsageParser.parse(Buffer.buffer(body));
        Assertions.assertNotNull(usage);
        Assertions.assertEquals(usage.getCompletionTokens(), completion);
        Assertions.assertEquals(usage.getPromptTokens(), prompt);
        Assertions.assertEquals(usage.getTotalTokens(), total);
        long actualCachedPrompt = 0;
        long actualCacheWritePrompt = 0;
        if (usage.getPromptTokensDetails() != null) {
            actualCachedPrompt = usage.getPromptTokensDetails().getCachedTokens();
            actualCacheWritePrompt = usage.getPromptTokensDetails().getCacheWriteTokens();
        }
        Assertions.assertEquals(actualCachedPrompt, cachedPrompt);
        Assertions.assertEquals(actualCacheWritePrompt, cacheWritePrompt);
        long actualReasoning = 0;
        if (usage.getCompletionTokensDetails() != null) {
            actualReasoning = usage.getCompletionTokensDetails().getReasoningTokens();
        }
        Assertions.assertEquals(actualReasoning, reasoning);
    }
}