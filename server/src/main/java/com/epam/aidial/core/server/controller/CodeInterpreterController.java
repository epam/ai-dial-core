package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterExecuteRequest;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterExecuteResponse;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterFiles;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterInputFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterOutputFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterSession;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterSessionId;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.codeinterpreter.CodeInterpreterService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.server.vertx.stream.InputStreamAdapter;
import com.epam.aidial.core.server.vertx.stream.InputStreamReader;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
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
public class CodeInterpreterController {

    private final ProxyContext context;
    private final Vertx vertx;
    private final CodeInterpreterService service;

    private final AccessService accessService;
    private final AsyncTaskExecutor taskExecutor;

    public CodeInterpreterController(ProxyContext context) {
        this.context = context;
        this.vertx = context.getProxy().getVertx();
        this.service = context.getProxy().getCodeInterpreterService();
        this.accessService = context.getProxy().getAccessService();
        this.taskExecutor = context.getProxy().getTaskExecutor();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/code_interpreter/open_session",
            operationId = "openSession",
            requestBody = @ApiSchema(implementation = CodeInterpreterSessionId.class),
            tags = {"Code interpreter"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = CodeInterpreterSession.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    Future<?> openSession() {
        context.getRequest()
                .body()
                .compose(body -> {
                    checkRunCodeInterpreter();
                    CodeInterpreterSessionId data = convertJson(body, CodeInterpreterSessionId.class);
                    return taskExecutor.submit(() -> service.openSession(context, data.getSessionId()));
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/code_interpreter/close_session",
            operationId = "closeSession",
            requestBody = @ApiSchema(implementation = CodeInterpreterSessionId.class),
            tags = {"Code interpreter"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = CodeInterpreterSession.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    Future<?> closeSession() {
        context.getRequest()
                .body()
                .compose(body -> {
                    CodeInterpreterSessionId data = convertJson(body, CodeInterpreterSessionId.class);
                    return taskExecutor.submit(() -> service.closeSession(context, data.getSessionId()));
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/code_interpreter/get_session",
            operationId = "getSession",
            requestBody = @ApiSchema(implementation = CodeInterpreterSessionId.class),
            tags = {"Code interpreter"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = CodeInterpreterSession.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    Future<?> getSession() {
        context.getRequest()
                .body()
                .compose(body -> {
                    CodeInterpreterSessionId data = convertJson(body, CodeInterpreterSessionId.class);
                    return taskExecutor.submit(() -> service.getSession(context, data.getSessionId()));
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/code_interpreter/execute_code",
            operationId = "executeCode",
            requestBody = @ApiSchema(implementation = CodeInterpreterExecuteRequest.class),
            tags = {"Code interpreter"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = CodeInterpreterExecuteResponse.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    Future<?> executeCode() {
        context.getRequest()
                .body()
                .compose(body -> {
                    checkRunCodeInterpreter();
                    CodeInterpreterExecuteRequest data = convertJson(body, CodeInterpreterExecuteRequest.class);
                    return taskExecutor.submit(() -> service.executeCode(context, data));
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/code_interpreter/upload_file",
            operationId = "uploadFileToCodeInterpreter",
            tags = {"Code interpreter"},
            contentType = "multipart/form-data",
            requestBody = @ApiSchema(implementation = byte[].class),
            parameters = {
                    @ApiParameter(
                            name = "session_id",
                            in = ParameterIn.QUERY,
                            required = true,
                            description = "Code interpreter session identifier"
                    )
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = CodeInterpreterFile.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    Future<?> uploadFile() {
        context.getRequest()
                .setExpectMultipart(true)
                .uploadHandler(upload -> {
                    // do not move inside execute blocking, otherwise you can miss the beginning of file
                    InputStreamAdapter stream = new InputStreamAdapter(upload);
                    taskExecutor.submit(() -> uploadFile(upload, stream))
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

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/code_interpreter/download_file",
            operationId = "downloadFileFromCodeInterpreter",
            requestBody = @ApiSchema(implementation = CodeInterpreterFile.class),
            tags = {"Code interpreter"},
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                        body = @ApiSchema(implementation = byte[].class), contentTypes = {"application/octet-stream"}),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    Future<?> downloadFile() {
        context.getRequest().body()
                .compose(buffer -> taskExecutor.submit(() -> downloadFile(buffer)))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    private Void downloadFile(Buffer body) {
        CodeInterpreterFile data = convertJson(body, CodeInterpreterFile.class);
        HttpServerResponse response = context.getResponse();

        return service.downloadFile(context, data.getSessionId(), data.getPath(), (stream, size) -> {
            if (size == null) {
                response.setChunked(true);
            } else {
                response.putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(size));
            }

            return new InputStreamReader(vertx, taskExecutor, stream)
                    .pipe()
                    .endOnFailure(false)
                    .to(response);
        });
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/code_interpreter/list_files",
            operationId = "listFilesFromCodeInterpreter",
            requestBody = @ApiSchema(implementation = CodeInterpreterSessionId.class),
            tags = {"Code interpreter"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = CodeInterpreterFiles.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    Future<?> listFiles() {
        context.getRequest()
                .body()
                .compose(body -> {
                    CodeInterpreterSessionId data = convertJson(body, CodeInterpreterSessionId.class);
                    return taskExecutor.submit(() -> service.listFiles(context, data.getSessionId()));
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/code_interpreter/transfer_input_file",
            operationId = "transferInputFile",
            requestBody = @ApiSchema(implementation = CodeInterpreterInputFile.class),
            tags = {"Code interpreter"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = CodeInterpreterFile.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    Future<?> transferInputFile() {
        context.getRequest()
                .body()
                .compose(body -> {
                    CodeInterpreterInputFile data = convertJson(body, CodeInterpreterInputFile.class);
                    return taskExecutor.submit(() -> service.transferInputFile(context, data));
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/code_interpreter/transfer_output_file",
            operationId = "transferOutputFile",
            requestBody = @ApiSchema(implementation = CodeInterpreterOutputFile.class),
            tags = {"Code interpreter"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = FileMetadata.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    Future<?> transferOutputFile() {
        context.getRequest()
                .body()
                .compose(body -> {
                    CodeInterpreterOutputFile data = convertJson(body, CodeInterpreterOutputFile.class);
                    return taskExecutor.submit(() -> service.transferOutputFile(context, data));
                })
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    private void respondJson(Object data) {
        if (data instanceof CodeInterpreterSession session) {
            session.setDeploymentId(null);
            session.setDeploymentUrl(null);
            session.setDeploymentType(null);
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
        if (!accessService.canCreateCodeApps(context)) {
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