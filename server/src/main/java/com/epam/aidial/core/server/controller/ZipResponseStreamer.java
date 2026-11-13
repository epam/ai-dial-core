package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.ExportLimits;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.server.vertx.stream.BoundedPipe;
import com.epam.aidial.core.server.vertx.stream.InputStreamReader;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.Pipe;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Streams a ZIP that a writer builds on a task thread into a {@link BoundedPipe} and on to the response, so a slow client slows the
 * writer down instead of growing memory, and a client too slow for the stall timeout fails the export.
 *
 * <p>Once the {@code 200} head is sent the status cannot change: a later failure resets the connection, so the client never gets a
 * complete ZIP. A failure before the head is handed to the caller, which answers with a status.
 */
final class ZipResponseStreamer {

    private static final int PIPE_CAPACITY = 1024 * 1024;
    private static final DateTimeFormatter FILE_NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Vertx vertx;
    private final AsyncTaskExecutor taskExecutor;
    private final Duration stallTimeout;

    ZipResponseStreamer(Vertx vertx, AsyncTaskExecutor taskExecutor, Duration stallTimeout) {
        this.vertx = vertx;
        this.taskExecutor = taskExecutor;
        this.stallTimeout = stallTimeout;
    }

    /**
     * {@code writer} does not close the stream; {@code locks} are released when it ends or cannot start. The pipe is aborted when the
     * {@code deadline} passes.
     *
     * @param fileNamePrefix   the file name is {@code <prefix>-<start>.zip}
     * @param failureResponder answers a failure before the head is written; the locks are released by then
     */
    void stream(ProxyContext context, String fileNamePrefix, Instant start, @Nullable ExportLimits.Deadline deadline, ExportSlot locks,
                Consumer<OutputStream> writer, Consumer<Throwable> failureResponder) {
        HttpServerResponse response = context.getResponse().setChunked(true);
        // set only when the archive starts, so that a status response sent before it does not carry them
        response.headersEndHandler(ignored -> {
            if (response.getStatusCode() == HttpStatus.OK.getCode()) {
                response.putHeader(HttpHeaders.CONTENT_TYPE, "application/zip");
                response.putHeader(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileNamePrefix + "-" + FILE_NAME_TIMESTAMP.format(start) + ".zip\"");
            }
        });

        BoundedPipe pipe = new BoundedPipe(PIPE_CAPACITY, stallTimeout);
        InputStreamReader reader = new InputStreamReader(vertx, taskExecutor, pipe.getInput());
        Pipe<Buffer> responsePipe = reader.pipe().endOnFailure(false);
        responsePipe.to(response).onFailure(error -> {
            reader.close();
            if (response.headWritten()) {
                response.reset();
            }
        });

        long abortTimer = deadline == null ? -1
                : vertx.setTimer(deadline.computeRemainingMillis(), id -> pipe.closeExceptionally(new IOException("The export took too long")));
        Future<Object> writing;
        try {
            writing = taskExecutor.submit(() -> {
                // not in try-with-resources: closing the stream on a failure would end the response cleanly
                try {
                    writer.accept(pipe.getOutput());
                } catch (Throwable e) {
                    pipe.closeExceptionally(e);
                    throw e;
                } finally {
                    cancelTimer(abortTimer);
                    locks.release();
                }
                pipe.getOutput().close();
                return null;
            });
        } catch (RuntimeException e) {
            // the executor refused the task before it ran
            cancelTimer(abortTimer);
            responsePipe.close();
            reader.close();
            locks.releaseThen(taskExecutor, () -> failureResponder.accept(e));
            return;
        }
        // a writer that ran has released the locks already; one that was dropped before it ran has not
        writing.onFailure(error -> locks.releaseThen(taskExecutor, () -> {
            cancelTimer(abortTimer);
            // the writer has already logged the failure; stop feeding the response before it is ended
            responsePipe.close();
            if (response.headWritten()) {
                response.reset();
            } else {
                failureResponder.accept(error);
            }
            reader.close();
        }));
    }

    private void cancelTimer(long timer) {
        if (timer >= 0) {
            vertx.cancelTimer(timer);
        }
    }
}
