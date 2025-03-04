package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterExecuteRequest;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterInputFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterOutputFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterSession;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterSessionId;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.service.codeinterpreter.CodeInterpreterService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.InputStreamAdapter;
import com.epam.aidial.core.server.vertx.stream.InputStreamReader;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerFileUpload;
import io.vertx.core.http.HttpServerResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

@Slf4j
class CodeInterpreterController {

    private final ProxyContext context;
    private final Vertx vertx;
    private final CodeInterpreterService service;

    private final AccessService accessService;

    public CodeInterpreterController(ProxyContext context) {
        this.context = context;
        this.vertx = context.getProxy().getVertx();
        this.service = context.getProxy().getCodeInterpreterService();
        this.accessService = context.getProxy().getAccessService();
    }

    Future<?> openSession() {
        context.getRequest()
                .body()
                .compose(body -> {
                    checkRunCodeInterpreter();
                    CodeInterpreterSessionId data = convertJson(body, CodeInterpreterSessionId.class);
                    return vertx.executeBlocking(() -> service.openSession(context, data.getSessionId()), false);
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    Future<?> closeSession() {
        context.getRequest()
                .body()
                .compose(body -> {
                    CodeInterpreterSessionId data = convertJson(body, CodeInterpreterSessionId.class);
                    return vertx.executeBlocking(() -> service.closeSession(context, data.getSessionId()), false);
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    Future<?> getSession() {
        context.getRequest()
                .body()
                .compose(body -> {
                    CodeInterpreterSessionId data = convertJson(body, CodeInterpreterSessionId.class);
                    return vertx.executeBlocking(() -> service.getSession(context, data.getSessionId()), false);
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    Future<?> executeCode() {
        context.getRequest()
                .body()
                .compose(body -> {
                    checkRunCodeInterpreter();
                    CodeInterpreterExecuteRequest data = convertJson(body, CodeInterpreterExecuteRequest.class);
                    return vertx.executeBlocking(() -> service.executeCode(context, data), false);
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    Future<?> uploadFile() {
        context.getRequest()
                .setExpectMultipart(true)
                .uploadHandler(upload -> {
                    // do not move inside execute blocking, otherwise you can miss the beginning of file
                    InputStreamAdapter stream = new InputStreamAdapter(upload);
                    vertx.executeBlocking(() -> uploadFile(upload, stream), false)
                            .onSuccess(this::respondJson)
                            .onComplete(e -> stream.close())
                            .onFailure(this::respondError);
                });

        return Future.succeededFuture();
    }

    @SneakyThrows
    private CodeInterpreterFile uploadFile(HttpServerFileUpload upload, InputStream stream) {
        String sessionId = context.getRequest().getParam("session_id");
        String fileName = upload.filename();

        if (sessionId == null) {
            throw new IllegalArgumentException("Missing session_id query param");
        }

        if (fileName == null) {
            throw new IllegalArgumentException("Missing filename in multipart upload");
        }

        return service.uploadFile(context, sessionId, fileName, stream);
    }

    Future<?> downloadFile() {
        context.getRequest().body()
                .compose(buffer -> vertx.executeBlocking(() -> downloadFile(buffer), false))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    private Void downloadFile(Buffer body) {
        CodeInterpreterFile data = convertJson(body, CodeInterpreterFile.class);
        HttpServerResponse response = context.getResponse();

        return service.downloadFile(context, data.getSessionId(), data.getPath(), (stream, size) -> {
            response.putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(size));
            return new InputStreamReader(vertx, stream)
                    .pipe()
                    .endOnFailure(false)
                    .to(response);
        });
    }

    Future<?> listFiles() {
        context.getRequest()
                .body()
                .compose(body -> {
                    CodeInterpreterSessionId data = convertJson(body, CodeInterpreterSessionId.class);
                    return vertx.executeBlocking(() -> service.listFiles(context, data.getSessionId()), false);
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    Future<?> transferInputFile() {
        context.getRequest()
                .body()
                .compose(body -> {
                    CodeInterpreterInputFile data = convertJson(body, CodeInterpreterInputFile.class);
                    return vertx.executeBlocking(() -> service.transferInputFile(context, data), false);
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    Future<?> transferOutputFile() {
        context.getRequest()
                .body()
                .compose(body -> {
                    CodeInterpreterOutputFile data = convertJson(body, CodeInterpreterOutputFile.class);
                    return vertx.executeBlocking(() -> service.transferOutputFile(context, data), false);
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    private void respondJson(Object data) {
        if (data instanceof CodeInterpreterSession session) {
            session.setDeploymentId(null);
            session.setDeploymentUrl(null);
            session.setUsedAt(null);
        }

        context.respond(HttpStatus.OK, data);
    }

    private void respondError(Throwable error) {
        HttpServerResponse response = context.getResponse();
        if (response.headWritten()) {
            // download request can partially fail, when some data already is sent, it is too late to send response
            // so the only option is to disconnect client
            response.reset();
        } else if (error instanceof IllegalArgumentException) {
            context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
        } else if (error instanceof PermissionDeniedException) {
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else if (error instanceof HttpException e) {
            context.respond(e.getStatus(), e.getMessage());
        } else {
            log.error("Failed to handle code interpreter request", error);
            context.respond(error, "Internal error");
        }
    }

    private void checkRunCodeInterpreter() {
        if (!accessService.canCreateCodeApps(context.getUserRoles())) {
            throw new PermissionDeniedException("User doesn't have sufficient permissions to run code interpreter");
        }
    }

    private static <T> T convertJson(Buffer body, Class<T> clazz) {
        try {
            T result = ProxyUtil.convertToObject(body, clazz);

            if (result == null) {
                throw new IllegalArgumentException("No JSON body");
            }

            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Not valid JSON body");
        }
    }
}