package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.consent.AcceptConsentRequest;
import com.epam.aidial.core.server.data.consent.AdminConsentStatus;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.consent.ConsentGrant;
import com.epam.aidial.core.server.data.consent.ReviewConsentResponse;
import com.epam.aidial.core.server.log.ResourceDependencyAuditLog;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Supplier;

@AllArgsConstructor
@Slf4j
public class ConsentController {

    private ProxyContext context;
    private Proxy proxy;

    @ApiOperation(
            method = "GET",
            path = "/v1/consent/{deployment_id}",
            operationId = "requestUserConsent",
            tags = {"User Consent"},
            parameters = {
                    @ApiParameter(name = "deployment_id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ReviewConsentResponse.class)),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> requestConsent(String deploymentId) {
        proxy.getTaskExecutor().submit(() -> proxy.getConsentService().buildConsent(context, deploymentId)).onSuccess(consent -> {
            context.respond(HttpStatus.OK, consent);
        }).onFailure(error -> handleRequestError(deploymentId, error));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/consent/{deployment_id}",
            operationId = "acceptUserConsent",
            requestBody = @ApiSchema(implementation = AcceptConsentRequest.class),
            tags = {"User Consent"},
            parameters = {
                    @ApiParameter(name = "deployment_id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success"),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> acceptConsent(String deploymentId) {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    AcceptConsentRequest request;
                    try {
                        request = ProxyUtil.convertToObject(buffer, AcceptConsentRequest.class);
                    } catch (Exception e) {
                        log.warn("Invalid request body provided", e);
                        context.respond(HttpStatus.BAD_REQUEST, "Invalid request body provided");
                        return Future.succeededFuture();
                    }

                    if (request == null || request.getConsent() == null) {
                        context.respond(HttpStatus.BAD_REQUEST, "User consent is missed");
                        return Future.succeededFuture();
                    }

                    return proxy.getTaskExecutor().submit(() -> {
                        proxy.getConsentService().acceptConsent(context, deploymentId, request.getConsent());
                        return null;
                    });
                })
                .onSuccess(ignored -> context.respond(HttpStatus.OK))
                .onFailure(error -> handleRequestError(deploymentId, error));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/consent/{deployment_id}/admin-consent",
            operationId = "getApplicationAdminConsentStatus",
            tags = {"User Consent"},
            parameters = {
                    @ApiParameter(name = "deployment_id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_ID)
            },
            responses = {
                    @ApiResponse(code = 200,
                            description = "Success — consented (live right now: grant exists AND matches the "
                                    + "current declaration, exactly what request-time resolution enforces), "
                                    + "stale, grantedBy, grantedAt, grantedResources (present when a grant exists, "
                                    + "including the stale case)",
                            body = @ApiSchema(implementation = AdminConsentStatus.class)),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> getAdminConsentStatus(String deploymentId) {
        // Not audited: reads are not consent decisions (no read path in the codebase audits);
        // the gate runs before any resolution, so a refusal leaks nothing.
        proxy.getTaskExecutor().submit(() -> {
            requireAdmin();
            return proxy.getConsentService().describeAdminConsent(context, deploymentId);
        }).onSuccess(status -> context.respond(HttpStatus.OK, status))
                .onFailure(error -> handleRequestError(deploymentId, error));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/consent/{deployment_id}/admin-consent",
            operationId = "grantApplicationAdminConsent",
            tags = {"User Consent"},
            parameters = {
                    @ApiParameter(name = "deployment_id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success"),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> grantAdminConsent(String deploymentId) {
        return adminConsentOperation(deploymentId, "GRANT",
                () -> proxy.getConsentService().grantAdminConsent(context, deploymentId));
    }

    @ApiOperation(
            method = "DELETE",
            path = "/v1/consent/{deployment_id}/admin-consent",
            operationId = "withdrawApplicationAdminConsent",
            tags = {"User Consent"},
            parameters = {
                    @ApiParameter(name = "deployment_id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success"),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> withdrawAdminConsent(String deploymentId) {
        return adminConsentOperation(deploymentId, "WITHDRAW",
                () -> proxy.getConsentService().withdrawAdminConsent(context, deploymentId));
    }

    /**
     * Both admin-consent mutations are the same act with a different verb: admin only (checked
     * before any resolution, so a refusal leaks nothing), audited either way — the grant line
     * carries the approved snapshot, the withdraw line what was withdrawn.
     */
    private Future<?> adminConsentOperation(String deploymentId, String action, Supplier<ConsentGrant> operation) {
        proxy.getTaskExecutor().submit(() -> {
            requireAdmin();
            return operation.get();
        })
                .onComplete(result -> ResourceDependencyAuditLog.consent(context, deploymentId, action,
                        result.succeeded() ? snapshotOf(result.result()) : null, asRuntime(result.cause())))
                .onSuccess(ignored -> context.respond(HttpStatus.OK))
                .onFailure(error -> handleRequestError(deploymentId, error));
        return Future.succeededFuture();
    }

    private static List<Consent.ResourceEntry> snapshotOf(ConsentGrant grant) {
        return grant == null || grant.getConsent() == null ? null : grant.getConsent().getResources();
    }

    private static RuntimeException asRuntime(Throwable error) {
        if (error == null) {
            return null;
        }
        return error instanceof RuntimeException runtimeError ? runtimeError : new RuntimeException(error);
    }

    private void requireAdmin() {
        // Fail-closed, unlike ResourceController's hasAdminAccess: this endpoint mints an app-level
        // consent that reaches every user, the same class of power the platform-bucket admin API
        // gates with hasExplicitAdminAccess (empty/unconfigured admin rules deny, not allow-all).
        if (!proxy.getAccessService().hasExplicitAdminAccess(context)) {
            throw new PermissionDeniedException("Only administrators may consent to application resource dependencies");
        }
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            log.warn("Forbidden deployment {}", deploymentId);
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            log.warn("Deployment not found {}", deploymentId, error);
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else if (error instanceof HttpException httpException) {
            log.warn("Admin consent rejected for deployment {} status={}", deploymentId, httpException.getStatus());
            context.respond(httpException);
        } else {
            log.error("Failed to process user consent", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to process user consent for deployment=%s".formatted(deploymentId));
        }
    }

}