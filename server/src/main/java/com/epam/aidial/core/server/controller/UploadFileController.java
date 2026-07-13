package com.epam.aidial.core.server.controller;


import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.stream.BlobWriteStream;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.HttpUtils;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.impl.PipeImpl;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UploadFileController extends AccessControlBaseController {

    public UploadFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, true);
    }

    @Override
    @ApiOperation(
            method = "PUT",
            path = "/v1/files/{bucket}/{file_path}",
            operationId = "uploadFile",
            contentType = "multipart/form-data",
            tags = {"Files"},
            requestBody = @ApiSchema(implementation = byte[].class),
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                    @ApiParameter(name = "file_path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.FILE_PATH),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH_UPLOAD_FILE),
                    @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH_UPLOAD_FILE)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = FileMetadata.class),
                            headers = {
                                    @ApiHeader(name = "ETag", description = "Entity tag for the uploaded file", required = true)
                            }),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 412),
                    @ApiResponse(code = 413),
                    @ApiResponse(code = 500)
            }
    )
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "File name is missing");
        }

        if (!ResourceDescriptorFactory.isValidResourcePath(resource)) {
            return context.respond(HttpStatus.BAD_REQUEST, "Resource name and/or parent folders must not end with .(dot)");
        }
        String author = context.getUserDisplayName();
        return proxy.getTaskExecutor().submit(() -> {
            EtagHeader etag = validateRequest(context.getRequest(), resource);
            context.getRequest()
                    .setExpectMultipart(true)
                    .uploadHandler(upload -> {
                        String contentType = upload.contentType();
                        Pipe<Buffer> pipe = new PipeImpl<>(upload).endOnFailure(false);
                        BlobWriteStream writeStream = new BlobWriteStream(proxy.getTaskExecutor(), proxy.getResourceService(),
                                resource, etag, contentType, author);
                        pipe.to(writeStream)
                                .onSuccess(success -> {
                                    FileMetadata metadata = writeStream.getMetadata();
                                    context.putHeader(HttpHeaders.ETAG, metadata.getEtag())
                                            .exposeHeaders()
                                            .respond(HttpStatus.OK, metadata);
                                })
                                .onFailure(error -> {
                                    writeStream.abortUpload(error);
                                    handleError(error, resource);
                                });
                    }).exceptionHandler(error -> handleError(error, resource));

            return Future.succeededFuture();
        })
                .otherwise(error -> {
                    handleError(error, resource);
                    return null;
                });
    }

    private void handleError(Throwable error, ResourceDescriptor resource) {
        context.respond(error, "Failed to upload file: " + resource.getUrl());
        log.warn("Failed to upload file: {}", resource.getUrl(), error);
    }

    private EtagHeader validateRequest(HttpServerRequest request, ResourceDescriptor resource) {
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        etag.validate(() -> proxy.getResourceService().getEtag(resource));
        if (contentType == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Request must have a content-type header to decode a multipart request");
        }
        if (!HttpUtils.isValidMultipartContentType(contentType)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Request must have a valid content-type header to decode a multipart request");
        }
        return etag;
    }
}