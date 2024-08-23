package com.epam.aidial.core.log;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("checkstyle:LineLength")
public class GfLogStoreTest {

    @Test
    public void testIsStreamingResponse() {
        String batchResponse = """
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
                  "usage" \t\r : \t\r {
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
        assertFalse(GfLogStore.isStreamingResponse(Buffer.buffer(batchResponse)));
        String streamingResponse = """
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant"}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"As"}}],"usage":null}
                 
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
                data: [DONE]
                """;
        assertTrue(GfLogStore.isStreamingResponse(Buffer.buffer(streamingResponse)));
    }

    @Test
    public void testAssembleStreamingResponse() {
        String streamingResponse = """
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant"}}],"usage":null}
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"As", "custom_content": {"attachments": [{"index": 1, "url": "url1"}]}}}],"usage":null}
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" an", "custom_content": {"attachments": [{"index": 0, "url": "url2"}]}}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" AI"}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" language"}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" model", "custom_content": {"stages": [{"index": 0, "name": "stage1", "status": "completed"}]}}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":","}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" I", "custom_content": {"stages": [{"index": 1, "name": "stage2", "status": "completed"}]}}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" don"}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"'t"}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" have", "custom_content": {"controls": [{"index": 0, "label": "label1"}]}}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" emotions"}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":","}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" but", "custom_content": {"controls": [{"index": 1, "label": "label2"}]}}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" I", "custom_content": {"state": {"p1": 1}}}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":"'m"}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" functioning"}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" perfectly"}}],"usage":null}
                 
                data: {"id":"chatcmpl-7VfCSOSOS1gYQbDFiEMyh71RJSy1m","object":"chat.completion.chunk","created":1687780896,"model":"gpt-35-turbo","choices":[{"index":0,"finish_reason":null,"delta":{"content":" well","custom_content": {"state": {"p2": 1}}}}],"usage":null}
                 
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
                                
                """;
        String res = GfLogStore.assembleStreamingResponse(Buffer.buffer(streamingResponse));
        assertNotNull(res);
        String expected = """
                {"id":"1d84aa54-e476-405d-9713-386bdfc85993","object":"chat.completion","created":"1687222196","model":"gpt-35-turbo","usage":{"junk_string":"junk","junk_integer":1,"junk_float":1.0,"junk_null":null,"junk_true":true,"junk_false":false,"completion_tokens":10,"prompt_tokens":20,"total_tokens":30},"statistics":{"usage_per_model":[{"name":"text-embedding-ada-002","prompt_tokens":23,"total_tokens":23},{"name":"gpt-4","prompt_tokens":123,"completion_tokens":17,"total_tokens":140}]},"choices":[{"finish_reason":"stop","delta":{"role":"assistant","content":"As an AI language model, I don't have emotions, but I'm functioning perfectly well. How can I assist you today?","custom_content":{"attachments":[{"url":"url2"},{"url":"url1"}],"stages":[{"name":"stage1","status":"completed"},{"name":"stage2","status":"completed"}],"controls":[{"label":"label1"},{"label":"label2"}],"state":{"p1":1,"p2":1}}}}]}""";
        assertEquals(expected, res);
    }

    @Test
    public void testAssembleStreamingResponse2() {
        String streamingResponse = """
                data: {"choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant"}}],"usage":null,"id":"3c9c699a-d1ef-4ec2-82ff-47a07206fa99","created":1724242846,"object":"chat.completion.chunk"}
                data: {"choices":[{"index":0,"finish_reason":null,"delta":{"custom_content":{"attachments":[{"index":0,"type":"text/markdown","title":"[0] 'Architecture'", "data":"data", "reference_url":"url1"}]}}}],"usage":null,"id":"3c9c699a-d1ef-4ec2-82ff-47a07206fa99","created":1724242846,"object":"chat.completion.chunk"}
                data: {"choices":[{"index":0,"finish_reason":null,"delta":{"custom_content":{"attachments":[{"index":1,"type":"text/markdown","title":"[1] 'User Guide'", "data":"data", "reference_url":"url2"}]}}}],"usage":null,"id":"3c9c699a-d1ef-4ec2-82ff-47a07206fa99","created":1724242846,"object":"chat.completion.chunk"}
                
                data: {"choices":[{"index":0,"finish_reason":null,"delta":{"custom_content":{"attachments":[{"index":2,"type":"text/markdown","title":"[2] 'Knowledge Base'","data":"data","reference_url":"url3"}]}}}],"usage":null,"id":"3c9c699a-d1ef-4ec2-82ff-47a07206fa99","created":1724242846,"object":"chat.completion.chunk"}
                
                data: {"choices":[{"index":0,"finish_reason":null,"delta":{"custom_content":{"attachments":[{"index":3,"type":"text/markdown","title":"[3] 'Documentation'","data":"you can pick one of three formats to copy its data: CSV, Markdown or Text.\\n\\n","reference_url":"url4"}]}}}],"usage":null,"id":"3c9c699a-d1ef-4ec2-82ff-47a07206fa99","created":1724242846,"object":"chat.completion.chunk"}
                
                data: {"choices":[{"index":0,"finish_reason":null,"delta":{"content":"A"}}],"usage":null,"id":"3c9c699a-d1ef-4ec2-82ff-47a07206fa99","created":1724242846,"object":"chat.completion.chunk"}
                
                data: {"choices":[{"index":0,"finish_reason":null,"delta":{"content":" B"}}],"usage":null,"id":"3c9c699a-d1ef-4ec2-82ff-47a07206fa99","created":1724242846,"object":"chat.completion.chunk"}
                
                data: {"choices":[{"index":0,"finish_reason":null,"delta":{"content":" C"}}],"usage":null,"id":"3c9c699a-d1ef-4ec2-82ff-47a07206fa99","created":1724242846,"object":"chat.completion.chunk"}
                data: [DONE]
                """;

        String res = GfLogStore.assembleStreamingResponse(Buffer.buffer(streamingResponse));
        System.out.println(res);


    }
}
