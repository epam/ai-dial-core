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
import com.epam.aidial.core.server.data.consent.GrantAdminConsentRequest;
import com.epam.aidial.core.server.data.consent.ReviewConsentResponse;
import com.epam.aidial.core.server.log.ResourceDependencyAuditLog;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
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
            path = "/v1/admin-consent/{deployment_id}",
            operationId = "getApplicationAdminConsentStatus",
            tags = {"Admin Consent"},
            parameters = {
                    @ApiParameter(name = "deployment_id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = AdminConsentStatus.class)),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> getAdminConsentStatus(String deploymentId) {
        asAdmin(() -> proxy.getConsentService().describeAdminConsent(context, deploymentId))
                .onSuccess(status -> context.respond(HttpStatus.OK, status))
                .onFailure(error -> handleAdminConsentError(deploymentId, error));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/admin-consent/{deployment_id}",
            operationId = "grantApplicationAdminConsent",
            requestBody = @ApiSchema(implementation = GrantAdminConsentRequest.class),
            tags = {"Admin Consent"},
            parameters = {
                    @ApiParameter(name = "deployment_id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success"),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 409),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> grantAdminConsent(String deploymentId) {
        context.getRequest().body()
                .onSuccess(body -> adminConsentOperation(deploymentId, AdminConsentAction.GRANT,
                        () -> proxy.getConsentService().grantAdminConsent(context, deploymentId, reviewedResources(body))))
                .onFailure(error -> handleAdminConsentError(deploymentId, error));
        return Future.succeededFuture();
    }

    private static List<Consent.ResourceEntry> reviewedResources(Buffer body) {
        GrantAdminConsentRequest request;
        try {
            request = ProxyUtil.convertToObject(body, GrantAdminConsentRequest.class);
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid request body provided");
        }
        if (request == null || request.getResources() == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "The reviewed resources are missing");
        }
        return request.getResources();
    }

    @ApiOperation(
            method = "DELETE",
            path = "/v1/admin-consent/{deployment_id}",
            operationId = "withdrawApplicationAdminConsent",
            tags = {"Admin Consent"},
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
        return adminConsentOperation(deploymentId, AdminConsentAction.WITHDRAW,
                () -> proxy.getConsentService().withdrawAdminConsent(context, deploymentId));
    }

    /** What an administrator did to an application's approval; the audit line carries the name. */
    private enum AdminConsentAction {
        GRANT, WITHDRAW
    }

    /** Audited on success and on failure. */
    private Future<?> adminConsentOperation(String deploymentId, AdminConsentAction action, Supplier<ConsentGrant> operation) {
        asAdmin(operation)
                .onComplete(result -> ResourceDependencyAuditLog.consent(context, deploymentId, action.name(),
                        result.succeeded() ? snapshotOf(result.result()) : null, result.cause()))
                .onSuccess(ignored -> context.respond(HttpStatus.OK))
                .onFailure(error -> handleAdminConsentError(deploymentId, error));
        return Future.succeededFuture();
    }

    private static List<Consent.ResourceEntry> snapshotOf(ConsentGrant grant) {
        return grant == null || grant.getConsent() == null ? null : grant.getConsent().getResources();
    }

    /** Admin only, checked before the deployment is resolved. */
    private <T> Future<T> asAdmin(Supplier<T> operation) {
        return proxy.getTaskExecutor().submit(() -> {
            requireAdmin();
            return operation.get();
        });
    }

    private void requireAdmin() {
        // fail-closed: with no admin rules configured nobody is an administrator here
        if (!proxy.getAccessService().hasExplicitAdminAccess(context)) {
            throw new PermissionDeniedException("Only administrators may manage application admin consent");
        }
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        handleConsentError("user", deploymentId, error);
    }

    private void handleAdminConsentError(String deploymentId, Throwable error) {
        if (error instanceof HttpException httpException) {
            log.warn("Admin consent rejected for deployment {} status={}", deploymentId, httpException.getStatus());
            context.respond(httpException);
        } else if (error instanceof IllegalArgumentException) {
            log.warn("Invalid admin consent request for deployment {}: {}", deploymentId, error.getMessage());
            context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
        } else {
            handleConsentError("admin", deploymentId, error);
        }
    }

    private void handleConsentError(String kind, String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            log.warn("Forbidden deployment {}", deploymentId);
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            log.warn("Deployment not found {}", deploymentId, error);
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else {
            log.error("Failed to process {} consent", kind, error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to process %s consent for deployment=%s".formatted(kind, deploymentId));
        }
    }

}
