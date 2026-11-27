package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.FileTarget;
import com.epam.aidial.core.server.service.resource.ComplexResourceHandler;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.service.resource.ProjectHandler;
import com.epam.aidial.core.server.service.resource.SkillHandler;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.stream.InputStreamReader;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.HttpUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.Map;

@Slf4j
public class UniversalFileController extends AccessControlBaseController {
    public static final String RESOURCE_ETAG_HEADER = "X-Resource-ETag";
    private static final Map<ResourceType, ComplexResourceHandler> HANDLERS = Map.of(
            ResourceTypes.SKILL, new SkillHandler(), ResourceTypes.PROJECT, new ProjectHandler());

    private final ComplexResourceService complexResourceService;
    private final String encodedFilePath;

    public UniversalFileController(Proxy proxy, ProxyContext context, boolean write, String encodedFilePath) {
        super(proxy, context, write);
        this.complexResourceService = proxy.getComplexResourceService();
        this.encodedFilePath = encodedFilePath;
    }

    @Override
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        try {
            FileTarget target = ResourceDescriptorFactory.fromFileUrl(resource.getUrl() + "/files/" + encodedFilePath, proxy.getEncryptionService());
            target.validatePath();
            target.requireFile();
            return switch (context.getRequest().method().name()) {
                case "GET" -> download(target);
                case "PUT" -> upload(target);
                case "DELETE" -> delete(target);
                default -> context.respond(HttpStatus.METHOD_NOT_ALLOWED);
            };
        } catch (IllegalArgumentException e) {
            return context.respond(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (HttpException e) {
            return context.respond(e, "Invalid file target");
        }
    }

    @ApiOperation(
            method = "GET", path = "/v1/skills/{bucket}/{path}/files/{filePath}", operationId = "downloadSkillFileV1",
            tags = {"Files", "Skills"}, contentType = "application/octet-stream",
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Owning resource path before the first files segment"),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, required = true, description = "Relative file path"),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = "File ETag precondition"),
                    @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "File ETag cache condition")
            },
            responses = {
                    @ApiResponse(code = 200, body = @ApiSchema(implementation = byte[].class), headers = {
                            @ApiHeader(name = "ETag", description = "File ETag"),
                            @ApiHeader(name = "X-Resource-ETag", description = "Owning resource aggregate ETag"),
                            @ApiHeader(name = "Content-Type", description = "Stored file MIME type"),
                            @ApiHeader(name = "Content-Length", description = "File size", schema = Long.class)}),
                    @ApiResponse(code = 304, headers = {@ApiHeader(name = "ETag", description = "File ETag")}),
                    @ApiResponse(code = 400), @ApiResponse(code = 403), @ApiResponse(code = 404),
                    @ApiResponse(code = 412), @ApiResponse(code = 500)
            })
    @ApiOperation(
            method = "GET", path = "/v1/projects/{bucket}/{path}/files/{filePath}", operationId = "downloadProjectFileV1",
            tags = {"Files", "Projects"}, contentType = "application/octet-stream",
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Owning resource path before the first files segment"),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, required = true, description = "Relative file path"),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = "File ETag precondition"),
                    @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "File ETag cache condition")
            },
            responses = {
                    @ApiResponse(code = 200, body = @ApiSchema(implementation = byte[].class), headers = {
                            @ApiHeader(name = "ETag", description = "File ETag"),
                            @ApiHeader(name = "X-Resource-ETag", description = "Owning resource aggregate ETag"),
                            @ApiHeader(name = "Content-Type", description = "Stored file MIME type"),
                            @ApiHeader(name = "Content-Length", description = "File size", schema = Long.class)}),
                    @ApiResponse(code = 304, headers = {@ApiHeader(name = "ETag", description = "File ETag")}),
                    @ApiResponse(code = 400), @ApiResponse(code = 403), @ApiResponse(code = 404),
                    @ApiResponse(code = 412), @ApiResponse(code = 500)
            })
    private Future<?> download(FileTarget target) {
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        proxy.getTaskExecutor().submit(() -> complexResourceService.getFileStream(target.resource(), target.filePath(), etag))
                .compose(result -> {
                    if (result == null) {
                        return context.respond(HttpStatus.NOT_FOUND);
                    }
                    var resourceStream = result.stream();
                    HttpServerResponse response = context.putHeader(HttpHeaders.CONTENT_TYPE, resourceStream.contentType())
                            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(resourceStream.contentLength()))
                            .putHeader(HttpHeaders.ETAG, resourceStream.etag())
                            .putHeader(RESOURCE_ETAG_HEADER, result.resourceEtag()).exposeHeaders().getResponse();
                    InputStreamReader stream = new InputStreamReader(proxy.getVertx(), proxy.getTaskExecutor(), resourceStream.inputStream());
                    stream.pipe().endOnFailure(false).to(response).onFailure(error -> {
                        stream.close();
                        response.reset();
                    });
                    return Future.succeededFuture();
                }).onFailure(error -> handleError(error, target));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "PUT", path = "/v1/skills/{bucket}/{path}/files/{filePath}", operationId = "uploadSkillFileV1",
            tags = {"Files", "Skills"}, contentType = "multipart/form-data", requestBody = @ApiSchema(implementation = byte[].class),
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Existing owning resource path"),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = "File ETag precondition, not the resource ETag"),
                    @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "Use * to create only an absent file")
            },
            responses = {
                    @ApiResponse(code = 200, body = @ApiSchema(implementation = FileMetadata.class), contentTypes = {"application/json"}, headers = {
                            @ApiHeader(name = "ETag", description = "Written file ETag"),
                            @ApiHeader(name = "X-Resource-ETag", description = "Committed owning resource ETag")}),
                    @ApiResponse(code = 400), @ApiResponse(code = 403),
                    @ApiResponse(code = 404, description = "Create the owning resource with its whole-resource v2 PUT API first"),
                    @ApiResponse(code = 412), @ApiResponse(code = 413), @ApiResponse(code = 500)
            })
    @ApiOperation(
            method = "PUT", path = "/v1/projects/{bucket}/{path}/files/{filePath}", operationId = "uploadProjectFileV1",
            tags = {"Files", "Projects"}, contentType = "multipart/form-data", requestBody = @ApiSchema(implementation = byte[].class),
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Existing owning resource path"),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = "File ETag precondition, not the resource ETag"),
                    @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "Use * to create only an absent file")
            },
            responses = {
                    @ApiResponse(code = 200, body = @ApiSchema(implementation = FileMetadata.class), contentTypes = {"application/json"}, headers = {
                            @ApiHeader(name = "ETag", description = "Written file ETag"),
                            @ApiHeader(name = "X-Resource-ETag", description = "Committed owning resource ETag")}),
                    @ApiResponse(code = 400), @ApiResponse(code = 403),
                    @ApiResponse(code = 404, description = "Create the owning resource with its whole-resource v2 PUT API first"),
                    @ApiResponse(code = 412), @ApiResponse(code = 413), @ApiResponse(code = 500)
            })
    private Future<?> upload(FileTarget target) {
        HttpServerRequest request = context.getRequest();
        String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
        if (contentType == null || !HttpUtils.isValidMultipartContentType(contentType)) {
            return context.respond(HttpStatus.BAD_REQUEST, "Request must have a valid multipart/form-data content-type");
        }
        EtagHeader etag = ProxyUtil.etag(request);
        String author = context.getUserDisplayName();
        long maxBytes = Math.min(proxy.getStorage().getMaxUploadedFileSize(), complexResourceService.getMaxFileSizeBytes(target.resource().getType()));
        MutableObject<UploadedFile> fileRef = new MutableObject<>();
        Promise<Void> received = Promise.promise();
        request.setExpectMultipart(true).uploadHandler(upload -> {
            upload.exceptionHandler(received::tryFail);
            if (fileRef.getValue() != null) {
                upload.handler(ignore -> { });
                received.tryFail(new HttpException(HttpStatus.BAD_REQUEST, "Exactly one file part is required"));
                return;
            }
            Buffer buffer = Buffer.buffer();
            fileRef.setValue(new UploadedFile(buffer, upload.contentType()));
            upload.handler(chunk -> {
                if (received.future().isComplete()) {
                    return;
                }
                if ((long) buffer.length() + chunk.length() > maxBytes) {
                    received.tryFail(new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, "File exceeds the upload size limit"));
                } else {
                    buffer.appendBuffer(chunk);
                }
            });
        }).endHandler(ignore -> {
            if (fileRef.getValue() == null) {
                received.tryFail(new HttpException(HttpStatus.BAD_REQUEST, "Exactly one file part is required"));
            } else {
                received.tryComplete();
            }
        }).exceptionHandler(received::tryFail);
        received.future().compose(ignore -> {
            UploadedFile file = fileRef.getValue();
            byte[] bytes = file.buffer().getBytes();
            return proxy.getTaskExecutor().submit(() -> complexResourceService.putFile(target.resource(), HANDLERS.get(target.resource().getType()),
                    target.filePath(), bytes, etag, file.contentType(), author));
        }).compose(result -> context.putHeader(HttpHeaders.ETAG, result.metadata().getEtag())
                .putHeader(RESOURCE_ETAG_HEADER, result.resourceEtag()).exposeHeaders().respond(HttpStatus.OK, result.metadata()))
                .onFailure(error -> handleError(error, target));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "DELETE", path = "/v1/skills/{bucket}/{path}/files/{filePath}", operationId = "deleteSkillFileV1",
            tags = {"Files", "Skills"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = "File ETag precondition"),
                    @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "File ETag precondition")
            },
            responses = {
                    @ApiResponse(code = 200, description = "Empty success response; entrypoint deletion remains subject to resource validation", headers = {
                            @ApiHeader(name = "X-Resource-ETag", description = "Owning resource aggregate ETag after deletion")}),
                    @ApiResponse(code = 400), @ApiResponse(code = 403), @ApiResponse(code = 404),
                    @ApiResponse(code = 412), @ApiResponse(code = 500)
            })
    @ApiOperation(
            method = "DELETE", path = "/v1/projects/{bucket}/{path}/files/{filePath}", operationId = "deleteProjectFileV1",
            tags = {"Files", "Projects"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, required = true),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = "File ETag precondition"),
                    @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "File ETag precondition")
            },
            responses = {
                    @ApiResponse(code = 200, description = "Empty success response", headers = {
                            @ApiHeader(name = "X-Resource-ETag", description = "Owning resource aggregate ETag after deletion")}),
                    @ApiResponse(code = 400), @ApiResponse(code = 403), @ApiResponse(code = 404),
                    @ApiResponse(code = 412), @ApiResponse(code = 500)
            })
    private Future<?> delete(FileTarget target) {
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        String author = context.getUserDisplayName();
        proxy.getTaskExecutor().submit(() -> complexResourceService.deleteFileWithFileEtag(target.resource(),
                HANDLERS.get(target.resource().getType()), target.filePath(), etag, author))
                .compose(resourceEtag -> context.putHeader(RESOURCE_ETAG_HEADER, resourceEtag).exposeHeaders().respond(HttpStatus.OK))
                .onFailure(error -> handleError(error, target));
        return Future.succeededFuture();
    }

    private void handleError(Throwable error, FileTarget target) {
        log.warn("Failed complex file request: {}", target.descriptor().getUrl(), error);
        context.respond(error, "Failed file request: " + target.descriptor().getUrl());
    }

    private record UploadedFile(Buffer buffer, String contentType) {
    }
}
