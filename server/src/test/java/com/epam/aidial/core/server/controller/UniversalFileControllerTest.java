package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerFileUpload;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UniversalFileControllerTest {
    private Proxy proxy;
    private ProxyContext context;
    private HttpServerRequest request;
    private ComplexResourceService service;
    private ResourceDescriptor root;

    @BeforeEach
    void init() {
        proxy = mock(Proxy.class);
        request = mock(HttpServerRequest.class, RETURNS_SELF);
        service = mock(ComplexResourceService.class);
        context = mock(ProxyContext.class, invocation -> {
            if (invocation.getMethod().getReturnType() == Future.class) {
                return Future.succeededFuture();
            }
            if (invocation.getMethod().getReturnType() == ProxyContext.class) {
                return invocation.getMock();
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(context.getRequest()).thenReturn(request);
        when(proxy.getComplexResourceService()).thenReturn(service);
        when(context.getUserDisplayName()).thenReturn("author");
        BlobStorage storage = mock(BlobStorage.class);
        when(proxy.getStorage()).thenReturn(storage);
        when(storage.getMaxUploadedFileSize()).thenReturn(100L);
        when(service.getMaxFileSizeBytes(any())).thenReturn(4L);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        when(proxy.getTaskExecutor()).thenReturn(executor);
        doAnswer(invocation -> {
            try {
                return Future.succeededFuture(((Callable<?>) invocation.getArgument(0)).call());
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }).when(executor).submit(any());
        root = ResourceDescriptorFactory.fromPublicUrl("skills/public/s");
    }

    @Test
    void testInvalidTargets() {
        when(request.method()).thenReturn(HttpMethod.GET);
        controller("").handle(root, false);
        ArgumentCaptor<HttpException> error = ArgumentCaptor.forClass(HttpException.class);
        verify(context).respond(error.capture(), anyString());
        assertEquals(HttpStatus.BAD_REQUEST, error.getValue().getStatus());
    }

    @Test
    void testHiddenTarget() {
        controller("refs/.dial-resource").handle(root, false);
        ArgumentCaptor<HttpException> error = ArgumentCaptor.forClass(HttpException.class);
        verify(context).respond(error.capture(), anyString());
        assertEquals(HttpStatus.NOT_FOUND, error.getValue().getStatus());
    }

    @Test
    void testEncodedSeparatorRejected() {
        controller("refs/%2Fsecret").handle(root, false);
        verify(context).respond(eq(HttpStatus.BAD_REQUEST), anyString());
    }

    @Test
    void testUnsupportedMethod() {
        when(request.method()).thenReturn(HttpMethod.POST);
        controller("a.txt").handle(root, false);
        verify(context).respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void testMissingDownload() {
        when(request.method()).thenReturn(HttpMethod.GET);
        controller("a.txt").handle(root, false);
        verify(context).respond(HttpStatus.NOT_FOUND);
    }

    @Test
    void testConditionalDownloadFailurePropagates() {
        when(request.method()).thenReturn(HttpMethod.GET);
        HttpException failure = new HttpException(HttpStatus.NOT_MODIFIED, "cached");
        when(service.getFileStream(any(), eq("a.txt"), any())).thenThrow(failure);
        controller("a.txt").handle(root, false);
        verify(context).respond(eq(failure), anyString());
    }

    @Test
    void testInvalidMultipartContentType() {
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("text/plain");
        controller("a.txt").handle(root, true);
        verify(context).respond(eq(HttpStatus.BAD_REQUEST), anyString());
        verify(request, never()).uploadHandler(any());
    }

    @Test
    void testMultipartMetadataAndStoredMime() {
        startUpload();
        FileMetadata metadata = new FileMetadata(ResourceDescriptorFactory.fromPublicUrl("skills/public/s/files/a.txt"), 4L, "application/custom");
        metadata.setEtag("file-tag");
        when(service.putFile(any(), any(), eq("a.txt"), any(), any(), eq("application/custom"), eq("author")))
                .thenReturn(new ComplexResourceService.FileMutationResult(metadata, "resource-tag"));
        HttpServerFileUpload upload = emitUpload("application/custom");
        emitChunk(upload, "abcd");
        endRequest();
        verify(service).putFile(eq(root), any(), eq("a.txt"), eq("abcd".getBytes()), any(), eq("application/custom"), eq("author"));
        verify(context).putHeader(HttpHeaders.ETAG, "file-tag");
        verify(context).putHeader(UniversalFileController.RESOURCE_ETAG_HEADER, "resource-tag");
        verify(context).respond(HttpStatus.OK, metadata);
    }

    @Test
    void testZeroFileParts() {
        startUpload();
        endRequest();
        assertResponseFailure(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testMultipleFileParts() {
        startUpload();
        emitUpload("text/plain");
        emitUpload("text/plain");
        assertResponseFailure(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testReceiveTimeLimit() {
        startUpload();
        HttpServerFileUpload upload = emitUpload("text/plain");
        emitChunk(upload, "abcd");
        emitChunk(upload, "e");
        emitChunk(upload, "discarded");
        endRequest();
        assertResponseFailure(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        verify(service, never()).putFile(any(), any(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void testUploadFailurePropagates() {
        startUpload();
        HttpServerFileUpload upload = emitUpload("text/plain");
        ArgumentCaptor<Handler<Throwable>> handler = ArgumentCaptor.forClass(Handler.class);
        verify(upload).exceptionHandler(handler.capture());
        IllegalStateException failure = new IllegalStateException("upload failed");
        handler.getValue().handle(failure);
        verify(context).respond(eq(failure), anyString());
    }

    @Test
    void testRequestFailurePropagates() {
        startUpload();
        ArgumentCaptor<Handler<Throwable>> handler = ArgumentCaptor.forClass(Handler.class);
        verify(request).exceptionHandler(handler.capture());
        IllegalStateException failure = new IllegalStateException("request failed");
        handler.getValue().handle(failure);
        verify(context).respond(eq(failure), anyString());
    }

    @Test
    void testMutationFailurePropagates() {
        startUpload();
        HttpException failure = new HttpException(HttpStatus.PRECONDITION_FAILED, "stale file");
        when(service.putFile(any(), any(), eq("a.txt"), any(), any(), eq("text/plain"), eq("author"))).thenThrow(failure);
        HttpServerFileUpload upload = emitUpload("text/plain");
        emitChunk(upload, "ok");
        endRequest();
        verify(context).respond(eq(failure), anyString());
    }

    @Test
    void testDeleteFailurePropagates() {
        when(request.method()).thenReturn(HttpMethod.DELETE);
        HttpException failure = new HttpException(HttpStatus.NOT_FOUND, "missing file");
        when(service.deleteFileWithFileEtag(eq(root), any(), eq("a.txt"), any(), eq("author"))).thenThrow(failure);
        controller("a.txt").handle(root, true);
        verify(context).respond(eq(failure), anyString());
    }

    @Test
    void testDeleteResponseAndFailure() {
        when(request.method()).thenReturn(HttpMethod.DELETE);
        when(service.deleteFileWithFileEtag(eq(root), any(), eq("a.txt"), any(), eq("author"))).thenReturn("resource-tag");
        controller("a.txt").handle(root, true);
        verify(context).putHeader(UniversalFileController.RESOURCE_ETAG_HEADER, "resource-tag");
        verify(context).respond(HttpStatus.OK);
        verify(context, never()).putHeader(eq(HttpHeaders.ETAG), anyString());
    }

    private UniversalFileController controller(String path) {
        return new UniversalFileController(proxy, context, true, path);
    }

    private void startUpload() {
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("multipart/form-data; boundary=test");
        controller("a.txt").handle(root, true);
    }

    private HttpServerFileUpload emitUpload(String contentType) {
        ArgumentCaptor<Handler<HttpServerFileUpload>> handler = ArgumentCaptor.forClass(Handler.class);
        verify(request).uploadHandler(handler.capture());
        HttpServerFileUpload upload = mock(HttpServerFileUpload.class, RETURNS_SELF);
        when(upload.contentType()).thenReturn(contentType);
        handler.getValue().handle(upload);
        return upload;
    }

    private static void emitChunk(HttpServerFileUpload upload, String content) {
        ArgumentCaptor<Handler<Buffer>> handler = ArgumentCaptor.forClass(Handler.class);
        verify(upload).handler(handler.capture());
        handler.getValue().handle(Buffer.buffer(content));
    }

    private void endRequest() {
        ArgumentCaptor<Handler<Void>> handler = ArgumentCaptor.forClass(Handler.class);
        verify(request).endHandler(handler.capture());
        handler.getValue().handle(null);
    }

    private void assertResponseFailure(HttpStatus status) {
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(context).respond(error.capture(), anyString());
        assertEquals(status, ((HttpException) error.getValue()).getStatus());
    }
}
