package com.epam.aidial.core.server.vertx.stream;

import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.ResourceUpload;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

/**
 * Implementation of vertx {@link io.vertx.core.streams.WriteStream} that handles data chunks (from {@link io.vertx.core.streams.ReadStream}) and writes them to the blob storage.
 * If file content is bigger than 5MB - multipart upload will be used.
 * Chunk size can be configured via {@link #setWriteQueueMaxSize(int)} method, but should be no less than 5 MB according to the s3 specification.
 * If any exception is caught in between - multipart upload will be aborted.
 */
@Slf4j
public class BlobWriteStream implements WriteStream<Buffer> {

    public static final int MIN_PART_SIZE_BYTES = 5 * 1024 * 1024;

    private final AsyncTaskExecutor taskExecutor;
    private final ResourceService resourceService;
    private final ResourceDescriptor resource;
    private final EtagHeader etag;
    private final String contentType;

    private final Buffer chunkBuffer = Buffer.buffer();
    private int chunkSize = MIN_PART_SIZE_BYTES;
    private int position;
    private ResourceUpload resourceUpload;
    @Getter
    private FileMetadata metadata;

    private Throwable exception;

    private Handler<Throwable> errorHandler;

    private boolean isBufferFull;

    private final String author;

    public BlobWriteStream(AsyncTaskExecutor taskExecutor,
                           ResourceService resourceService,
                           ResourceDescriptor resource,
                           EtagHeader etag,
                           String contentType) {
        this(taskExecutor, resourceService, resource, etag, contentType, null);
    }

    public BlobWriteStream(AsyncTaskExecutor taskExecutor,
                           ResourceService resourceService,
                           ResourceDescriptor resource,
                           EtagHeader etag,
                           String contentType,
                           String author) {
        this.taskExecutor = taskExecutor;
        this.resourceService = resourceService;
        this.resource = resource;
        this.etag = etag;
        this.contentType = contentType != null ? contentType : BlobStorageUtil.getContentType(resource.getName());
        this.author = author;
    }

    @Override
    public synchronized WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        this.errorHandler = handler;
        return this;
    }

    @Override
    public synchronized Future<Void> write(Buffer data) {
        Promise<Void> promise = Promise.promise();
        write(data, promise);
        return promise.future();
    }

    @Override
    public synchronized void write(Buffer data, Handler<AsyncResult<Void>> handler) {
        // exception might be thrown during drain handling, if so we need to stop processing chunks
        // upload abortion will be handled in the end
        if (exception != null) {
            handler.handle(Future.failedFuture(exception));
            return;
        }

        int length = data.length();
        chunkBuffer.setBuffer(position, data);
        position += length;
        if (position > chunkSize) {
            isBufferFull = true;
        }

        handler.handle(Future.succeededFuture());
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {
        Future<Void> result = taskExecutor.submit(() -> {
            synchronized (BlobWriteStream.this) {
                if (exception != null) {
                    throw new RuntimeException(exception);
                }

                ByteBuf lastChunk = chunkBuffer.slice(0, position).getByteBuf();
                if (resourceUpload == null) {
                    log.info("Resource is too small for multipart upload, sending as a regular blob");
                    try (InputStream chunkStream = new ByteBufInputStream(lastChunk)) {
                        metadata = resourceService.putFile(resource, chunkStream.readAllBytes(), etag, contentType, author);
                    }
                } else {
                    if (position != 0) {
                        resourceUpload.addChunk(lastChunk);
                    }

                    metadata = resourceService.finishFileUpload(resource, resourceUpload, etag);
                    log.info("Multipart upload committed, bytes handled {}", resourceUpload.getContentLength());
                }

                return null;
            }
        });
        if (handler != null) {
            result.onComplete(handler);
        }
    }

    @Override
    public synchronized WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        assert maxSize > MIN_PART_SIZE_BYTES;
        chunkSize = maxSize;
        return this;
    }

    @Override
    public synchronized boolean writeQueueFull() {
        return isBufferFull;
    }

    @Override
    public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        taskExecutor.submit(() -> {
            synchronized (BlobWriteStream.this) {
                try {
                    if (resourceUpload == null) {
                        resourceUpload = resourceService.initFileUpload(resource, contentType, etag, author);
                    }

                    ByteBuf chunk = chunkBuffer.slice(0, position).getByteBuf();
                    resourceUpload.addChunk(chunk);
                    position = 0;
                    isBufferFull = false;
                } catch (Throwable ex) {
                    exception = ex;
                } finally {
                    if (handler != null) {
                        handler.handle(null);
                    }
                }
            }
            return null;
        });

        return this;
    }

    public synchronized void abortUpload(Throwable ex) {
        if (resourceUpload != null) {
            resourceUpload.abort();
        }

        if (errorHandler != null) {
            errorHandler.handle(ex);
        }

        log.warn("Multipart upload aborted", ex);
    }
}
