package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.function.EncryptedContentWrapFn;
import com.epam.aidial.core.translator.ResponsesToMessagesStream;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResponsesToMessagesReadStreamTest {

    @SuppressWarnings("unchecked")
    private final ReadStream<Buffer> upstream = mock(ReadStream.class);
    private final EncryptedContentWrapFn wrapFn = mock(EncryptedContentWrapFn.class);
    private final List<String> received = new ArrayList<>();
    private final AtomicBoolean ended = new AtomicBoolean();
    private ResponsesToMessagesReadStream stream;
    private Handler<Buffer> upstreamChunks;
    private Handler<Void> upstreamEnd;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(wrapFn.apply(any())).thenAnswer(invocation -> Future.succeededFuture(invocation.getArgument(0, JsonNode.class)));
        stream = new ResponsesToMessagesReadStream(upstream, new ResponsesToMessagesStream("model", "msg_dial"), wrapFn);
        ArgumentCaptor<Handler<Buffer>> chunks = ArgumentCaptor.forClass(Handler.class);
        ArgumentCaptor<Handler<Void>> end = ArgumentCaptor.forClass(Handler.class);
        verify(upstream).handler(chunks.capture());
        verify(upstream).endHandler(end.capture());
        upstreamChunks = chunks.getValue();
        upstreamEnd = end.getValue();
        stream.handler(chunk -> received.add(chunk.toString()));
        stream.endHandler(ignored -> ended.set(true));
    }

    @Test
    void testEventsSplitAcrossChunksAreTranslatedInOrder() {
        upstreamChunks.handle(Buffer.buffer("data: {\"type\":\"response.output_text.delta\",\"delta\":\"a\"}\n"));
        upstreamChunks.handle(Buffer.buffer("\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n"));
        upstreamEnd.handle(null);

        String all = String.join("", received);
        assertTrue(all.startsWith("event: message_start"), all);
        assertTrue(all.contains("\"text\":\"a\""), all);
        assertTrue(all.endsWith("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"), all);
        assertTrue(ended.get());
    }

    @Test
    void testMalformedEventIsSkippedAndTheUsageStillArrives() {
        upstreamChunks.handle(Buffer.buffer("data: {not json\n\n"));
        upstreamChunks.handle(Buffer.buffer("data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"output_tokens\":4}}}\n\n"));
        upstreamEnd.handle(null);

        String all = String.join("", received);
        assertTrue(all.contains("\"output_tokens\":4"), all);
        assertTrue(all.endsWith("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"), all);
        assertTrue(ended.get());
    }

    @Test
    void testUpstreamEndingEarlyEndsTheStreamInError() {
        upstreamChunks.handle(Buffer.buffer("data: {\"type\":\"response.output_text.delta\",\"delta\":\"a\"}\n\n"));
        upstreamEnd.handle(null);

        assertTrue(received.get(received.size() - 1).startsWith("event: error"), received.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlowControlAndFailuresReachTheUpstream() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        stream.exceptionHandler(failure::set);
        ArgumentCaptor<Handler<Throwable>> exceptions = ArgumentCaptor.forClass(Handler.class);
        verify(upstream).exceptionHandler(exceptions.capture());
        RuntimeException reset = new RuntimeException("reset");
        exceptions.getValue().handle(reset);

        stream.pause();
        stream.fetch(3);
        stream.resume();

        assertSame(reset, failure.get());
        verify(upstream).pause();
        verify(upstream).fetch(3);
        verify(upstream).resume();
    }
}
