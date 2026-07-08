package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.folder.FolderResourceHandler;
import com.epam.aidial.core.server.service.folder.FolderResourceService;
import com.epam.aidial.core.server.service.folder.SkillHandler;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.InputStreamReader;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.HttpUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whole-resource (folder-as-resource) controller for the v2 API. Handles the HTTP concerns (multipart
 * decoding for PUT, ZIP streaming for GET) and delegates the resource logic to {@link FolderResourceService}
 * using a per-type handler keyed by URL group.
 */
@Slf4j
public class FolderResourceController extends AccessControlBaseController {

    private static final Map<ResourceType, FolderResourceHandler> HANDLERS = Map.of(
            ResourceTypes.SKILL, new SkillHandler());

    private final FolderResourceService folderResourceService;
    // Relative path of a single file inside the resource; null for whole-resource operations.
    private final String filePath;

    public FolderResourceController(Proxy proxy, ProxyContext context, boolean isWriteAccess) {
        this(proxy, context, isWriteAccess, null);
    }

    public FolderResourceController(Proxy proxy, ProxyContext context, boolean isWriteAccess, String filePath) {
        super(proxy, context, isWriteAccess);
        this.folderResourceService = proxy.getFolderResourceService();
        this.filePath = filePath;
    }

    @Override
    protected Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess) {
        FolderResourceHandler handler = HANDLERS.get(resource.getType());
        if (handler == null) {
            return context.respond(HttpStatus.BAD_REQUEST, "Unsupported folder resource type: " + resource.getType().group());
        }

        HttpMethod method = context.getRequest().method();
        if (filePath != null) {
            if (HttpMethod.PUT.equals(method)) {
                return putFile(resource, handler);
            }
            if (HttpMethod.GET.equals(method)) {
                return getFile(resource);
            }
            if (HttpMethod.DELETE.equals(method)) {
                return deleteFile(resource, handler);
            }
            return context.respond(HttpStatus.METHOD_NOT_ALLOWED);
        }
        if (HttpMethod.PUT.equals(method)) {
            return put(resource, handler);
        }
        if (HttpMethod.GET.equals(method)) {
            return get(resource);
        }
        if (HttpMethod.DELETE.equals(method)) {
            return delete(resource);
        }
        return context.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ApiOperation(
            method = "PUT",
            path = "/v2/skills/{bucket}/{path}",
            operationId = "uploadSkillFolder",
            tags = {"Skills"},
            contentType = "multipart/form-data",
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, description = "The target bucket.", required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, description = "The resource path within the bucket.", required = true),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER,
                        description = "ETag of the version to replace. Use * to overwrite any existing version, or omit to create only if not exists.")
            },
            requestBody = @ApiSchema(implementation = byte[].class),
            responses = {
                    @ApiResponse(code = 200, description = "Folder resource uploaded successfully",
                            headers = {
                                    @ApiHeader(name = "ETag", description = "The ETag of the uploaded resource version")
                            }),
                    @ApiResponse(code = 400, description = "Bad request - invalid content type or malformed request"),
                    @ApiResponse(code = 412, description = "Precondition failed - ETag mismatch")
            },
            responseProfile = ResponseProfile.CONDITIONAL_AUTHORIZED_WRITE,
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    private Future<?> put(ResourceDescriptor resource, FolderResourceHandler handler) {
        HttpServerRequest request = context.getRequest();
        String contentType = request.getHeader(HttpHeaderNames.CONTENT_TYPE);
        if (contentType == null || !HttpUtils.isValidMultipartContentType(contentType)) {
            return context.respond(HttpStatus.BAD_REQUEST, "Request must have a valid multipart/form-data content-type");
        }

        String author = context.getUserDisplayName();
        EtagHeader etag = ProxyUtil.etag(request);

        // Multipart parts arrive sequentially on the event loop; buffer each so the blocking commit can
        // run on a worker thread. The whole request is already size-capped by the proxy.
        Map<String, Buffer> uploads = new LinkedHashMap<>();
        Promise<Void> received = Promise.promise();
        request.setExpectMultipart(true)
                .uploadHandler(upload -> {
                    Buffer buffer = Buffer.buffer();
                    upload.handler(buffer::appendBuffer);
                    upload.endHandler(v -> uploads.put(upload.filename(), buffer));
                    upload.exceptionHandler(received::tryFail);
                })
                .endHandler(v -> received.tryComplete())
                .exceptionHandler(received::tryFail);

        received.future()
                .compose(v -> proxy.getTaskExecutor().submit(() -> folderResourceService.putFolder(resource, handler, uploads, etag, author)))
                .compose(aggregateEtag -> context.putHeader(HttpHeaders.ETAG, aggregateEtag)
                        .exposeHeaders()
                        .respond(HttpStatus.OK)
                        .mapEmpty())
                .recover(error -> {
                    log.warn("Failed to upload resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to upload resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/v2/skills/{bucket}/{path}",
            operationId = "downloadSkillFolder",
            tags = {"Skills"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, description = "The target bucket.", required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, description = "The resource path within the bucket.", required = true)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Folder resource downloaded as ZIP archive",
                            body = @ApiSchema(implementation = byte[].class),
                            contentTypes = {"application/zip"},
                            headers = {
                                    @ApiHeader(name = "ETag", description = "The ETag of the resource version"),
                                    @ApiHeader(name = "Content-Length", description = "Size of the ZIP archive in bytes", schema = Integer.class)
                            }),
                    @ApiResponse(code = 404, description = "Folder resource not found")
            },
            responseProfile = ResponseProfile.CONFIG_RESOURCE_FULL,
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    private Future<?> get(ResourceDescriptor resource) {
        proxy.getTaskExecutor().submit(() -> folderResourceService.getMarker(resource))
                .compose(document -> {
                    if (document == null) {
                        return context.respond(HttpStatus.NOT_FOUND).mapEmpty();
                    }
                    downloadArchive(resource, document);
                    return Future.succeededFuture();
                })
                .recover(error -> {
                    log.warn("Failed to download resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to download resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "DELETE",
            path = "/v2/skills/{bucket}/{path}",
            operationId = "deleteSkillFolder",
            tags = {"Skills"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, description = "The target bucket.", required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, description = "The resource path within the bucket.", required = true),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER,
                            description = "ETag of the version to delete. Use * to delete any existing version.")
            },
            responses = {
                    @ApiResponse(code = 200, description = "Folder resource deleted successfully"),
                    @ApiResponse(code = 404, description = "Folder resource not found"),
                    @ApiResponse(code = 412, description = "Precondition failed - ETag mismatch")
            },
            responseProfile = ResponseProfile.CONDITIONAL_AUTHORIZED_WRITE,
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    private Future<?> delete(ResourceDescriptor resource) {
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        proxy.getTaskExecutor().submit(() -> {
            folderResourceService.deleteFolder(resource, etag);
            return null;
        })
                .compose(v -> context.respond(HttpStatus.OK).mapEmpty())
                .recover(error -> {
                    log.warn("Failed to delete resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to delete resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "PUT",
            path = "/v2/skills/{bucket}/{path}/files/{filePath}",
            operationId = "uploadSkillFile",
            tags = {"Skills"},
            contentType = "multipart/form-data",
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, description = "The target bucket.", required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, description = "The resource path within the bucket.", required = true),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, description = "The relative path of the file within the skill.", required = true),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER,
                            description = "ETag of the skill version. The file is added/replaced in the skill atomically.")
            },
            requestBody = @ApiSchema(implementation = byte[].class),
            responses = {
                    @ApiResponse(code = 200, description = "File uploaded successfully",
                            headers = {
                                    @ApiHeader(name = "ETag", description = "The new ETag of the skill after file upload")
                            }),
                    @ApiResponse(code = 400, description = "Bad request - invalid content type or malformed request"),
                    @ApiResponse(code = 412, description = "Precondition failed - ETag mismatch")
            },
            responseProfile = ResponseProfile.CONDITIONAL_AUTHORIZED_WRITE,
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    private Future<?> putFile(ResourceDescriptor resource, FolderResourceHandler handler) {
        HttpServerRequest request = context.getRequest();
        String contentType = request.getHeader(HttpHeaderNames.CONTENT_TYPE);
        if (contentType == null || !HttpUtils.isValidMultipartContentType(contentType)) {
            return context.respond(HttpStatus.BAD_REQUEST, "Request must have a valid multipart/form-data content-type");
        }

        String author = context.getUserDisplayName();
        EtagHeader etag = ProxyUtil.etag(request);

        MutableObject<Buffer> bufferRef = new MutableObject<>();
        Promise<Void> received = Promise.promise();
        request.setExpectMultipart(true)
                .uploadHandler(upload -> {
                    Buffer buffer = Buffer.buffer();
                    upload.handler(buffer::appendBuffer);
                    upload.endHandler(v -> {
                        if (bufferRef.get() != null) {
                            throw new HttpException(HttpStatus.BAD_REQUEST, "Exactly one file part is required");
                        }
                        bufferRef.setValue(buffer);
                    });
                    upload.exceptionHandler(received::tryFail);
                })
                .endHandler(v -> received.tryComplete())
                .exceptionHandler(received::tryFail);

        received.future()
                .compose(v -> {
                    Buffer buffer = bufferRef.get();
                    if (buffer == null) {
                        throw new HttpException(HttpStatus.BAD_REQUEST, "Exactly one file part is required");
                    }
                    byte[] content = buffer.getBytes();
                    return proxy.getTaskExecutor().submit(() ->
                            folderResourceService.putFile(resource, handler, filePath, content, etag, author));
                })
                .compose(aggregateEtag -> context.putHeader(HttpHeaders.ETAG, aggregateEtag)
                        .exposeHeaders()
                        .respond(HttpStatus.OK)
                        .mapEmpty())
                .recover(error -> {
                    log.warn("Failed to upload file to resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to upload file to resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/v2/skills/{bucket}/{path}/files/{filePath}",
            operationId = "downloadSkillFile",
            tags = {"Skills"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, description = "The target bucket.", required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, description = "The resource path within the bucket.", required = true),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, description = "The relative path of the file within the skill.", required = true)
            },
            responses = {
                    @ApiResponse(code = 200, description = "File downloaded successfully",
                            body = @ApiSchema(implementation = byte[].class),
                            headers = {
                                    @ApiHeader(name = "ETag", description = "The ETag of the skill version"),
                                    @ApiHeader(name = "Content-Type", description = "MIME type of the file"),
                                    @ApiHeader(name = "Content-Length", description = "Size of the file in bytes", schema = Long.class)
                            }),
                    @ApiResponse(code = 404, description = "File or skill not found")
            },
            responseProfile = ResponseProfile.AUTHORIZED_READ,
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    private Future<?> getFile(ResourceDescriptor resource) {
        proxy.getTaskExecutor().submit(() -> folderResourceService.getFileStream(resource, filePath))
                .compose(stream -> {
                    if (stream == null) {
                        return context.respond(HttpStatus.NOT_FOUND).mapEmpty();
                    }
                    streamFile(stream);
                    return Future.succeededFuture();
                })
                .recover(error -> {
                    log.warn("Failed to download file from resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to download file from resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "DELETE",
            path = "/v2/skills/{bucket}/{path}/files/{filePath}",
            operationId = "deleteSkillFile",
            tags = {"Skills"},
            parameters = {
                    @ApiParameter(name = "bucket", in = ParameterIn.PATH, description = "The target bucket.", required = true),
                    @ApiParameter(name = "path", in = ParameterIn.PATH, description = "The resource path within the bucket.", required = true),
                    @ApiParameter(name = "filePath", in = ParameterIn.PATH, description = "The relative path of the file within the skill.", required = true),
                    @ApiParameter(name = "If-Match", in = ParameterIn.HEADER,
                            description = "ETag of the skill version. The file is removed from the skill atomically.")
            },
            responses = {
                    @ApiResponse(code = 200, description = "File deleted successfully",
                            headers = {
                                    @ApiHeader(name = "ETag", description = "The new ETag of the skill after file deletion")
                            }),
                    @ApiResponse(code = 404, description = "File or skill not found"),
                    @ApiResponse(code = 412, description = "Precondition failed - ETag mismatch")
            },
            responseProfile = ResponseProfile.CONDITIONAL_AUTHORIZED_WRITE,
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    private Future<?> deleteFile(ResourceDescriptor resource, FolderResourceHandler handler) {
        String author = context.getUserDisplayName();
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        proxy.getTaskExecutor().submit(() -> folderResourceService.deleteFile(resource, handler, filePath, etag, author))
                .compose(aggregateEtag -> context.putHeader(HttpHeaders.ETAG, aggregateEtag)
                        .exposeHeaders()
                        .respond(HttpStatus.OK)
                        .mapEmpty())
                .recover(error -> {
                    log.warn("Failed to delete file from resource: {}", resource.getUrl(), error);
                    return context.respond(error, "Failed to delete file from resource: " + resource.getUrl()).mapEmpty();
                });
        return Future.succeededFuture();
    }

    private void streamFile(ResourceService.ResourceStream stream) {
        HttpServerResponse response = context.putHeader(HttpHeaders.CONTENT_TYPE, stream.contentType())
                .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(stream.contentLength()))
                .putHeader(HttpHeaders.ETAG, stream.etag())
                .exposeHeaders()
                .getResponse();

        InputStreamReader reader = new InputStreamReader(proxy.getVertx(), proxy.getTaskExecutor(), stream.inputStream());
        reader.pipe()
                .endOnFailure(false)
                .to(response)
                .onFailure(error -> {
                    reader.close();
                    response.reset();
                });
    }

    private void downloadArchive(ResourceDescriptor resource, FolderResourceMarker document) {
        Buffer archive = folderResourceService.downloadArchive(resource, document.getCurrentVersion());

        context.putHeader(HttpHeaders.CONTENT_TYPE, "application/zip")
                .putHeader(HttpHeaders.ETAG, document.getEtag())
                .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(archive.length()))
                .exposeHeaders();

        context.respond(HttpStatus.OK.getCode(), archive);
    }
}
