package com.epam.aidial.core.util;

import com.epam.aidial.core.function.BaseResponseFunction;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.buffer.impl.VertxByteBufAllocator;
import jodd.io.CharBufferReader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class EventStreamParser {

    private static final byte[] EVENT_TOKEN = "data: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DONE_TOKEN = "[DONE]".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final BaseResponseFunction DEFAULT_HANDLER = new BaseResponseFunction(null, null) {
        @Override
        public Future<Void> apply(ObjectNode jsonNodes) {
            return Future.succeededFuture();
        }
    };

    private final ByteBuf buffer;

    private int eventIndex;

    private int chunkIndex;

    private boolean firstChunk = true;

    private boolean lastChunk = false;

    private final BaseResponseFunction handler;

    private Stages stage;

    private List<Future<Void>> futures;

    private enum Stages {
        EVENT, DATA, EOL;
    }


    public EventStreamParser(int initialSizeHint, BaseResponseFunction handler) {
        this.handler = handler == null ? DEFAULT_HANDLER : handler;
        buffer = VertxByteBufAllocator.DEFAULT.heapBuffer(initialSizeHint, Integer.MAX_VALUE);
    }

    public synchronized Future<Boolean> parse(Buffer chunk) {
        if (lastChunk) {
            return Future.succeededFuture(true);
        }
        chunkIndex = 0;
        futures = null;
        if (firstChunk) {
            chunkIndex += skipBom(chunk);
            firstChunk = false;
            stage = Stages.EVENT;
        }

        try {
            while (chunkIndex < chunk.length()) {
                switch (stage) {
                    case EVENT -> handleEventStage(chunk);
                    case DATA -> handleDataStage(chunk);
                    case EOL -> handleEndOfLineStage(chunk);
                    default -> throw new IllegalStateException("unknown stage + " + stage);
                }
                if (lastChunk) {
                    break;
                }
            }
        } catch (Throwable e) {
            log.error("Error occurred at parsing chunk", e);
            return Future.failedFuture(e);
        }

        if (futures == null) {
            return Future.succeededFuture(lastChunk);
        }
        return Future.join(futures).transform(ignore -> Future.succeededFuture(lastChunk));
    }

    private void handleEventStage(Buffer chunk) {
        for (; eventIndex < EVENT_TOKEN.length && chunkIndex < chunk.length(); chunkIndex++, eventIndex++) {
            if (EVENT_TOKEN[eventIndex] != chunk.getByte(chunkIndex)) {
                throw new IllegalArgumentException("Bad event");
            }
        }

        if (eventIndex == EVENT_TOKEN.length) {
            eventIndex = 0;
            stage = Stages.DATA;
        }
    }

    private void handleDataStage(Buffer chunk) throws IOException {
        boolean eol = accumulateBuffer(chunk);
        if (eol) {
            boolean done = isLastMessage();
            if (done) {
                lastChunk = true;
                return;
            }
            if (futures == null) {
                futures = new ArrayList<>();
            }
            try (CharBufferReader reader = toCharBufferReader()) {
                ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(reader);
                Future<Void> future = handler.apply(tree);
                futures.add(future);
            } catch (Throwable e) {
                log.error("Error occurred at handling json data from chunk", e);
            } finally {
                buffer.clear();
                stage = Stages.EOL;
            }
        }
    }

    private CharBufferReader toCharBufferReader() {
        return new CharBufferReader(StandardCharsets.UTF_8.decode(buffer.nioBuffer()));
    }

    private boolean accumulateBuffer(Buffer chunk) {
        for (; chunkIndex < chunk.length(); chunkIndex++) {
            byte b = chunk.getByte(chunkIndex);
            if (b == '\n' || b == '\r') {
                return true;
            }
            buffer.writeByte(b);
        }
        return false;
    }

    private boolean isLastMessage() {
        if (buffer.readableBytes() == DONE_TOKEN.length) {
            int j = 0;
            for (; j < DONE_TOKEN.length; j++) {
                if (buffer.getByte(j) != DONE_TOKEN[j]) {
                    break;
                }
            }
            return j == DONE_TOKEN.length;
        }
        return false;
    }

    private void handleEndOfLineStage(Buffer chunk) {
        for (; chunkIndex < chunk.length(); chunkIndex++) {
            byte b = chunk.getByte(chunkIndex);
            if (b == '\n' || b == '\r') {
                continue;
            }
            stage = Stages.EVENT;
            break;
        }
    }

    private int skipBom(Buffer chunk) {
        if (chunk.length() < BOM.length) {
            return 0;
        }
        for (int i = 0; i < BOM.length; i++) {
            if (chunk.getByte(i) != BOM[i]) {
                return 0;
            }
        }
        return BOM.length;
    }
}
