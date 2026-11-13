package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.AdminSystemExportBusyException;
import com.epam.aidial.core.server.service.AdminSystemExportService;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Admin-only system export of the {@code public} and {@code platform} buckets at {@code POST /v1/admin/system-export}.
 * The optional {@code scope} query parameter selects the buckets, see {@link AdminSystemExportService#resolveBucketLocations}.
 *
 * <p>The archive is streamed as {@link ZipResponseStreamer} describes; a failure before the head, such as a {@code 409} on a held bucket
 * lock, gets a status.
 */
public class AdminSystemExportController implements Controller {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final AdminSystemExportService exportService;
    private final ZipResponseStreamer streamer;

    public AdminSystemExportController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.authorizationService = proxy.getConfigAuthService();
        this.exportService = proxy.getAdminSystemExportService();
        this.streamer = new ZipResponseStreamer(proxy.getVertx(), proxy.getTaskExecutor(),
                Duration.ofMillis(proxy.getAdminSystemExportSettings().getStallTimeout()));
    }

    @Override
    @ApiOperation(
            method = "POST",
            path = "/v1/admin/system-export",
            operationId = "systemExport",
            tags = {"Admin"},
            parameters = {
                    @ApiParameter(name = "scope", in = ParameterIn.QUERY,
                            description = "Comma-separated, case-insensitive list of the buckets to export: public, platform. "
                                    + "May be repeated, the lists are combined. All buckets when omitted.")
            },
            responses = {
                    @ApiResponse(code = 200, description = "ZIP archive of the public and platform buckets",
                            body = @ApiSchema(implementation = byte[].class), contentTypes = {"application/zip"}),
                    @ApiResponse(code = 400, description = "The scope is empty, has an empty element or names an unknown bucket"),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 409, description = "Another system export or a write holds a bucket lock, retry later"),
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

        List<String> bucketLocations;
        try {
            bucketLocations = AdminSystemExportService.resolveBucketLocations(context.getRequest().params().getAll("scope"));
        } catch (IllegalArgumentException e) {
            context.respond(HttpStatus.BAD_REQUEST, e.getMessage());
            return Future.succeededFuture();
        }

        Instant start = Instant.now();
        streamer.stream(context, "dial-system-export", start, null, out -> exportService.export(out, start, bucketLocations), error -> {
            if (error instanceof AdminSystemExportBusyException) {
                context.respond(HttpStatus.CONFLICT, error.getMessage());
            } else {
                context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to run the system export");
            }
        });

        return Future.succeededFuture();
    }
}
