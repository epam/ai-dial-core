package com.epam.aidial.core.server.proxy.embedded;

import com.epam.aidial.core.plugin.Response;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.impl.InboundBuffer;

import java.util.Set;

import static com.epam.aidial.core.server.proxy.embedded.EmbeddedServerRequest.unsupported;

/**
 * The response of a pass Core serves in process for an origin plugin's call. What the pass writes becomes the call's
 * answer: its status and headers on the first write or the end, then its body through an {@link InboundBuffer} on the
 * plugin's context, which the plugin reads as any Vert.x stream.
 *
 * <p>Threads. The status, the headers and the response's state change where the pass calls, as on Vert.x's own
 * response, so a pass that writes from a virtual thread reads back what it set; the log layout reads them from other
 * threads. The hand-over to the plugin runs on the plugin's context, from the event loop both contexts share, and the
 * write acks and the drain handler run on the child's context, where its pipe runs.
 *
 * <p>{@link #reset} cuts the answer, as a dropped connection does: the call fails before the head, the body after it.
 * The plugin's {@link Response#cancel} closes the response, as a client going away does: every later write fails
 * its ack and a pending drain handler fires, so a pipe into the response fails on its next write.
 */
public final class EmbeddedServerResponse implements HttpServerResponse {

    // InboundBuffer counts chunks: a pipe into the response pauses once 16 wait for the plugin
    private static final long HIGH_WATER_MARK = 16;

    private final ContextInternal childContext;
    private final ContextInternal parentContext;
    private final Promise<Response> head;
    private final InboundBuffer<Object> body;
    private final AnswerBody reader = new AnswerBody();
    private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    private volatile int statusCode = HttpResponseStatus.OK.code();
    private volatile String statusMessage;
    private volatile boolean chunked;
    private volatile boolean headWritten;
    private volatile boolean ended;
    private volatile boolean closed;
    private volatile boolean full;
    private volatile long bytesWritten;
    private Handler<Void> drainHandler;
    private Handler<Void> closeHandler;
    private Handler<Void> endHandler;
    private Handler<Throwable> exceptionHandler;

    /**
     * @param childContext  the context the child pass runs on
     * @param parentContext the context of the plugin that made the call, on the same event loop
     */
    public EmbeddedServerResponse(ContextInternal childContext, ContextInternal parentContext) {
        this.childContext = childContext;
        this.parentContext = parentContext;
        this.head = parentContext.promise();
        this.body = InboundBuffer.createPaused(parentContext, HIGH_WATER_MARK, ignored -> onDrain(), reader::handle);
    }

    /**
     * The call's answer. It completes on the plugin's context once the pass writes its head, and fails when the pass
     * resets the response before.
     */
    public Future<Response> answer() {
        return head.future();
    }

    @Override
    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public HttpServerResponse setStatusCode(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    @Override
    public String getStatusMessage() {
        String message = statusMessage;
        return message == null ? HttpResponseStatus.valueOf(statusCode).reasonPhrase() : message;
    }

    @Override
    public HttpServerResponse setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
        return this;
    }

    @Override
    public HttpServerResponse setChunked(boolean chunked) {
        this.chunked = chunked;
        return this;
    }

    @Override
    public boolean isChunked() {
        return chunked;
    }

    @Override
    public MultiMap headers() {
        return headers;
    }

    @Override
    public HttpServerResponse putHeader(String name, String value) {
        headers.set(name, value);
        return this;
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, CharSequence value) {
        headers.set(name, value);
        return this;
    }

    @Override
    public HttpServerResponse putHeader(String name, Iterable<String> values) {
        headers.set(name, values);
        return this;
    }

    @Override
    public HttpServerResponse putHeader(CharSequence name, Iterable<CharSequence> values) {
        headers.set(name, values);
        return this;
    }

    @Override
    public Future<Void> write(Buffer data) {
        return send(data, false);
    }

    @Override
    public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
        onComplete(write(data), handler);
    }

    @Override
    public Future<Void> write(String chunk, String enc) {
        return write(Buffer.buffer(chunk, enc));
    }

    @Override
    public void write(String chunk, String enc, Handler<AsyncResult<Void>> handler) {
        onComplete(write(chunk, enc), handler);
    }

    @Override
    public Future<Void> write(String chunk) {
        return write(Buffer.buffer(chunk));
    }

    @Override
    public void write(String chunk, Handler<AsyncResult<Void>> handler) {
        onComplete(write(chunk), handler);
    }

    @Override
    public Future<Void> end(String chunk) {
        return end(Buffer.buffer(chunk));
    }

    @Override
    public void end(String chunk, Handler<AsyncResult<Void>> handler) {
        onComplete(end(chunk), handler);
    }

    @Override
    public Future<Void> end(String chunk, String enc) {
        return end(Buffer.buffer(chunk, enc));
    }

    @Override
    public void end(String chunk, String enc, Handler<AsyncResult<Void>> handler) {
        onComplete(end(chunk, enc), handler);
    }

    @Override
    public Future<Void> end(Buffer chunk) {
        return send(chunk, true);
    }

    @Override
    public void end(Buffer chunk, Handler<AsyncResult<Void>> handler) {
        onComplete(end(chunk), handler);
    }

    @Override
    public Future<Void> end() {
        return end(Buffer.buffer());
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {
        onComplete(end(), handler);
    }

    @Override
    public boolean writeQueueFull() {
        return full;
    }

    @Override
    public HttpServerResponse setWriteQueueMaxSize(int maxSize) {
        return this;
    }

    @Override
    public HttpServerResponse drainHandler(Handler<Void> handler) {
        this.drainHandler = handler;
        return this;
    }

    @Override
    public HttpServerResponse exceptionHandler(Handler<Throwable> handler) {
        this.exceptionHandler = handler;
        return this;
    }

    @Override
    public HttpServerResponse closeHandler(Handler<Void> handler) {
        this.closeHandler = handler;
        return this;
    }

    @Override
    public HttpServerResponse endHandler(Handler<Void> handler) {
        this.endHandler = handler;
        return this;
    }

    @Override
    public boolean ended() {
        return ended;
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public boolean headWritten() {
        return headWritten;
    }

    @Override
    public long bytesWritten() {
        return bytesWritten;
    }

    /**
     * Cuts the answer, as an HTTP response resets its connection. Returns false when the answer is complete or the
     * response is already closed.
     */
    @Override
    public boolean reset(long code) {
        if (ended || closed) {
            return false;
        }
        closed = true;
        HttpClosedException error = new HttpClosedException("The pass serving the call reset its response");
        if (!headWritten) {
            headWritten = true;
            parentContext.runOnContext(ignored -> head.tryFail(error));
        } else {
            parentContext.runOnContext(ignored -> {
                body.clear();
                reader.fail(error);
            });
        }
        return true;
    }

    @Override
    public void close() {
        reset(0);
    }

    /**
     * The plugin stopped reading the answer, or its pass ended: the response closes, as when a client goes away.
     */
    public void cancel() {
        childContext.emit(null, ignored -> {
            if (closed) {
                return;
            }
            closed = true;
            body.clear();
            HttpClosedException error = new HttpClosedException("The origin plugin cancelled the call");
            // the plugin may still wait for the head, or hold an answer it has not read to the end
            parentContext.runOnContext(ignore -> {
                head.tryFail(error);
                reader.fail(error);
            });
            Handler<Void> drain = drainHandler;
            drainHandler = null;
            if (drain != null) {
                drain.handle(null);
            }
            if (exceptionHandler != null) {
                exceptionHandler.handle(error);
            }
            if (closeHandler != null) {
                closeHandler.handle(null);
            }
        });
    }

    private Future<Void> send(Buffer data, boolean end) {
        Promise<Void> ack = childContext.promise();
        if (closed) {
            ack.fail(new HttpClosedException("The call's answer was cancelled"));
            return ack.future();
        }
        if (ended) {
            ack.fail(new IllegalStateException("Response has already been written"));
            return ack.future();
        }
        ended = end;
        freezeHead();
        bytesWritten += data.length();
        parentContext.emit(data, chunk -> {
            if (closed) {
                ack.tryFail(new HttpClosedException("The call's answer was cancelled"));
                return;
            }
            boolean writable = chunk.length() == 0 || body.write(chunk);
            if (end) {
                writable = body.write(InboundBuffer.END_SENTINEL);
            }
            full = !writable;
            ack.tryComplete();
        });
        if (end && endHandler != null) {
            childContext.emit(null, endHandler);
        }
        return ack.future();
    }

    private void freezeHead() {
        if (headWritten) {
            return;
        }
        headWritten = true;
        Answer answer = new Answer(statusCode, MultiMap.caseInsensitiveMultiMap().addAll(headers), this);
        // from a task of its own: the plugin's code runs outside the pass's write
        parentContext.runOnContext(ignored -> head.tryComplete(answer));
    }

    private void onDrain() {
        full = false;
        childContext.emit(null, ignored -> {
            Handler<Void> drain = drainHandler;
            if (drain != null && !closed) {
                drain.handle(null);
            }
        });
    }

    private static void onComplete(Future<Void> future, Handler<AsyncResult<Void>> handler) {
        if (handler != null) {
            future.onComplete(handler);
        }
    }

    @Override
    public HttpServerResponse writeContinue() {
        throw unsupported("writeContinue");
    }

    @Override
    public Future<Void> writeEarlyHints(MultiMap headers) {
        throw unsupported("writeEarlyHints");
    }

    @Override
    public void writeEarlyHints(MultiMap headers, Handler<AsyncResult<Void>> handler) {
        throw unsupported("writeEarlyHints");
    }

    @Override
    public Future<Void> writeHead() {
        if (closed) {
            return Future.failedFuture(new HttpClosedException("The call's answer was cancelled"));
        }
        freezeHead();
        return Future.succeededFuture();
    }

    @Override
    public MultiMap trailers() {
        throw unsupported("trailers");
    }

    @Override
    public HttpServerResponse putTrailer(String name, String value) {
        throw unsupported("putTrailer");
    }

    @Override
    public HttpServerResponse putTrailer(CharSequence name, CharSequence value) {
        throw unsupported("putTrailer");
    }

    @Override
    public HttpServerResponse putTrailer(String name, Iterable<String> values) {
        throw unsupported("putTrailer");
    }

    @Override
    public HttpServerResponse putTrailer(CharSequence name, Iterable<CharSequence> value) {
        throw unsupported("putTrailer");
    }

    @Override
    public Future<Void> sendFile(String filename, long offset, long length) {
        throw unsupported("sendFile");
    }

    @Override
    public HttpServerResponse sendFile(String filename, long offset, long length, Handler<AsyncResult<Void>> resultHandler) {
        throw unsupported("sendFile");
    }

    @Override
    public HttpServerResponse headersEndHandler(Handler<Void> handler) {
        throw unsupported("headersEndHandler");
    }

    @Override
    public HttpServerResponse bodyEndHandler(Handler<Void> handler) {
        throw unsupported("bodyEndHandler");
    }

    @Override
    public int streamId() {
        return -1;
    }

    @Override
    public Future<HttpServerResponse> push(HttpMethod method, String path, String host, MultiMap headers) {
        throw unsupported("push");
    }

    @Override
    public Future<HttpServerResponse> push(HttpMethod method, HostAndPort authority, String path, MultiMap headers) {
        throw unsupported("push");
    }

    @Override
    public HttpServerResponse writeCustomFrame(int type, int flags, Buffer payload) {
        throw unsupported("writeCustomFrame");
    }

    @Override
    public HttpServerResponse addCookie(Cookie cookie) {
        throw unsupported("addCookie");
    }

    @Override
    public Cookie removeCookie(String name, boolean invalidate) {
        throw unsupported("removeCookie");
    }

    @Override
    public Cookie removeCookie(String name, String domain, String path, boolean invalidate) {
        throw unsupported("removeCookie");
    }

    @Override
    public Set<Cookie> removeCookies(String name, boolean invalidate) {
        throw unsupported("removeCookies");
    }

    private record Answer(int statusCode, MultiMap headers, EmbeddedServerResponse response) implements Response {

        @Override
        public ReadStream<Buffer> body() {
            return response.reader;
        }

        @Override
        public void cancel() {
            response.cancel();
        }
    }

    /**
     * The answer's body as the plugin reads it, on the plugin's context. It starts paused, and flows once the plugin
     * sets a handler, unless the plugin paused it first.
     */
    private final class AnswerBody implements ReadStream<Buffer> {

        private Handler<Buffer> handler;
        private Handler<Void> endHandler;
        private Handler<Throwable> exceptionHandler;
        private boolean paused;
        private boolean ended;
        private Throwable failure;

        void handle(Object item) {
            if (failure != null) {
                return;
            }
            if (item == InboundBuffer.END_SENTINEL) {
                ended = true;
                if (endHandler != null) {
                    endHandler.handle(null);
                }
            } else if (handler != null) {
                handler.handle((Buffer) item);
            }
        }

        void fail(Throwable error) {
            if (failure != null || ended) {
                // a body read to its end has nothing left to cut
                return;
            }
            failure = error;
            if (exceptionHandler != null) {
                exceptionHandler.handle(error);
            }
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            this.exceptionHandler = handler;
            Throwable error = failure;
            if (handler != null && error != null) {
                // a body cut before the plugin listened still reports it, from a task of its own
                parentContext.runOnContext(ignored -> handler.handle(error));
            }
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.handler = handler;
            if (handler != null && !paused) {
                body.resume();
            }
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            paused = true;
            body.pause();
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            paused = false;
            body.resume();
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            body.fetch(amount);
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }
    }
}
