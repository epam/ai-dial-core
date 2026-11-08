package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.ExportArchive;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.service.ExportArchive.StoredStream;
import com.epam.aidial.core.server.service.ExportArchive.StreamSource;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportLimits;
import com.epam.aidial.core.server.service.ExportPlanner;
import com.epam.aidial.core.server.service.ExportRequestParser;
import com.epam.aidial.core.server.service.ExportRequestParser.ExportRequest;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
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
 * Admin-only export at {@code POST /v1/admin/export} of the Applications that the request body names, with the dependencies that they
 * need ({@link #exportSelection}). The export of whole buckets is {@code POST /v1/admin/system-export}, which this class does not touch.
 *
 * <p>{@link ExportArchive} is streamed as {@link ZipResponseStreamer} describes. The export holds one {@link ExportSlot} from the plan to the
 * last byte. A failure before the head gets a status.
 */
@Slf4j
public class AdminExportController implements Controller {

    /** The one cluster-wide slot of the selective export: the export holds it from the plan to the last byte. */
    public static final String SELECTION_SLOT = "export-selection";

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final AsyncTaskExecutor taskExecutor;
    private final Vertx vertx;
    private final ZipResponseStreamer streamer;
    private final AccessService accessService;
    private final Supplier<ExportPlanner> plannerSource;
    @Nullable
    private final BodyRedactor bodyRedactor;
    private final StreamSource streamSource;
    private final String coreVersion;
    @Nullable
    private final LockService lockService;
    private final ExportLimits limits;

    /** What the plan leaves for the next steps: the closure, and the slot that is still held. */
    private record Planned(Closure closure, ExportSlot slot) {
    }

    public AdminExportController(Proxy proxy, ProxyContext context) {
        this(context, proxy.getConfigAuthService(), proxy.getTaskExecutor(), proxy.getVertx(),
                Duration.ofMillis(proxy.getAdminExportSettings().getStallTimeout()), proxy.getAccessService(),
                () -> newPlanner(proxy), proxy.getExportBodyRedactor(), streamSourceOf(proxy), proxy.getVersion(),
                proxy.getLockService(), ExportLimits.of(proxy.getAdminExportSettings()));
    }

    AdminExportController(ProxyContext context, ConfigAuthorizationService authorizationService,
                          AsyncTaskExecutor taskExecutor, Vertx vertx, Duration stallTimeout, AccessService accessService,
                          Supplier<ExportPlanner> plannerSource, @Nullable BodyRedactor bodyRedactor, StreamSource streamSource,
                          String coreVersion, @Nullable LockService lockService, ExportLimits limits) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.taskExecutor = taskExecutor;
        this.vertx = vertx;
        this.streamer = new ZipResponseStreamer(vertx, taskExecutor, stallTimeout);
        this.accessService = accessService;
        this.plannerSource = plannerSource;
        this.bodyRedactor = bodyRedactor;
        this.streamSource = streamSource;
        this.coreVersion = coreVersion;
        this.lockService = lockService;
        this.limits = limits;
    }

    /** The bytes of Files and Skills are opened when the archive is written; JSON resources are already held by the closure. */
    private static StreamSource streamSourceOf(Proxy proxy) {
        return new StreamSource() {
            @Override
            public StoredStream openStream(ResourceDescriptor descriptor) throws IOException {
                ResourceService.ResourceStream stream = proxy.getResourceService().getResourceStream(descriptor, EtagHeader.ANY);
                return stream == null ? null : new StoredStream(stream.inputStream(), stream.etag(), stream.contentLength(), stream.contentType());
            }

            @Override
            public StoredStream openVersionFile(ResourceDescriptor skill, String versionId, String relativePath) throws IOException {
                ResourceService.ResourceStream stream = proxy.getComplexResourceService().getVersionFileStream(skill, versionId, relativePath);
                return stream == null ? null : new StoredStream(stream.inputStream(), stream.etag(), stream.contentLength(), stream.contentType());
            }

            @Override
            public String readMarkerEtag(ResourceDescriptor skill) {
                FolderResourceMarker marker = proxy.getComplexResourceService().getMarker(skill);
                return marker == null ? null : marker.getEtag();
            }

            @Override
            public String readEtag(ResourceDescriptor descriptor) {
                ResourceItemMetadata metadata = proxy.getResourceService().getResourceMetadata(descriptor);
                return metadata == null ? null : metadata.getEtag();
            }
        };
    }

    private static ExportPlanner newPlanner(Proxy proxy) {
        MergedConfigStore store = (MergedConfigStore) proxy.getConfigStore();
        return new ExportPlanner(proxy.getApiKeyStore().getMutationLock(), store::getInvalidEntities, store::get,
                store::getFileSourcedConfig, proxy.getResourceService(), proxy.getApplicationSchemaService(),
                proxy.getCatalogSchemaService(), proxy.getEncryptionService(), proxy.getExportBodyRedactor(),
                descriptor -> proxy.getComplexResourceService().getMarker(descriptor));
    }

    @Override
    @ApiOperation(
            method = "POST",
            path = "/v1/admin/export",
            operationId = "exportAdminApplications",
            tags = {"Admin"},
            requestBody = @ApiSchema(implementation = ExportRequestParser.ExportRequestBody.class),
            description = "Admin only (explicit admin rules; a per-request key is refused). Exports the selected Applications and their "
                    + "dependencies as a ZIP in the layout of the system export: the technical manifest first, the dependency registry second, "
                    + "each resource as redacted JSON at its storage path after its metadata entry, the resources of the file configuration "
                    + "as fragments, and a verification entry last. The export of whole buckets is POST /v1/admin/system-export. "
                    + "The request holds only `roots`. A dependency that is missing, unsupported, external or unresolved, and incomplete "
                    + "discovery, do not fail the export: the package is written and the registry lists each finding. See docs/admin-export.md.",
            responses = {
                    @ApiResponse(code = 200, description = "ZIP archive of the selected Applications and their dependencies",
                            body = @ApiSchema(implementation = byte[].class), contentTypes = {"application/zip"}),
                    @ApiResponse(code = 400, description = "The body is missing or is not a valid selection of Applications"),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404, description = "A selected Application does not exist"),
                    @ApiResponse(code = 409, description = "Another export of Applications is running (the one slot), retry later"),
                    @ApiResponse(code = 413, description = "A selective export exceeds a limit of the adminExport settings (resources, "
                            + "references, depth, size of one resource or total size); nothing is exported"),
                    @ApiResponse(code = 500),
                    @ApiResponse(code = 503, description = "No redactor is configured, so no body of a selection may be exported"),
                    @ApiResponse(code = 504, description = "A selective export did not finish within the deadline of the adminExport settings")
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

    /**
     * The selective mode: the body names the Applications to export. It validates the strict body, the
     * roots and the closure, all before any response byte. A request that passes is streamed as a ZIP through
     * {@link ZipResponseStreamer} with the {@link BodyRedactor} that production sets; without one (a test setup) it is answered 503 and no
     * body leaves the system. The export is bounded by the
     * {@link ExportLimits} (graph, retained bodies, deadline) and by one cluster-wide slot ({@link #SELECTION_SLOT}), and it
     * fails as a whole with a status when a bound is exceeded.
     */
    void exportSelection(Buffer body) {
        ExportRequest request;
        try {
            request = ExportRequestParser.parse(body.toString(StandardCharsets.UTF_8), limits.maxRoots());
        } catch (HttpException e) {
            context.respond(e);
            return;
        }
        ExportPlanner planner = plannerSource.get();
        ExportLimits.Deadline deadline = limits.startDeadline();
        AtomicReference<ExportSlot> takenSlot = new AtomicReference<>(ExportSlot.none());
        Future<Planned> planning;
        try {
            planning = taskExecutor.submit(() -> {
                ExportSlot slot = ExportSlot.acquire(lockService, SELECTION_SLOT);
                takenSlot.set(slot);
                DeadlineInterrupt interrupt = new DeadlineInterrupt(vertx, Thread.currentThread(), deadline);
                try {
                    return new Planned(planner.plan(request, this::canReadPublic, limits, deadline), slot);
                } finally {
                    interrupt.disarm();
                }
            });
        } catch (RuntimeException e) {
            // the executor refused the task before it ran, so no slot was taken
            context.respond(e, "Failed to prepare the export");
            return;
        }
        // a failed plan leaves its slot to a task of its own: the thread of the plan may have been interrupted
        planning.onSuccess(planned -> releasingSlotOnFailure(planned.slot(), () -> respondToSelection(planned, deadline)))
                .onFailure(error -> takenSlot.get().releaseThen(taskExecutor, () -> context.respond(error, "Failed to prepare the export")));
    }

    private void respondToSelection(Planned planned, ExportLimits.Deadline deadline) {
        Closure closure = planned.closure();
        if (bodyRedactor == null) {
            // no redaction policy is configured, so no body may leave the system
            planned.slot().releaseThen(taskExecutor, () -> context.respond(HttpStatus.SERVICE_UNAVAILABLE, "The export response is not available yet"));
            return;
        }
        streamSelection(closure, planned.slot(), deadline);
    }

    /**
     * The archive is prepared, with every body redacted, before the response head is written, so a failure there gets a
     * status and no byte of a ZIP. After that the archive is streamed as the full export is. The closure is not kept: the
     * archive holds only the redacted bytes.
     */
    private void streamSelection(Closure closure, ExportSlot slot, ExportLimits.Deadline deadline) {
        Instant start = Instant.now();
        Future<ExportArchive> preparation;
        try {
            preparation = taskExecutor.submit(() -> ExportArchive.prepare(closure, streamSource, coreVersion, start, deadline));
        } catch (RuntimeException e) {
            // the executor refused the task before it ran
            slot.releaseThen(taskExecutor, () -> context.respond(e, "Failed to prepare the export"));
            return;
        }
        preparation.onSuccess(archive -> releasingSlotOnFailure(slot, () -> streamer.stream(context, "dial-export", start, deadline, slot,
                        out -> writeArchive(archive, out, deadline),
                        error -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to export the Applications"))))
                .onFailure(error -> slot.releaseThen(taskExecutor, () -> context.respond(error, "Failed to prepare the export")));
    }

    /**
     * An exception thrown by a success handler of a {@link Future} is not routed to its failure handler, so a handler that fails after the slot
     * was taken (for example the response was already closed by the client) would keep the slot until its lease ends and every other export
     * would be answered 409. The release is single, so it does no harm when the streamer has already taken over the slot.
     */
    private void releasingSlotOnFailure(ExportSlot slot, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            slot.releaseThen(taskExecutor, () -> context.respond(e, "Failed to prepare the export"));
        }
    }

    private static void writeArchive(ExportArchive archive, OutputStream out, ExportLimits.Deadline deadline) {
        try {
            archive.writeTo(out, deadline);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean canReadPublic(ResourceDescriptor descriptor) {
        return accessService.lookupPermissions(Set.of(descriptor), context)
                .getOrDefault(descriptor, Set.of()).contains(ResourceAccessType.READ);
    }
}
