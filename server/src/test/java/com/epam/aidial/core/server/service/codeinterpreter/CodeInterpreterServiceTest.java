package com.epam.aidial.core.server.service.codeinterpreter;

import com.epam.aidial.core.server.log.JsonLogCapture;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationOperatorService;
import com.epam.aidial.core.server.tracing.BackgroundOperationTracer;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
class CodeInterpreterServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void sessionCheckFailureIsLoggedWithTheRunsTraceId(Vertx vertx) {
        ResourceService resourceService = mock(ResourceService.class);
        when(resourceService.getPrefix()).thenReturn("test");

        ApplicationOperatorService operatorService = mock(ApplicationOperatorService.class);
        when(operatorService.isActive()).thenReturn(true);

        RScoredSortedSet<String> activeSessions = mock(RScoredSortedSet.class);
        doThrow(new IllegalStateException("redis is down")).when(activeSessions)
                .valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean(), anyInt(), anyInt());
        RedissonClient redisson = mock(RedissonClient.class);
        doReturn(activeSessions).when(redisson).getScoredSortedSet(anyString());

        JsonObject settings = new JsonObject()
                .put("sessionProxyUrl", "http://localhost:1")
                .put("sessionImage", "image")
                .put("checkPeriod", 20);

        try (JsonLogCapture logs = JsonLogCapture.attach()) {
            new CodeInterpreterService(vertx, new AsyncTaskExecutor(vertx, new JsonObject()), redisson, resourceService,
                    mock(AccessService.class), mock(EncryptionService.class), operatorService,
                    new BackgroundOperationTracer(vertx), () -> "id", settings);

            List<JsonNode> lines = logs.await("Failed to check active sessions", 2);
            for (JsonNode line : lines) {
                assertEquals("redis is down", line.path("Attributes").path("exception.message").asText());
                assertEquals(32, line.path("TraceId").asText().length());
                assertEquals(16, line.path("SpanId").asText().length());
            }
            assertNotEquals(lines.get(0).path("TraceId").asText(), lines.get(1).path("TraceId").asText(),
                    "every run must get a trace id of its own");
        }
    }
}
