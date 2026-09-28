package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

public class ProxyContextTest {

    @Test
    public void testOperationDuration() {
        ProxyContext context = context(null);
        context.setRequestTimestamp(1000L);
        context.setResponseBodyTimestamp(2500L);

        assertEquals(1500, context.calculateOperationDurationMs());
    }

    @Test
    public void testOperationDurationWhenResponseBodyTimestampNotSet() {
        // e.g. a deployment without an endpoint, which responds without ever producing a response body
        ProxyContext context = context(null);
        context.setResponseBodyTimestamp(0L);

        long duration = context.calculateOperationDurationMs();
        assertTrue(duration >= 0 && duration < 60_000, "Unexpected duration: " + duration);
    }

    @Test
    public void testOperationDurationIsNeverNegative() {
        ProxyContext context = context(null);
        context.setRequestTimestamp(2000L);
        // clock moved backwards between the two measurements
        context.setResponseBodyTimestamp(1000L);

        assertEquals(0, context.calculateOperationDurationMs());
    }

    @Test
    public void testUserClaims() {
        ObjectNode claims = ProxyUtil.MAPPER.createObjectNode().put("email", "jane.doe@example.com");

        assertEquals(claims, context(new ExtractedClaims("sub", List.of("role"), "hash", claims, null, "Jane Doe"))
                .getUserClaims());
    }

    @Test
    public void testUserClaimsAreAbsentForApiKeyAuthentication() {
        assertNull(context(null).getUserClaims());
    }

    @Test
    public void testRespondWithNoContentDoesNotLogWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProxyContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            context(null).respond(HttpStatus.NO_CONTENT.getCode(), Buffer.buffer());

            assertTrue(appender.list.isEmpty(), appender.list::toString);
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
    }

    @Test
    public void testRespondWithErrorStatusLogsWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProxyContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            context(null).respond(HttpStatus.BAD_REQUEST.getCode(), Buffer.buffer("boom"));

            assertEquals(1, appender.list.size(), appender.list::toString);
            ILoggingEvent event = appender.list.get(0);
            assertEquals(Level.WARN, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("boom"), event.getFormattedMessage());
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
    }

    @Test
    public void testResolveResponseTreeParsesResponseBodyOnce() {
        ProxyContext context = context(null);
        Buffer body = Buffer.buffer("{\"id\":\"chat-1\"}");
        context.setResponseBody(body);

        JsonNode tree = context.resolveResponseTree(body);

        assertEquals("chat-1", tree.path("id").asText());
        assertSame(tree, context.resolveResponseTree(body));
    }

    @Test
    public void testResolveResponseTreeReusesTheTreeGivenWithTheBody() {
        ProxyContext context = context(null);
        // not what the bytes parse to, to prove the given tree is the one returned
        JsonNode given = ProxyUtil.MAPPER.createObjectNode().put("id", "given");
        Buffer body = Buffer.buffer("{\"id\":\"parsed\"}");
        context.setResponseBody(body, given);

        assertSame(given, context.resolveResponseTree(body));
    }

    @Test
    public void testResolveResponseTreeForgetsTheTreeOfReplacedBody() {
        ProxyContext context = context(null);
        Buffer first = Buffer.buffer("{\"id\":\"first\"}");
        context.setResponseBody(first);
        context.resolveResponseTree(first);
        Buffer second = Buffer.buffer("{\"id\":\"second\"}");
        context.setResponseBody(second);

        assertEquals("second", context.resolveResponseTree(second).path("id").asText());
    }

    @Test
    public void testResolveResponseTreeParsesAnyOtherBufferAfresh() {
        ProxyContext context = context(null);
        context.setResponseBody(Buffer.buffer("{\"id\":\"cached\"}"));
        Buffer other = Buffer.buffer("{\"id\":\"other\"}");

        JsonNode tree = context.resolveResponseTree(other);

        assertEquals("other", tree.path("id").asText());
        assertNotSame(tree, context.resolveResponseTree(other));
    }

    @Test
    public void testResolveResponseTreeOfNonJson() {
        ProxyContext context = context(null);
        Buffer body = Buffer.buffer("data: {}\n\n");
        context.setResponseBody(body);

        assertTrue(context.resolveResponseTree(body).isMissingNode());
        assertTrue(context.resolveResponseTree(null).isMissingNode());
    }

    @Test
    public void testAssembledChatCompletionsResponseSharesTheTreeTracingMerged() {
        ProxyContext context = context(null);
        context.setResponseBody(Buffer.buffer("data: {\"id\":\"chat-1\",\"choices\":[]}\n\ndata: [DONE]\n\n"));

        ObjectNode tree = context.assembledChatCompletionsResponseTree();

        assertEquals("chat-1", tree.path("id").asText());
        assertSame(tree, context.assembledChatCompletionsResponseTree());
        assertEquals(ProxyUtil.convertToString(tree), context.assembledChatCompletionsResponse());
    }

    @Test
    public void testAssembledChatCompletionsResponseWithoutTracing() {
        ProxyContext context = context(null);
        context.setResponseBody(Buffer.buffer("data: {\"id\":\"chat-1\",\"choices\":[]}\n\ndata: [DONE]\n\n"));

        assertTrue(context.assembledChatCompletionsResponse().contains("\"chat-1\""));
    }

    private static ProxyContext context(ExtractedClaims claims) {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(new Key());
        HttpServerRequest request = mock(HttpServerRequest.class, RETURNS_DEEP_STUBS);
        return new ProxyContext(null, request, apiKeyData, claims, "trace-id", "span-id", "01");
    }
}