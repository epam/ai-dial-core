package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.AdminExportBusyException;
import com.epam.aidial.core.server.service.AdminExportService;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.server.vertx.stream.BoundedPipe;
import com.epam.aidial.core.server.vertx.stream.InputStreamReader;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.Pipe;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Admin-only export of the {@code public} and {@code platform} buckets at {@code POST /v1/admin/export}.
 *
 * <p>The archive is streamed while it is built: {@link AdminExportService} writes it on a task thread into a
 * {@link BoundedPipe}, and {@link InputStreamReader} feeds the response from the pipe, so a slow client slows
 * the export down instead of growing memory, and a client that is too slow for the stall timeout fails it.
 *
 * <p>Once the {@code 200} head is sent the status cannot change. A failure after that resets the connection,
 * so the client sees a transport error and an archive without the central directory, never a complete ZIP.
 * A failure before that, such as {@code 409} when another export or a write holds a bucket lock, gets a status.
 */
public class AdminExportController implements Controller {

    private static final int PIPE_CAPACITY = 1024 * 1024;
    private static final DateTimeFormatter FILE_NAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final AdminExportService exportService;
    private final AsyncTaskExecutor taskExecutor;
    private final Vertx vertx;
    private final Duration stallTimeout;

    public AdminExportController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.authorizationService = proxy.getConfigAuthService();
        this.exportService = proxy.getAdminExportService();
        this.taskExecutor = proxy.getTaskExecutor();
        this.vertx = proxy.getVertx();
        this.stallTimeout = Duration.ofMillis(proxy.getAdminExportSettings().getStallTimeout());
    }

    @Override
    @ApiOperation(
            method = "POST",
            path = "/v1/admin/export",
            operationId = "exportAdminBuckets",
            tags = {"Admin"},
            responses = {
                    @ApiResponse(code = 200, description = "ZIP archive of the public and platform buckets",
                            body = @ApiSchema(implementation = byte[].class), contentTypes = {"application/zip"}),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 409, description = "Another export or a write holds a bucket lock, retry later"),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> handle() {
        if (!authorizationService.isAdmin(context)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }

        Instant start = Instant.now();
        HttpServerResponse response = context.getResponse().setChunked(true);
        // set only when the archive starts, so that a status response sent before it does not carry them
        response.headersEndHandler(ignored -> {
            if (response.getStatusCode() == HttpStatus.OK.getCode()) {
                response.putHeader(HttpHeaders.CONTENT_TYPE, "application/zip");
                response.putHeader(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"dial-export-" + FILE_NAME_TIMESTAMP.format(start) + ".zip\"");
            }
        });

        BoundedPipe pipe = new BoundedPipe(PIPE_CAPACITY, stallTimeout);
        InputStreamReader reader = new InputStreamReader(vertx, taskExecutor, pipe.getInput());
        Pipe<Buffer> responsePipe = reader.pipe().endOnFailure(false);
        responsePipe.to(response).onFailure(error -> {
            reader.close();
            if (response.headWritten()) {
                response.reset();
            }
        });

        taskExecutor.submit(() -> {
            // not in try-with-resources: closing the stream on a failure would end the response cleanly
            try {
                exportService.export(pipe.getOutput(), start);
            } catch (Throwable e) {
                pipe.closeExceptionally(e);
                throw e;
            }
            pipe.getOutput().close();
            return null;
        }).onFailure(error -> {
            // the service has already logged the failure; stop feeding the response before it is ended
            responsePipe.close();
            if (response.headWritten()) {
                response.reset();
            } else if (error instanceof AdminExportBusyException) {
                context.respond(HttpStatus.CONFLICT, error.getMessage());
            } else {
                context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to export the buckets");
            }
            reader.close();
        });

        return Future.succeededFuture();
    }
}
