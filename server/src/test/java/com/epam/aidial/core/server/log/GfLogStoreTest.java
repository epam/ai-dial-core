package com.epam.aidial.core.server.log;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.deltix.gflog.api.LogEntry;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                {"id":"1d84aa54-e476-405d-9713-386bdfc85993","object":"chat.completion","created":"1687222196","model":"gpt-35-turbo","usage":{"junk_string":"junk","junk_integer":1,"junk_float":1.0,"junk_null":null,"junk_true":true,"junk_false":false,"completion_tokens":10,"prompt_tokens":20,"total_tokens":30},"statistics":{"usage_per_model":[{"name":"text-embedding-ada-002","prompt_tokens":23,"total_tokens":23},{"name":"gpt-4","prompt_tokens":123,"completion_tokens":17,"total_tokens":140}]},"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"As an AI language model, I don't have emotions, but I'm functioning perfectly well. How can I assist you today?","custom_content":{"attachments":[{"url":"url2"},{"url":"url1"}],"stages":[{"name":"stage1","status":"completed"},{"name":"stage2","status":"completed"}],"controls":[{"label":"label1"},{"label":"label2"}],"state":{"p1":1,"p2":1}}}}]}""";
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
        assertNotNull(res);
        String expected = """
                {"id":"3c9c699a-d1ef-4ec2-82ff-47a07206fa99","object":"chat.completion","created":1724242846,"model":null,"choices":[{"index":0,"finish_reason":null,"message":{"role":"assistant","custom_content":{"attachments":[{"type":"text/markdown","title":"[0] 'Architecture'","data":"data","reference_url":"url1"},{"type":"text/markdown","title":"[1] 'User Guide'","data":"data","reference_url":"url2"},{"type":"text/markdown","title":"[2] 'Knowledge Base'","data":"data","reference_url":"url3"},{"type":"text/markdown","title":"[3] 'Documentation'","data":"you can pick one of three formats to copy its data: CSV, Markdown or Text.\\n\\n","reference_url":"url4"}]},"content":"A B C"}}]}""";
        assertEquals(expected, res);
    }

    @Test
    public void testGetParentDeployment_NoInterceptors() {
        ProxyContext context = mock(ProxyContext.class);
        // app calls model without interceptors
        when(context.getInterceptors()).thenReturn(null);
        when(context.getSourceDeployment()).thenReturn("app");

        String result = GfLogStore.getParentDeployment(context);

        assertEquals("app", result);
    }

    @Test
    public void testGetParentDeployment_DeploymentWithInterceptors1() {
        ProxyContext context = mock(ProxyContext.class);
        // app calls model with interceptors
        List<String> interceptors = List.of("interceptor1", "interceptor2");
        when(context.getInterceptors()).thenReturn(interceptors);
        List<String> executionPath = List.of("app", "interceptor1", "interceptor2", "model");
        when(context.getExecutionPath()).thenReturn(executionPath);

        String result = GfLogStore.getParentDeployment(context);

        assertEquals("app", result);
    }

    @Test
    public void testGetParentDeployment_DeploymentWithInterceptors2() {
        ProxyContext context = mock(ProxyContext.class);
        // chat calls model with interceptors
        List<String> interceptors = List.of("interceptor1", "interceptor2");
        when(context.getInterceptors()).thenReturn(interceptors);
        List<String> executionPath = List.of("interceptor1", "interceptor2", "model");
        when(context.getExecutionPath()).thenReturn(executionPath);

        String result = GfLogStore.getParentDeployment(context);

        assertNull(result);
    }

    @Test
    public void testGetParentDeployment_InterceptorPathMismatch() {
        ProxyContext context = mock(ProxyContext.class);
        // app calls model with interceptors but interceptor1 calls some dep1 in the middle using the same per request key
        List<String> interceptors = List.of("interceptor1", "interceptor2");
        when(context.getInterceptors()).thenReturn(interceptors);
        List<String> executionPath = List.of("app", "interceptor1", "dep1", "interceptor2", "model");
        when(context.getExecutionPath()).thenReturn(executionPath);

        String result = GfLogStore.getParentDeployment(context);

        assertNull(result);
    }

    @SneakyThrows
    @Test
    public void testAppendAndEscape() {
        final int len = 120;
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) i);
        }
        String s = sb.toString();
        LogEntry entry = mock(LogEntry.class);
        StringBuilder buffer = new StringBuilder();
        when(entry.append(anyChar())).thenAnswer(cb -> {
            buffer.append((char) cb.getArgument(0));
            return null;
        });
        when(entry.append(anyString(), anyInt(), anyInt())).thenAnswer(cb -> {
            buffer.append((String) cb.getArgument(0), cb.getArgument(1), cb.getArgument(2));
            return null;
        });
        when(entry.append(anyString())).thenAnswer(cb -> {
            buffer.append((String) cb.getArgument(0));
            return null;
        });
        GfLogStore.append(entry, s, true);
        String expected = "\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007\\b\\t\\n\\u000B\\f\\r\\u000E\\u000F"
                + "\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017\\u0018\\u0019\\u001A\\u001B\\u001C\\"
                + "u001D\\u001E\\u001F !\\\"#$%&'()*+,-.\\/0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\\\]^_`abcdefghijklmnopqrstuvw";
        String actual = buffer.toString();
        assertEquals(expected, actual);
        assertDoesNotThrow(() -> ProxyUtil.MAPPER.readValue("\"" + actual + "\"", String.class));
    }

    @SneakyThrows
    @Test
    public void testAppendClaims() {
        ProxyContext context = mock(ProxyContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(List.of("admin", "reader"));
        when(context.getProject()).thenReturn("proj");
        when(context.getUserHash()).thenReturn("hash");
        when(context.getUserDisplayName()).thenReturn("Jane \"Doe\"");

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(true, false, Set.of()).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("userId").asText());
        assertEquals("admin", claims.get("roles").get(0).asText());
        assertEquals("reader", claims.get("roles").get(1).asText());
        assertEquals("proj", claims.get("project").asText());
        assertEquals("hash", claims.get("userHash").asText());
        assertEquals("Jane \"Doe\"", claims.get("userDisplayName").asText());
    }

    @SneakyThrows
    @Test
    public void testAppendClaimsSkipsNullFields() {
        ProxyContext context = mock(ProxyContext.class);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(null);
        when(context.getProject()).thenReturn(null);
        when(context.getUserHash()).thenReturn(null);
        when(context.getUserDisplayName()).thenReturn(null);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(true, false, Set.of()).appendClaims(context, entry);

        JsonNode claims = parseWrapped(buffer.toString());
        assertEquals("user-1", claims.get("userId").asText());
        assertFalse(claims.has("roles"));
        assertFalse(claims.has("project"));
        assertFalse(claims.has("userHash"));
        assertFalse(claims.has("userDisplayName"));
    }

    @SneakyThrows
    @Test
    public void testAppendHeadersAppliesBlacklistAndJoinsMultiValues() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Authorization", "Bearer secret");
        headers.add("API-KEY", "key-secret");
        headers.add("X-Conversation-Id", "conv-1");
        headers.add("Accept", "text/plain");
        headers.add("Accept", "application/json");

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(headers);
        ProxyContext context = mock(ProxyContext.class);
        when(context.getRequest()).thenReturn(request);

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        new GfLogStore(false, true, Set.of("authorization", "api-key")).appendHeaders(context, entry);

        JsonNode headerNode = parseWrapped(buffer.toString());
        assertFalse(headerNode.has("Authorization"));
        assertFalse(headerNode.has("API-KEY"));
        assertEquals("conv-1", headerNode.get("X-Conversation-Id").asText());
        assertEquals("text/plain, application/json", headerNode.get("Accept").asText());
    }

    @SneakyThrows
    @Test
    public void testClaimsAndHeadersComposeIntoValidLogJson() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Authorization", "Bearer secret");
        headers.add("X-Conversation-Id", "conv-1");

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.headers()).thenReturn(headers);
        ProxyContext context = mock(ProxyContext.class);
        when(context.getRequest()).thenReturn(request);
        when(context.getUserId()).thenReturn("user-1");
        when(context.getUserRoles()).thenReturn(List.of("admin"));
        when(context.getProject()).thenReturn("proj");

        StringBuilder buffer = new StringBuilder();
        LogEntry entry = capturingEntry(buffer);

        GfLogStore store = new GfLogStore(true, true, Set.of("authorization"));
        // mirror the order/position used by the real log line: both sections follow a preceding object member
        store.appendClaims(context, entry);
        store.appendHeaders(context, entry);

        JsonNode root = ProxyUtil.MAPPER.readTree("{\"user\":{}" + buffer + "}");
        assertEquals("user-1", root.get("claims").get("userId").asText());
        assertEquals("proj", root.get("claims").get("project").asText());
        assertFalse(root.get("headers").has("Authorization"));
        assertEquals("conv-1", root.get("headers").get("X-Conversation-Id").asText());
    }

    private static LogEntry capturingEntry(StringBuilder buffer) {
        LogEntry entry = mock(LogEntry.class);
        when(entry.append(anyChar())).thenAnswer(cb -> {
            buffer.append((char) cb.getArgument(0));
            return entry;
        });
        when(entry.append(anyString(), anyInt(), anyInt())).thenAnswer(cb -> {
            buffer.append((String) cb.getArgument(0), cb.getArgument(1), cb.getArgument(2));
            return entry;
        });
        when(entry.append(anyString())).thenAnswer(cb -> {
            buffer.append((String) cb.getArgument(0));
            return entry;
        });
        return entry;
    }

    @SneakyThrows
    private static JsonNode parseWrapped(String member) {
        // member looks like: ,"claims":{...} ; wrap into a valid object and return the value node
        String json = "{" + member.substring(1) + "}";
        JsonNode root = ProxyUtil.MAPPER.readTree(json);
        return root.elements().next();
    }
}
