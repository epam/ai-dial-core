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
import com.epam.aidial.core.server.service.ExportLimits;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Admin-only {@code POST /v1/admin/export} of the Applications that the body names, with their dependencies.
 *
 * <p>The bucket locks of the admin buckets are held from before the response head to the last byte. A failure before the head gets a
 * status; one after it cuts the stream.
 */
@Slf4j
public class AdminExportController implements Controller {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final AsyncTaskExecutor taskExecutor;
    private final Vertx vertx;
    private final ZipResponseStreamer streamer;
    private final AccessService accessService;
    private final Supplier<ExportStreamer> exportStreamerSource;
    private final String coreVersion;
    @Nullable
    private final LockService lockService;
    private final ExportLimits limits;

    public AdminExportController(Proxy proxy, ProxyContext context) {
        this(context, proxy.getConfigAuthService(), proxy.getTaskExecutor(), proxy.getVertx(),
                Duration.ofMillis(proxy.getAdminExportSettings().getStallTimeout()), proxy.getAccessService(),
                () -> newExportStreamer(proxy), proxy.getVersion(), proxy.getLockService(),
                ExportLimits.of(proxy.getAdminExportSettings()));
    }

    AdminExportController(ProxyContext context, ConfigAuthorizationService authorizationService,
                          AsyncTaskExecutor taskExecutor, Vertx vertx, Duration stallTimeout, AccessService accessService,
                          Supplier<ExportStreamer> exportStreamerSource, String coreVersion, @Nullable LockService lockService,
                          ExportLimits limits) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.taskExecutor = taskExecutor;
        this.vertx = vertx;
        this.streamer = new ZipResponseStreamer(vertx, taskExecutor, stallTimeout);
        this.accessService = accessService;
        this.exportStreamerSource = exportStreamerSource;
        this.coreVersion = coreVersion;
        this.lockService = lockService;
        this.limits = limits;
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
            description = "Admin only: the caller needs explicit admin rules, and a per-request key is refused. Exports the selected Applications "
                    + "and the dependencies that they need as a ZIP that is streamed one resource at a time. The entries come in this order: "
                    + ".dial-export/manifest.json (formatVersion 2, mode selective) first, then each resource at its storage path, the entities of "
                    + "the file configuration instead as fragments under .dial-export/config/. The archive holds no metadata entries and no "
                    + "verification entry. An "
                    + "Application, a Model, an Interceptor and a ToolSet are written in the form of the admin GET of the entity, without "
                    + "secrets (a masked client_secret_hint stays); an application type schema is written without the keys of its upstreams, "
                    + "and a catalog schema, a Prompt, a File and a Skill as stored. The archive holds no list of dependencies: a dependency "
                    + "that is missing, unsupported, external or unresolved does not fail the export and is only logged by the server. The "
                    + "request holds only roots, which are checked before the response starts (403 for a public root that the caller cannot "
                    + "read, 404 for a root that does not exist). The export holds the bucket locks of the admin buckets from before the "
                    + "response starts to the last byte, so it excludes the system export, another export and every admin write, and it "
                    + "answers 409 when a lock is held. A limit of the adminExport settings (resources, references, depth, size of one "
                    + "resource, deadline) or any other failure after the response has started cuts the stream: the connection is reset and "
                    + "the ZIP is incomplete, so a client must discard it. The export of whole buckets is POST /v1/admin/system-export. See "
                    + "docs/admin-export.md.",
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
            request = ExportRequestParser.parse(body.toString(StandardCharsets.UTF_8), limits.maxRoots());
        } catch (HttpException e) {
            context.respond(e);
            return;
        }
        ExportStreamer exportStreamer = exportStreamerSource.get();
        ExportLimits.Deadline deadline = limits.startDeadline();
        AtomicReference<ExportSlot> takenLocks = new AtomicReference<>(ExportSlot.newReleasedSlot());
        Future<ExportSlot> checking;
        try {
            checking = taskExecutor.submit(() -> {
                ExportSlot locks = lockBuckets();
                takenLocks.set(locks);
                DeadlineInterrupt interrupt = new DeadlineInterrupt(vertx, Thread.currentThread(), deadline);
                try {
                    exportStreamer.checkRoots(request, this::hasPublicReadAccess);
                    return locks;
                } finally {
                    interrupt.disarm();
                }
            });
        } catch (RuntimeException e) {
            // the executor refused the task before it ran, so no lock was taken
            context.respond(e, "Failed to prepare the export");
            return;
        }
        // a failed check leaves its locks to a task of its own: the thread of the check may have been interrupted
        checking.onSuccess(locks -> runReleasingLocksOnFailure(locks, () -> streamSelection(exportStreamer, request, locks, deadline)))
                .onFailure(error -> takenLocks.get().releaseThen(taskExecutor, () -> context.respond(error, "Failed to prepare the export")));
    }

    /** Takes the locks without waiting, so that a held lock is a {@code 409} before any byte. */
    private ExportSlot lockBuckets() {
        if (lockService == null) {
            return ExportSlot.newReleasedSlot();
        }
        LockService.Lock locks = lockService.tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS);
        if (locks == null) {
            log.info("Admin export rejected: a bucket lock is held by another export, a system export or a write");
            throw new HttpException(HttpStatus.CONFLICT, "A bucket lock is held by another export or a write, retry later");
        }
        return new ExportSlot(locks);
    }

    private void streamSelection(ExportStreamer exportStreamer, ExportRequest request, ExportSlot locks, ExportLimits.Deadline deadline) {
        Instant start = Instant.now();
        streamer.stream(context, "dial-export", start, deadline, locks,
                out -> writeArchive(exportStreamer, request, out, start, deadline),
                error -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to export the Applications"));
    }

    /**
     * A {@link Future} does not route an exception of a success handler to its failure handler, so a handler that fails after the
     * locks were taken would keep them until the lease ends. The release is single, so it is harmless when the streamer took over the locks.
     */
    private void runReleasingLocksOnFailure(ExportSlot locks, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            locks.releaseThen(taskExecutor, () -> context.respond(e, "Failed to prepare the export"));
        }
    }

    private void writeArchive(ExportStreamer exportStreamer, ExportRequest request, OutputStream out, Instant start,
                              ExportLimits.Deadline deadline) {
        DeadlineInterrupt interrupt = new DeadlineInterrupt(vertx, Thread.currentThread(), deadline);
        try {
            exportStreamer.stream(out, request, this::hasPublicReadAccess, limits, deadline, coreVersion, start);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            interrupt.disarm();
        }
    }

    private boolean hasPublicReadAccess(ResourceDescriptor descriptor) {
        return accessService.lookupPermissions(Set.of(descriptor), context)
                .getOrDefault(descriptor, Set.of()).contains(ResourceAccessType.READ);
    }
}
