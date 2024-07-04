package com.epam.aidial.core.storage;

import com.epam.aidial.core.data.FileMetadata;
import com.epam.aidial.core.data.ResourceItemMetadata;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.util.EtagBuilder;
import com.epam.aidial.core.util.ResourceUtil;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of vertx {@link io.vertx.core.streams.WriteStream} that handles data chunks (from {@link io.vertx.core.streams.ReadStream}) and writes them to the blob storage.
 * If file content is bigger than 5MB - multipart upload will be used.
 * Chunk size can be configured via {@link #setWriteQueueMaxSize(int)} method, but should be no less than 5 MB according to the s3 specification.
 * If any exception is caught in between - multipart upload will be aborted.
 */
@Slf4j
public class BlobWriteStream implements WriteStream<Buffer> {

    private static final int MIN_PART_SIZE_BYTES = 5 * 1024 * 1024;

    private final Vertx vertx;
    private final BlobStorage storage;
    private final LockService.Lock lock;
    private final ResourceDescription resource;
    private final String contentType;
    private final EtagBuilder etagBuilder = new EtagBuilder();

    private final Buffer chunkBuffer = Buffer.buffer();
    private int chunkSize = MIN_PART_SIZE_BYTES;
    private int position;
    private MultipartUpload mpu;
    private int chunkNumber = 0;
    @Getter
    private ResourceItemMetadata metadata;

    private Throwable exception;

    private Handler<Throwable> errorHandler;

    private final List<MultipartPart> parts = new ArrayList<>();

    private boolean isBufferFull;

    private long bytesHandled;

    public BlobWriteStream(Vertx vertx,
                           BlobStorage storage,
                           LockService.Lock lock,
                           ResourceDescription resource,
                           String contentType) {
        this.vertx = vertx;
        this.storage = storage;
        this.lock = lock;
        this.resource = resource;
        this.contentType = contentType != null ? contentType : BlobStorageUtil.getContentType(resource.getName());
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
        bytesHandled += length;
        if (position > chunkSize) {
            isBufferFull = true;
        }

        handler.handle(Future.succeededFuture());
    }

    @Override
    public void end(Handler<AsyncResult<Void>> handler) {
        Future<Void> result = vertx.executeBlocking(() -> {
            synchronized (BlobWriteStream.this) {
                if (exception != null) {
                    if (mpu != null) {
                        storage.abortMultipartUpload(mpu);
                        log.warn("Multipart upload aborted", exception);
                    }
                    throw new RuntimeException(exception);
                }
                lock.extend();

                Buffer lastChunk = chunkBuffer.slice(0, position);
                String etag = etagBuilder.append(lastChunk.getBytes()).build();
                metadata = new FileMetadata(resource, bytesHandled, contentType)
                        .setEtag(etag);
                if (mpu == null) {
                    log.info("Resource is too small for multipart upload, sending as a regular blob");
                    storage.store(resource.getAbsoluteFilePath(), contentType, lastChunk, etag);
                } else {
                    if (position != 0) {
                        MultipartPart part = storage.storeMultipartPart(mpu, ++chunkNumber, lastChunk);
                        parts.add(part);
                    }
                    mpu.blobMetadata().getUserMetadata().put(ResourceUtil.ETAG_ATTRIBUTE, etag);
                    storage.completeMultipartUpload(mpu, parts);
                    log.info("Multipart upload committed, bytes handled {}", bytesHandled);
                }

                return null;
            }
        });
        result.onSuccess(success -> {
            if (handler != null) {
                handler.handle(Future.succeededFuture());
            }
        }).onFailure(error -> {
            if (handler != null) {
                handler.handle(Future.failedFuture(error));
            }

            if (errorHandler != null) {
                errorHandler.handle(error);
            }
        });
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
        vertx.executeBlocking(() -> {
            synchronized (BlobWriteStream.this) {
                try {
                    if (mpu == null) {
                        mpu = storage.initMultipartUpload(resource.getAbsoluteFilePath(), contentType);
                    }
                    lock.extend();
                    Buffer chunk = chunkBuffer.slice(0, position);
                    etagBuilder.append(chunk.getBytes());
                    MultipartPart part = storage.storeMultipartPart(mpu, ++chunkNumber, chunk);
                    parts.add(part);
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
}
