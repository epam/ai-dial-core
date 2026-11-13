package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.AdminExportSettings;
import com.epam.aidial.core.server.service.ExportRequestParser;
import com.epam.aidial.core.server.service.ExportRequestParser.ExportRequest;
import com.epam.aidial.core.server.service.ExportResourceProjector;
import com.epam.aidial.core.server.service.ExportStreamer;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Admin-only {@code POST /v1/admin/export} of the Applications that the body names, with their dependencies.
 *
 * <p>One task takes the bucket locks of the admin buckets, checks the roots and writes the archive, and releases the locks when it ends.
 * A failure before the first byte gets a status; one after it cuts the stream.
 */
@Slf4j
public class AdminExportController implements Controller {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final ZipResponseStreamer streamer;
    private final AccessService accessService;
    private final Supplier<ExportStreamer> exportStreamerSource;
    private final String coreVersion;
    @Nullable
    private final LockService lockService;
    private final Duration deadline;

    public AdminExportController(Proxy proxy, ProxyContext context) {
        this(context, proxy.getConfigAuthService(), proxy.getTaskExecutor(), proxy.getVertx(), proxy.getAdminExportSettings(),
                proxy.getAccessService(), () -> newExportStreamer(proxy), proxy.getVersion(), proxy.getLockService());
    }

    AdminExportController(ProxyContext context, ConfigAuthorizationService authorizationService,
                          AsyncTaskExecutor taskExecutor, Vertx vertx, AdminExportSettings settings, AccessService accessService,
                          Supplier<ExportStreamer> exportStreamerSource, String coreVersion, @Nullable LockService lockService) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.streamer = new ZipResponseStreamer(vertx, taskExecutor, Duration.ofMillis(settings.getStallTimeout()));
        this.accessService = accessService;
        this.exportStreamerSource = exportStreamerSource;
        this.coreVersion = coreVersion;
        this.lockService = lockService;
        this.deadline = Duration.ofMillis(settings.getDeadline());
    }

    private static ExportStreamer newExportStreamer(Proxy proxy) {
        MergedConfigStore store = (MergedConfigStore) proxy.getConfigStore();
        return new ExportStreamer(proxy.getApiKeyStore().getMutationLock(), store::getInvalidEntities, store::get,
                store::getFileSourcedConfig, proxy.getResourceService(), proxy.getApplicationSchemaService(),
                proxy.getCatalogSchemaService(), proxy.getEncryptionService(),
                new ExportResourceProjector(proxy.getApplicationService(), proxy.getToolSetService()), proxy.getComplexResourceService());
    }

    @Override
    @ApiOperation(
            method = "POST",
            path = "/v1/admin/export",
            operationId = "exportAdminApplications",
            tags = {"Admin"},
            requestBody = @ApiSchema(implementation = ExportRequestParser.ExportRequestBody.class),
            responses = {
                    @ApiResponse(code = 200, description = "ZIP archive of the selected Applications and their dependencies",
                            body = @ApiSchema(implementation = byte[].class), contentTypes = {"application/zip"}),
                    @ApiResponse(code = 400, description = "The body is missing, holds a field other than roots, or is not a valid selection of Applications"),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404, description = "A selected Application does not exist"),
                    @ApiResponse(code = 409, description = "A bucket lock is held by another export, a system export or an admin write, retry later"),
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
        context.getRequest().body()
                .onSuccess(this::exportSelection)
                .onFailure(error -> context.respond(HttpStatus.BAD_REQUEST, "Failed to read the request body"));
        return Future.succeededFuture();
    }

    void exportSelection(Buffer body) {
        ExportRequest request;
        try {
            request = ExportRequestParser.parse(body.toString(StandardCharsets.UTF_8));
        } catch (HttpException e) {
            context.respond(e);
            return;
        }
        ExportStreamer exportStreamer = exportStreamerSource.get();
        Instant start = Instant.now();
        streamer.stream(context, "dial-export", start, deadline, out -> writeArchive(exportStreamer, request, out, start),
                error -> context.respond(error, "Failed to export the Applications"));
    }

    /**
     * Takes the locks, checks the roots and writes the archive on one thread. The head is sent with the first byte of the archive, so a
     * failure before it, a held lock or a root that cannot be exported, is still answered with a status.
     */
    private void writeArchive(ExportStreamer exportStreamer, ExportRequest request, OutputStream out, Instant start) {
        try (LockService.Lock ignored = lockBuckets()) {
            exportStreamer.checkRoots(request, this::hasPublicReadAccess);
            try {
                exportStreamer.stream(out, request, this::hasPublicReadAccess, coreVersion, start);
            } catch (HttpException e) {
                // a status is for the lock and the roots; a failure of the stream before its first byte is a 500
                throw new IllegalStateException(e.getMessage(), e);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Takes the locks without waiting, so that a held lock is a {@code 409} before any byte. */
    private LockService.Lock lockBuckets() {
        if (lockService == null) {
            return () -> {
            };
        }
        LockService.Lock locks = lockService.tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS);
        if (locks == null) {
            log.info("Admin export rejected: a bucket lock is held by another export, a system export or a write");
            throw new HttpException(HttpStatus.CONFLICT, "A bucket lock is held by another export or a write, retry later");
        }
        return locks;
    }

    private boolean hasPublicReadAccess(ResourceDescriptor descriptor) {
        return accessService.lookupPermissions(Set.of(descriptor), context)
                .getOrDefault(descriptor, Set.of()).contains(ResourceAccessType.READ);
    }
}
