package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.AdminSystemImportBusyException;
import com.epam.aidial.core.server.service.AdminSystemImportRejectedException;
import com.epam.aidial.core.server.service.AdminSystemImportService;
import com.epam.aidial.core.server.service.AdminSystemImportService.ImportResult;
import com.epam.aidial.core.server.service.AdminSystemImportService.Progress;
import com.epam.aidial.core.server.service.AdminSystemImportService.StagedImport;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.server.vertx.stream.InputStreamAdapter;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Duration;

/**
 * Admin-only system import of the {@code public} and {@code platform} buckets at {@code POST /v1/admin/system-import}.
 * Takes the ZIP that {@link AdminSystemExportController} produces.
 *
 * <p>The body is read and staged on a task thread ({@link AdminSystemImportService#stage}) before anything is
 * touched. Once accepted, this controller sends {@code 200} with {@code application/x-ndjson} and a progress line
 * at least every {@code adminSystemImport.progressInterval} while the replace phase
 * ({@link AdminSystemImportService#replace}) runs, then a final status line. A failure after the head is sent
 * resets the connection, as {@link AdminSystemExportController} does for export, so a failure never looks like a
 * success.
 */
@Slf4j
public class AdminSystemImportController implements Controller {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final AdminSystemImportService importService;
    private final AsyncTaskExecutor taskExecutor;
    private final Vertx vertx;
    private final Duration stallTimeout;
    private final long progressIntervalMs;

    public AdminSystemImportController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.authorizationService = proxy.getConfigAuthService();
        this.importService = proxy.getAdminSystemImportService();
        this.taskExecutor = proxy.getTaskExecutor();
        this.vertx = proxy.getVertx();
        this.stallTimeout = Duration.ofMillis(proxy.getAdminSystemImportSettings().getStallTimeout());
        this.progressIntervalMs = proxy.getAdminSystemImportSettings().getProgressInterval();
    }

    @Override
    @ApiOperation(
            method = "POST",
            path = "/v1/admin/system-import",
            operationId = "systemImport",
            tags = {"Admin"},
            contentType = "application/zip",
            requestBody = @ApiSchema(implementation = byte[].class),
            responses = {
                    @ApiResponse(code = 200, description = "Newline-delimited JSON: progress lines while the replace runs, "
                            + "then a final status line", body = @ApiSchema(implementation = byte[].class),
                            contentTypes = {"application/x-ndjson"}),
                    @ApiResponse(code = 400, description = "The archive is missing, malformed or not accepted by this version"),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 408, description = "No bytes were received from the client for adminSystemImport.stallTimeout"),
                    @ApiResponse(code = 409, description = "Another system import already holds the guard, retry later"),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> handle() {
        if (!authorizationService.isAdmin(context)) {
            closeConnectionAfterResponse();
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }

        InputStreamAdapter adapter = new InputStreamAdapter(context.getRequest(), stallTimeout);

        taskExecutor.submit(() -> {
            LockService.Lock guard = importService.tryLockGuard();
            if (guard == null) {
                throw new AdminSystemImportBusyException("Another system import is already running");
            }
            try {
                StagedImport staged = importService.stage(adapter);
                return Pair.of(guard, staged);
            } catch (Throwable e) {
                guard.close();
                throw e;
            }
        }).onSuccess(pair -> onAccepted(pair.getLeft(), pair.getRight()))
                .onFailure(error -> {
                    adapter.close();
                    if (error instanceof AdminSystemImportBusyException) {
                        closeConnectionAfterResponse();
                        context.respond(HttpStatus.CONFLICT, error.getMessage());
                    } else if (error instanceof AdminSystemImportRejectedException rejected) {
                        closeConnectionAfterResponse();
                        context.respond(rejected.getStatus(), rejected.getMessage());
                    } else {
                        log.error("System import failed during the stage phase", error);
                        closeConnectionAfterResponse();
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to run the system import");
                    }
                });

        return Future.succeededFuture();
    }

    /**
     * Runs on the event loop once the stage phase has accepted the archive. Sends the {@code 200} head, starts the
     * progress timer, and submits the replace phase.
     */
    private void onAccepted(LockService.Lock guard, StagedImport staged) {
        HttpServerResponse response = context.getResponse().setChunked(true);
        response.putHeader(HttpHeaders.CONTENT_TYPE, "application/x-ndjson");
        response.setStatusCode(HttpStatus.OK.getCode());

        Progress progress = new Progress();
        response.write(progressLine(progress).encode() + "\n");
        long timerId = vertx.setPeriodic(progressIntervalMs, ignored -> response.write(progressLine(progress).encode() + "\n"));

        taskExecutor.submit(() -> importService.replace(staged, progress)).onComplete(ar -> {
            vertx.cancelTimer(timerId);
            guard.close();
            taskExecutor.submit(() -> {
                importService.deleteStaging(staged);
                return null;
            });

            if (ar.succeeded()) {
                ImportResult result = ar.result();
                JsonObject status = new JsonObject()
                        .put("status", "success")
                        .put("importedBlobs", result.importedBlobs())
                        .put("deletedBlobs", result.deletedBlobs())
                        .put("warnings", new JsonArray(result.warnings()));
                response.write(status.encode() + "\n");
                response.end();
                log.info("System import completed: importedBlobs={}, deletedBlobs={}, warnings={}",
                        result.importedBlobs(), result.deletedBlobs(), result.warnings());
            } else {
                log.error("System import failed during the replace phase", ar.cause());
                if (response.headWritten()) {
                    response.reset();
                }
            }
        });
    }

    private static JsonObject progressLine(Progress progress) {
        JsonObject json = new JsonObject();
        switch (progress.getPhase()) {
            case WIPE -> json.put("phase", "wipe").put("deleted", progress.getDeleted());
            case COPY -> json.put("phase", "copy").put("done", progress.getCopied()).put("total", progress.getTotal());
            case INVALIDATE -> json.put("phase", "invalidate");
            case REBUILD -> json.put("phase", "rebuild");
            default -> throw new IllegalStateException("Unexpected phase: " + progress.getPhase());
        }
        return json;
    }

    private void closeConnectionAfterResponse() {
        context.getResponse().putHeader(HttpHeaders.CONNECTION, "close");
    }
}
