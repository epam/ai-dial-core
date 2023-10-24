package com.epam.aidial.core.storage;

import com.epam.aidial.core.data.FileMetadata;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class BlobWriteStream implements WriteStream<Buffer> {

    private static final int MIN_PART_SIZE = 5 * 1024 * 1024;

    private final Vertx vertx;
    private final BlobStorage storage;
    private final String fileId;
    private final String parentPath;
    private final String fileName;
    private final String contentType;

    private final Buffer chunkBuffer = Buffer.buffer();
    private int chunkSize = MIN_PART_SIZE;
    private int position;
    private MultipartUpload mpu;
    private int chunkNumber = 0;
    private FileMetadata metadata;

    private Throwable exception;

    private Handler<Throwable> errorHandler;

    private final List<MultipartPart> parts = new ArrayList<>();

    private boolean isBufferFull;

    private long bytesHandled;

    public BlobWriteStream(Vertx vertx,
                           BlobStorage storage,
                           String fileName,
                           String contentType,
                           String fileId,
                           String parentPath) {
        this.vertx = vertx;
        this.storage = storage;
        this.fileName = fileName;
        this.contentType = contentType;
        this.fileId = fileId;
        this.parentPath = parentPath;
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
        Future<Void> result = vertx.executeBlocking(promise -> {
            synchronized (this) {
                if (exception != null) {
                    promise.fail(exception);
                    return;
                }

                Buffer lastChunk = chunkBuffer.slice(0, position);
                metadata = new FileMetadata(fileId, fileName, parentPath, bytesHandled, contentType);
                if (mpu == null) {
                    log.info("Resource is too small for multipart upload, sending as a regular blob");
                    storage.store(metadata, lastChunk);
                } else {
                    if (position != 0) {
                        MultipartPart part = storage.storeMultipartPart(mpu, ++chunkNumber, lastChunk);
                        parts.add(part);
                    }

                    storage.completeMultipartUpload(metadata, mpu, parts);
                    log.info("Multipart upload committed, bytes handled {}", bytesHandled);
                }

                promise.complete();
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
        });
    }

    @Override
    public synchronized WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
        assert maxSize > MIN_PART_SIZE;
        chunkSize = maxSize;
        return this;
    }

    @Override
    public synchronized boolean writeQueueFull() {
        return isBufferFull;
    }

    @Override
    public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
        vertx.executeBlocking((promise) -> {
            synchronized (this) {
                try {
                    if (mpu == null) {
                        mpu = storage.initMultipartUpload(fileId, parentPath, fileName, contentType);
                    }
                    MultipartPart part = storage.storeMultipartPart(mpu, ++chunkNumber, chunkBuffer.slice(0, position));
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
        });

        return this;
    }

    public synchronized FileMetadata getMetadata() {
        return metadata;
    }

    public synchronized void abortUpload(Throwable ex) {
        if (mpu != null) {
            storage.abortMultipartUpload(mpu);
        }

        if (errorHandler != null) {
            errorHandler.handle(ex);
        }

        log.info("Multipart upload aborted");
    }
}
