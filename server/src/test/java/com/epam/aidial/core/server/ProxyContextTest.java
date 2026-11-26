package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.proxy.embedded.EmbeddedServerRequest;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ProxyContextTest {

    @Test
    public void testOperationDuration() {
        ProxyContext context = context(null);
        context.setRequestTimestamp(1000L);
        context.setResponseBodyTimestamp(2500L);

        assertEquals(1500, context.calculateOperationDurationMs());
    }

    @Test
    public void testRequestBodyTimestampIsTheFirstRead() throws Exception {
        ProxyContext context = context(null);
        context.setRequestBodyWithTimestamp(Buffer.buffer("a"));
        long first = context.getRequestBodyTimestamp();
        Thread.sleep(5);
        // a fallback re-parses the saved bytes: not a second read
        context.setRequestBodyWithTimestamp(Buffer.buffer("a"));

        assertEquals(first, context.getRequestBodyTimestamp());
        assertEquals("a", context.getRequestBody().toString());
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

        assertEquals(claims, context(new ExtractedClaims("sub", List.of("role"), "hash", claims, null, "Jane Doe", null))
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
    public void testCallTreeUntilTheBodyIsSet() {
        ProxyContext context = context(null, mock(EmbeddedServerRequest.class, RETURNS_DEEP_STUBS));
        assertNull(context.getCallTree());

        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode().put("model", "m");
        context.setCallTree(tree);
        assertSame(tree, context.getCallTree());
        assertTrue(context.isChildPass());

        context.setRequestBody(Buffer.buffer("{}"));
        assertNull(context.getCallTree());
        assertTrue(context.isChildPass());
    }

    @Test
    public void testParseRequestBodyTakesTheCallTree() throws Exception {
        ProxyContext context = context(null);
        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode().put("model", "m");
        context.setCallTree(tree);

        assertSame(tree, ProxyUtil.parseRequestBody(context, Buffer.buffer()));
        assertNull(context.getRequestBody());
        assertTrue(context.getRequestBodyTimestamp() > 0);
    }

    @Test
    public void testParseRequestBodyParsesWithoutCallTree() throws Exception {
        ProxyContext context = context(null);
        Buffer body = Buffer.buffer("{\"model\":\"m\"}");

        assertEquals("m", ProxyUtil.parseRequestBody(context, body).path("model").asText());
        assertSame(body, context.getRequestBody());
        assertFalse(context.isChildPass());
    }

    @Test
    public void testChildPassWithoutCallTree() {
        // a GET of the user info through a plugin's host carries no body
        ProxyContext context = context(null, mock(EmbeddedServerRequest.class, RETURNS_DEEP_STUBS));

        assertTrue(context.isChildPass());
        assertNull(context.getCallTree());
    }

    @Test
    public void testMasterApiKeyDataIsReadOncePerRequest() {
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        Proxy proxy = mock(Proxy.class);
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        ApiKeyData onMaster = new ApiKeyData();
        when(apiKeyStore.getPerRequestKeyDataOnMaster("per-request-key")).thenReturn(onMaster);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(new Key());
        apiKeyData.setPerRequestKey("per-request-key");
        ProxyContext context = new ProxyContext(proxy, mock(HttpServerRequest.class, RETURNS_DEEP_STUBS), apiKeyData, null, "trace-id", "span-id", "01");

        assertSame(onMaster, context.loadMasterApiKeyData());
        assertSame(onMaster, context.loadMasterApiKeyData());
        // a context with other key data answers from that data alone
        assertNull(context.copyWith(apiKeyData).loadMasterApiKeyData());

        verify(apiKeyStore).getPerRequestKeyDataOnMaster("per-request-key");
    }

    @Test
    public void testMasterApiKeyDataIsNotReadInProcessOrOnTheEventLoop() throws Exception {
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        Proxy proxy = mock(Proxy.class);
        when(proxy.getApiKeyStore()).thenReturn(apiKeyStore);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(new Key());
        apiKeyData.setPerRequestKey("per-request-key");
        ProxyContext childPass = new ProxyContext(proxy, mock(EmbeddedServerRequest.class, RETURNS_DEEP_STUBS), apiKeyData, null, "trace-id", "span-id", "01");
        assertNull(childPass.loadMasterApiKeyData());

        ProxyContext context = new ProxyContext(proxy, mock(HttpServerRequest.class, RETURNS_DEEP_STUBS), apiKeyData, null, "trace-id", "span-id", "01");
        Vertx vertx = Vertx.vertx();
        try {
            CompletableFuture<ApiKeyData> onEventLoop = new CompletableFuture<>();
            vertx.runOnContext(ignore -> onEventLoop.complete(context.loadMasterApiKeyData()));
            assertNull(onEventLoop.get(10, TimeUnit.SECONDS));
        } finally {
            vertx.close();
        }

        verifyNoInteractions(apiKeyStore);
    }

    @Test
    public void testMasterApiKeyDataIsNotReadForAnotherKindOfKey() {
        Proxy proxy = mock(Proxy.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(new Key());
        ProxyContext context = new ProxyContext(proxy, mock(HttpServerRequest.class, RETURNS_DEEP_STUBS), apiKeyData, null, "trace-id", "span-id", "01");

        assertNull(context.loadMasterApiKeyData());

        verifyNoInteractions(proxy);
    }

    private static ProxyContext context(ExtractedClaims claims) {
        return context(claims, mock(HttpServerRequest.class, RETURNS_DEEP_STUBS));
    }

    private static ProxyContext context(ExtractedClaims claims, HttpServerRequest request) {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(new Key());
        return new ProxyContext(null, request, apiKeyData, claims, "trace-id", "span-id", "01");
    }
}