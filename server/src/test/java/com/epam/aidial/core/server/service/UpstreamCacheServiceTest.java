package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.data.cache.CachedUpstreamEntry;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.MessagesApiRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.service.LockService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ConfigSupport;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UpstreamCacheServiceTest {

    private static RedisServer redisServer;

    private static RedissonClient redissonClient;

    private LockService lockService;

    private UpstreamCacheService service;

    @BeforeAll
    public static void beforeAll() throws IOException {
        redisServer = RedisServer.newRedisServer()
                .port(16370)
                .bind("127.0.0.1")
                .setting("maxmemory 16M")
                .setting("maxmemory-policy volatile-lfu")
                .build();
        redisServer.start();
        ConfigSupport configSupport = new ConfigSupport();
        org.redisson.config.Config redisClientConfig = configSupport.fromJSON("""
                {
                  "singleServerConfig": {
                     "address": "redis://localhost:16370"
                  }
                }
                """, org.redisson.config.Config.class);

        redissonClient = Redisson.create(redisClientConfig);
    }

    @AfterAll
    public static void afterAll() throws IOException {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @BeforeEach
    public void beforeEach() {
        lockService = new LockService(redissonClient, null);
    }

    @Test
    public void testUpdateEntry() {
        service = new UpstreamCacheService(redissonClient, lockService, System::currentTimeMillis, null);

        service.updateEntry("hash", new CachedUpstreamEntry("http://localhost:8080/chat", null, "prefix.body.messages[1]", null), new Model(), null);

        assertTrue(redissonClient.getKeys().getKeys().iterator().hasNext());
    }

    @Test
    public void testUpdateEntryWithoutEndpointPinsById() throws JsonProcessingException {
        // an upstream configured through interfaces carries no legacy endpoint; Redis rejects a null value,
        // so the field is omitted and the id alone identifies the pinned upstream
        service = new UpstreamCacheService(redissonClient, lockService, System::currentTimeMillis, null);
        String body = """
                {
                    "messages": [
                        {
                            "role": "user",
                            "content": "hello",
                            "custom_fields": {
                                "cache_breakpoint": {}
                            }
                        }
                    ]
                }
                """;
        RequestObject request = new ChatCompletionRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
        Model model = new Model();
        model.setName("interfaces-model");

        CacheBreakpointContext context = service.buildCacheBreakpointContext(
                request, CachePolicy.AVAILABILITY_PRIORITY, model, InterfaceType.OPENAI_CHAT_COMPLETIONS);
        String breakpoint = context.breakpoints().get(context.breakpoints().size() - 1);

        service.updateEntry(context.prefixToHash().get(breakpoint),
                new CachedUpstreamEntry(null, "up-1", breakpoint, null), model, null);

        CachedUpstreamEntry entry = service.getCacheEntry(context, model);
        assertNotNull(entry);
        assertNull(entry.endpoint());
        assertEquals("up-1", entry.id());
    }

    @Test
    public void testBuildCacheBreakpointContext_withBreakpoints() throws JsonProcessingException {
        service = new UpstreamCacheService(redissonClient, lockService, System::currentTimeMillis, null);
        String body = """
                {
                    "tools": [
                        {
                            "type": "function",
                            "function": {
                                "name": "some_long_function",
                                "description": "desc"
                            }
                        },
                        {
                            "type": "function",
                            "function": {
                                "name": "some_long_function_1",
                                "description": "...",
                                "args": {
                                    "type": "object",
                                    "properties": {
                                        "a": {
                                            "type": "string"
                                        }
                                    }
                                }
                            },
                            "custom_fields": {
                                "cache_breakpoint": {},
                                "some_random_key": "some_random_value"
                            }
                        }
                    ],
                    "messages": [
                        {
                            "role": "system",
                            "content": "System prompt",
                            "custom_fields": {
                                "cache_breakpoint": {
                                    "expire_at": "2025-10-02T15:01:23Z"
                                }
                            }
                        },
                        {
                            "role": "user",
                            "content": "Here is a file, say hello",
                            "custom_fields": {
                                "cache_breakpoint": {
                                    "expire_at": "2025-10-03T15:01:23Z"
                                }
                            },
                            "custom_content": {
                                "state": {
                                    "some_field": "some_value",
                                    "some_random_key": "some_random_value"
                                },
                                "attachments": [
                                    {
                                        "url": "URL"
                                    },
                                    {
                                        "data": "long long data..."
                                    }
                                ]
                            }
                        },
                        {
                            "role": "assistant",
                            "content": [
                                {
                                    "type": "text",
                                    "text": "..."
                                },
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": "..."
                                    }
                                }
                            ],
                            "custom_fields": {
                                "cache_breakpoint": {
                                    "expire_at": "2025-10-04T15:01:23Z"
                                }
                            }
                        }
                    ]
                }
                """;
        RequestObject request = new ChatCompletionRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
        Model model = new Model();
        model.setName("gpt-4");

        CacheBreakpointContext context = service.buildCacheBreakpointContext(request, CachePolicy.AVAILABILITY_PRIORITY, model, InterfaceType.OPENAI_CHAT_COMPLETIONS);

        assertNotNull(context);
        assertEquals(4, context.breakpoints().size());
        List<String> expectedBreakpoints = List.of("prefix.body.tools[1]", "prefix.body.messages[0]", "prefix.body.messages[1]", "prefix.body.messages[2]");
        assertEquals(expectedBreakpoints, context.breakpoints());
        assertEquals(5, context.prefixToHash().size());
        assertEquals(CachePolicy.AVAILABILITY_PRIORITY, context.policy());
    }

    @Test
    public void testBuildCacheBreakpointContext_chatCompletionsIgnoresFieldsHashingOrderOverride() throws JsonProcessingException {
        service = new UpstreamCacheService(redissonClient, lockService, System::currentTimeMillis, null);
        String body = """
                {
                    "tools": [
                        {
                            "type": "function",
                            "function": {"name": "some_function"},
                            "custom_fields": {"cache_breakpoint": {}}
                        }
                    ],
                    "messages": [
                        {"role": "user", "content": "hi"}
                    ]
                }
                """;
        RequestObject request = new ChatCompletionRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
        Model model = new Model();
        model.setName("gpt-4");
        // fieldsHashingOrder is deprecated and must have no effect, even for chat completions
        model.setFieldsHashingOrder(List.of("prefix.body.messages"));

        CacheBreakpointContext context = service.buildCacheBreakpointContext(
                request, CachePolicy.AVAILABILITY_PRIORITY, model, InterfaceType.OPENAI_CHAT_COMPLETIONS);

        List<String> expectedBreakpoints = List.of("prefix.body.tools[0]");
        assertEquals(expectedBreakpoints, context.breakpoints());
    }

    @Test
    public void testBuildCacheBreakpointContext_autoCaching() throws JsonProcessingException {
        service = new UpstreamCacheService(redissonClient, lockService, System::currentTimeMillis, null);
        String body = """
                {
                    "tools": [
                        {
                            "type": "function",
                            "function": {
                                "name": "some_long_function",
                                "description": "desc"
                            }
                        }
                    ],
                    "messages": [
                        {
                            "role": "system",
                            "content": "System prompt"
                        },
                        {
                            "role": "user",
                            "content": "Here is a file, say hello",
                            "custom_content": {
                                "state": {
                                    "some_field": "some_value",
                                    "some_random_key": "some_random_value"
                                },
                                "attachments": [
                                    {
                                        "url": "URL"
                                    },
                                    {
                                        "data": "long long data..."
                                    }
                                ]
                            }
                        },
                        {
                            "role": "assistant",
                            "content": [
                                {
                                    "type": "text",
                                    "text": "..."
                                },
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": "..."
                                    }
                                }
                            ]
                        }
                    ]
                }
                """;
        RequestObject request = new ChatCompletionRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
        Model model = new Model();
        model.setName("gpt-4");
        Features features = new Features();
        features.setAutoCachingSupported(true);
        model.setFeatures(features);

        CacheBreakpointContext context = service.buildCacheBreakpointContext(request, CachePolicy.AVAILABILITY_PRIORITY, model, InterfaceType.OPENAI_CHAT_COMPLETIONS);

        assertNotNull(context);
        assertEquals(4, context.breakpoints().size());
        List<String> expectedBreakpoints = List.of("prefix.body.tools[0]", "prefix.body.messages[0]", "prefix.body.messages[1]", "prefix.body.messages[2]");
        assertEquals(expectedBreakpoints, context.breakpoints());
        assertEquals(4, context.prefixToHash().size());
        assertEquals(CachePolicy.AVAILABILITY_PRIORITY, context.policy());
    }

    @Test
    public void testGetCacheEntry_entryFound() throws JsonProcessingException {
        service = new UpstreamCacheService(redissonClient, lockService, System::currentTimeMillis, null);
        String body = """
                {
                    "tools": [
                        {
                            "type": "function",
                            "function": {
                                "name": "some_long_function",
                                "description": "desc"
                            }
                        },
                        {
                            "type": "function",
                            "function": {
                                "name": "some_long_function_1",
                                "description": "...",
                                "args": {
                                    "type": "object",
                                    "properties": {
                                        "a": {
                                            "type": "string"
                                        }
                                    }
                                }
                            },
                            "custom_fields": {
                                "cache_breakpoint": {},
                                "some_random_key": "some_random_value"
                            }
                        }
                    ],
                    "messages": [
                        {
                            "role": "system",
                            "content": "System prompt",
                            "custom_fields": {
                                "cache_breakpoint": {
                                    "expire_at": "2025-10-02T15:01:23Z"
                                }
                            }
                        },
                        {
                            "role": "user",
                            "content": "Here is a file, say hello",
                            "custom_fields": {
                                "cache_breakpoint": {
                                    "expire_at": "2025-10-03T15:01:23Z"
                                }
                            },
                            "custom_content": {
                                "state": {
                                    "some_field": "some_value",
                                    "some_random_key": "some_random_value"
                                },
                                "attachments": [
                                    {
                                        "url": "URL"
                                    },
                                    {
                                        "data": "long long data..."
                                    }
                                ]
                            }
                        },
                        {
                            "role": "assistant",
                            "content": [
                                {
                                    "type": "text",
                                    "text": "..."
                                },
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": "..."
                                    }
                                }
                            ],
                            "custom_fields": {
                                "cache_breakpoint": {
                                    "expire_at": "2025-10-04T15:01:23Z"
                                }
                            }
                        }
                    ]
                }
                """;
        RequestObject request = new ChatCompletionRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
        Model model = new Model();
        model.setName("gpt-4");

        CacheBreakpointContext context = service.buildCacheBreakpointContext(request, CachePolicy.AVAILABILITY_PRIORITY, model, InterfaceType.OPENAI_CHAT_COMPLETIONS);
        Map<String, String> prefixToHash = context.prefixToHash();
        for (var breakpoint : context.breakpoints()) {
            CachedUpstreamEntry cachedUpstreamEntry = new CachedUpstreamEntry("http://host/chat", null, breakpoint, null);
            service.updateEntry(prefixToHash.get(breakpoint), cachedUpstreamEntry, model, null);
        }

        CachedUpstreamEntry entry = service.getCacheEntry(context, model);
        assertNotNull(entry);
        assertEquals("prefix.body.messages[2]", entry.prefixPath());
        assertEquals("http://host/chat", entry.endpoint());
    }

    @Test
    public void testGetCacheEntry_entryNotFound() throws JsonProcessingException {
        service = new UpstreamCacheService(redissonClient, lockService, System::currentTimeMillis, null);
        String body = """
                {
                    "tools": [
                        {
                            "type": "function",
                            "function": {
                                "name": "some_long_function",
                                "description": "desc"
                            }
                        },
                        {
                            "type": "function",
                            "function": {
                                "name": "some_long_function_1",
                                "description": "...",
                                "args": {
                                    "type": "object",
                                    "properties": {
                                        "a": {
                                            "type": "string"
                                        }
                                    }
                                }
                            },
                            "custom_fields": {
                                "cache_breakpoint": {},
                                "some_random_key": "some_random_value"
                            }
                        }
                    ],
                    "messages": [
                        {
                            "role": "system",
                            "content": "System prompt",
                            "custom_fields": {
                                "cache_breakpoint": {
                                    "expire_at": "2025-10-02T15:01:23Z"
                                }
                            }
                        },
                        {
                            "role": "user",
                            "content": "Here is a file, say hello",
                            "custom_fields": {
                                "cache_breakpoint": {
                                    "expire_at": "2025-10-03T15:01:23Z"
                                }
                            },
                            "custom_content": {
                                "state": {
                                    "some_field": "some_value",
                                    "some_random_key": "some_random_value"
                                },
                                "attachments": [
                                    {
                                        "url": "URL"
                                    },
                                    {
                                        "data": "long long data..."
                                    }
                                ]
                            }
                        },
                        {
                            "role": "assistant",
                            "content": [
                                {
                                    "type": "text",
                                    "text": "..."
                                },
                                {
                                    "type": "image_url",
                                    "image_url": {
                                        "url": "..."
                                    }
                                }
                            ],
                            "custom_fields": {
                                "cache_breakpoint": {
                                    "expire_at": "2025-10-04T15:01:23Z"
                                }
                            }
                        }
                    ]
                }
                """;
        RequestObject request = new ChatCompletionRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
        Model model = new Model();
        model.setName("gpt-4");

        CacheBreakpointContext context = service.buildCacheBreakpointContext(request, CachePolicy.AVAILABILITY_PRIORITY, model, InterfaceType.OPENAI_CHAT_COMPLETIONS);

        CachedUpstreamEntry entry = service.getCacheEntry(context, model);
        assertNotNull(entry);
        assertEquals("prefix.body.messages[2]", entry.prefixPath());
        assertNull(entry.endpoint());
    }

    @Test
    public void testBuildCacheBreakpointContext_anthropicIgnoresFieldsHashingOrderOverride() throws JsonProcessingException {
        service = new UpstreamCacheService(redissonClient, lockService, System::currentTimeMillis, null);
        String body = """
                {
                    "system": [
                        {"type": "text", "text": "System prompt", "cache_control": {"type": "ephemeral"}}
                    ],
                    "messages": [
                        {
                            "role": "user",
                            "content": [
                                {"type": "text", "text": "hi"},
                                {"type": "text", "text": "there", "cache_control": {"type": "ephemeral"}}
                            ]
                        }
                    ]
                }
                """;
        RequestObject request = new MessagesApiRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
        Model model = new Model();
        model.setName("claude");
        // a chat-completions-only override that would drop `system` entirely if Anthropic ever inherited it
        model.setFieldsHashingOrder(List.of("prefix.body.tools", "prefix.body.messages"));

        CacheBreakpointContext context = service.buildCacheBreakpointContext(
                request, CachePolicy.AVAILABILITY_PRIORITY, model, InterfaceType.ANTHROPIC_MESSAGES);

        List<String> expectedBreakpoints = List.of("prefix.body.system[0]", "prefix.body.messages[0].content[1]");
        assertEquals(expectedBreakpoints, context.breakpoints());
    }

    @Test
    public void testBuildCacheBreakpointContext_responsesBuiltInOrder_autoCaching() throws JsonProcessingException {
        service = new UpstreamCacheService(redissonClient, lockService, System::currentTimeMillis, null);
        String body = """
                {
                    "instructions": "Be concise",
                    "input": [
                        {"role": "user", "content": "Hi"},
                        {"role": "assistant", "content": "Hello"}
                    ]
                }
                """;
        RequestObject request = new ResponsesApiRequest((ObjectNode) ProxyUtil.MAPPER.readTree(body));
        Model model = new Model();
        model.setName("gpt-5-responses");
        Features features = new Features();
        features.setAutoCachingSupported(true);
        model.setFeatures(features);

        CacheBreakpointContext context = service.buildCacheBreakpointContext(
                request, CachePolicy.AVAILABILITY_PRIORITY, model, InterfaceType.OPENAI_RESPONSES);

        List<String> expectedBreakpoints = List.of("prefix.body.instructions[0]", "prefix.body.input[0]", "prefix.body.input[1]");
        assertEquals(expectedBreakpoints, context.breakpoints());
    }

    @Test
    public void testSortObjectProperties_simple() throws JsonProcessingException {
        String simpleJson = """
                 {
                   "d" : {
                     "b" : 1,
                     "a": 2
                   },
                   "c": [
                     {"d": true,"a": "text"},
                     {"z": 3, "a": false}
                   ],
                   "a" : {
                     "z" : 1,
                     "f": 2
                   }
                 }
                 """;
        JsonNode node = ProxyUtil.MAPPER.readTree(simpleJson);
        JsonNode result = JsonUtil.sort(node);
        assertEquals("""
                 {"a":{"f":2,"z":1},"c":[{"a":"text","d":true},{"a":false,"z":3}],"d":{"a":2,"b":1}}""", result.toString());
    }
}
