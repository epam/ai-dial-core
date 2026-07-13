package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.InputStreamReader;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DownloadFileController extends AccessControlBaseController {

    public DownloadFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, false);
    }

    @Override
    @ApiOperation(
            method = "GET",
            path = "/v1/files/{bucket}/{file_path}",
            operationId = "downloadFile",
            contentType = "application/octet-stream",
            tags = {"Files"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                    @ApiParameter(name = "file_path", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.FILE_PATH)
            },
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = byte[].class), contentTypes = {"application/octet-stream"}),
                    @ApiResponse(code = 401, description = OpenApiDescriptions.RESPONSE_INVALID_AUTHENTICATION)
            }
    )
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Can't download a folder");
        }
        EtagHeader etagHeader = ProxyUtil.etag(context.getRequest());
        proxy.getTaskExecutor().submit(() -> proxy.getResourceService().getResourceStream(resource, etagHeader))
                .compose(resourceStream -> {
                    if (resourceStream == null) {
                        return context.respond(HttpStatus.NOT_FOUND);
                    }

                    HttpServerResponse response = context.putHeader(HttpHeaders.CONTENT_TYPE, resourceStream.contentType())
                            // content-length removed by vertx
                            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(resourceStream.contentLength()))
                            .putHeader(HttpHeaders.ETAG, resourceStream.etag())
                            .exposeHeaders()
                            .getResponse();

                    InputStreamReader stream = new InputStreamReader(proxy.getVertx(), proxy.getTaskExecutor(), resourceStream.inputStream());
                    stream.pipe()
                            .endOnFailure(false)
                            .to(response)
                            .onFailure(error -> {
                                stream.close();
                                response.reset();
                            });
                    return Future.succeededFuture();
                }).onFailure(error -> {
                    log.warn("Failed to download file: {}", resource.getUrl(), error);
                    context.respond(error, "Failed to download file: " + resource.getUrl());
                });

        return Future.succeededFuture();
    }
}