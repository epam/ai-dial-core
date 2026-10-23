package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiOperations;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.InvalidEntityException;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.security.EntityBucketBinding;
import com.epam.aidial.core.server.security.Operation;
import com.epam.aidial.core.server.service.policy.PolicyRecordCodec;
import com.epam.aidial.core.server.service.policy.PolicyService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.charset.StandardCharsets;

/**
 * Serves {@code /v1/policies/platform/[<costObject>/]<name>} and
 * {@code /v1/policy_assignments/platform/<costObject>/<id>}: the same Configuration-API verbs as
 * {@link ConfigResourceController} (GET, PUT with {@code If-Match}/{@code If-None-Match}, DELETE; POST is
 * 405), admin-only, but over a path with one folder level. A write also updates the merged config of
 * this pod; the other pods follow through the replica event. Listings by folder prefix go through
 * {@link ConfigResourceMetadataController}.
 */
@Slf4j
public class PolicyController implements Controller {

    private static final String ALLOW_HEADER = "GET, PUT, DELETE";

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final ResourceService resourceService;
    private final AsyncTaskExecutor taskExecutor;
    private final LockService lockService;
    private final MergedConfigStore mergedConfigStore;
    private final PolicyService policyService;
    private final ResourceTypes type;
    private final String bucket;
    private final String path;

    public PolicyController(Proxy proxy, ProxyContext context, String entityType, String bucket, String path) {
        this.context = context;
        this.authorizationService = proxy.getConfigAuthService();
        this.resourceService = proxy.getResourceService();
        this.taskExecutor = proxy.getTaskExecutor();
        this.lockService = proxy.getLockService();
        this.mergedConfigStore = (MergedConfigStore) proxy.getConfigStore();
        this.policyService = new PolicyService(resourceService);
        this.type = ResourceTypes.of(entityType);
        this.bucket = bucket;
        this.path = path;
    }

    public static boolean serves(String entityType) {
        return ResourceTypes.POLICY.group().equals(entityType) || ResourceTypes.POLICY_ASSIGNMENT.group().equals(entityType);
    }

    @Override
    @ApiOperations({
            @ApiOperation(
                    method = "GET",
                    path = "/v1/policies/{bucket}/{path}",
                    operationId = "getPolicy",
                    tags = {"Policies"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true,
                                    description = "Policy name, with the folder of its cost object for a project policy: [<costObject>/]<name>"),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "ETag of the cached copy; answers 304 when it is still current")
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = Policy.class),
                                    headers = {@ApiHeader(name = "ETag", description = "Entity tag for the policy", required = true)}),
                            @ApiResponse(code = 304),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 500)
                    },
                    extensions = {@ApiExtension(name = "x-preview", value = "true")}
            ),
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/policies/{bucket}/{path}",
                    operationId = "savePolicy",
                    requestBody = @ApiSchema(implementation = Policy.class),
                    tags = {"Policies"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true,
                                    description = "Policy name, with the folder of its cost object for a project policy: [<costObject>/]<name>"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ConfigResourceController.ConfigWriteResponse.class),
                                    headers = {@ApiHeader(name = "ETag", description = "Entity tag for the saved policy", required = true)}),
                            @ApiResponse(code = 400, description = "Malformed path or JSON, or a body that does not fit the record schema: an unknown field, a wrong type"),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 409, description = "The policy would tie in priority with another budget of a cost object, "
                                    + "or its name is taken by an assignment"),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 422, description = "The policy breaks a validation rule"),
                            @ApiResponse(code = 500)
                    },
                    extensions = {@ApiExtension(name = "x-preview", value = "true")}
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/policies/{bucket}/{path}",
                    operationId = "deletePolicy",
                    tags = {"Policies"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true,
                                    description = "Policy name, with the folder of its cost object for a project policy: [<costObject>/]<name>"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 204, description = "Success"),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 409, description = "An assignment references the policy"),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 500)
                    },
                    extensions = {@ApiExtension(name = "x-preview", value = "true")}
            ),
            @ApiOperation(
                    method = "GET",
                    path = "/v1/policy_assignments/{bucket}/{path}",
                    operationId = "getPolicyAssignment",
                    tags = {"Policy Assignments"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Assignment path: <costObject>/<id>"),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = "ETag of the cached copy; answers 304 when it is still current")
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = PolicyAssignment.class),
                                    headers = {@ApiHeader(name = "ETag", description = "Entity tag for the assignment", required = true)}),
                            @ApiResponse(code = 304),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 500)
                    },
                    extensions = {@ApiExtension(name = "x-preview", value = "true")}
            ),
            @ApiOperation(
                    method = "PUT",
                    path = "/v1/policy_assignments/{bucket}/{path}",
                    operationId = "savePolicyAssignment",
                    requestBody = @ApiSchema(implementation = PolicyAssignment.class),
                    tags = {"Policy Assignments"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Assignment path: <costObject>/<id>"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH),
                            @ApiParameter(name = "If-None-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_NONE_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ConfigResourceController.ConfigWriteResponse.class),
                                    headers = {@ApiHeader(name = "ETag", description = "Entity tag for the saved assignment", required = true)}),
                            @ApiResponse(code = 400, description = "Malformed path or JSON, or a body that does not fit the record schema: an unknown field, a wrong type"),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 409, description = "The assignment would tie in priority with another budget of the cost object, "
                                    + "or its id is taken by a self-applying policy"),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 422, description = "The assignment breaks a validation rule"),
                            @ApiResponse(code = 500)
                    },
                    extensions = {@ApiExtension(name = "x-preview", value = "true")}
            ),
            @ApiOperation(
                    method = "DELETE",
                    path = "/v1/policy_assignments/{bucket}/{path}",
                    operationId = "deletePolicyAssignment",
                    tags = {"Policy Assignments"},
                    parameters = {
                            @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true, description = OpenApiDescriptions.BUCKET),
                            @ApiParameter(name = "path", in = ParameterIn.PATH, required = true, description = "Assignment path: <costObject>/<id>"),
                            @ApiParameter(name = "If-Match", in = ParameterIn.HEADER, description = OpenApiDescriptions.IF_MATCH)
                    },
                    responses = {
                            @ApiResponse(code = 204, description = "Success"),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 412),
                            @ApiResponse(code = 500)
                    },
                    extensions = {@ApiExtension(name = "x-preview", value = "true")}
            )
    })
    public Future<?> handle() {
        if (!EntityBucketBinding.isAllowed(type.group(), bucket)) {
            // Body-less 404, indistinguishable from "not found", as on ConfigResourceController.
            context.respond(HttpStatus.NOT_FOUND);
            return Future.succeededFuture();
        }

        HttpMethod method = context.getRequest().method();
        Operation operation = method == HttpMethod.GET || method == HttpMethod.HEAD ? Operation.READ : Operation.WRITE;
        if (!authorizationService.isAuthorized(context, type.group(), path, bucket, operation)) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }

        if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
            return handleGet();
        }
        if (method == HttpMethod.PUT) {
            return handlePut();
        }
        if (method == HttpMethod.DELETE) {
            return handleDelete();
        }
        context.putHeader("Allow", ALLOW_HEADER);
        context.respond(HttpStatus.METHOD_NOT_ALLOWED, "Not implemented");
        return Future.succeededFuture();
    }

    private Future<?> handleGet() {
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        taskExecutor.submit(() -> {
            ResourceDescriptor descriptor = PolicyService.describe(type, path);
            // validates the conditional header itself: a 304 or 412 is thrown before the body is read
            Pair<ResourceItemMetadata, String> stored = resourceService.getResourceWithMetadata(descriptor, etag);
            if (stored == null) {
                return null;
            }
            ObjectNode record = (ObjectNode) PolicyRecordCodec.toTree(type == ResourceTypes.POLICY
                    ? PolicyRecordCodec.readPolicy(stored.getValue())
                    : PolicyRecordCodec.readAssignment(stored.getValue()));
            return Pair.of(stored.getKey().getEtag(), record);
        }).onSuccess(result -> {
            if (result == null) {
                context.respond(HttpStatus.NOT_FOUND);
            } else {
                context.putHeader(HttpHeaders.ETAG, result.getKey()).respond(HttpStatus.OK, result.getValue());
            }
        }).onFailure(this::handleError);
        return Future.succeededFuture();
    }

    private Future<?> handlePut() {
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        String author = context.getUserDisplayName();
        context.getRequest().body().compose(body -> {
            JsonNode request = parseBody(body);
            return taskExecutor.submit(() -> lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, () -> {
                // describe() before parsing the body: a malformed path is the more basic error
                PolicyService.describe(type, path);
                String mapKey = MergedConfigStore.resolveMapKeyFor(PolicyService.describe(type, path));
                if (type == ResourceTypes.POLICY) {
                    Policy policy = PolicyRecordCodec.parsePolicy(request);
                    ResourceItemMetadata meta = policyService.putPolicy(path, policy, etag, author);
                    applyStoredWrite(mapKey, policy);
                    return meta;
                }
                PolicyAssignment assignment = PolicyRecordCodec.parseAssignment(request);
                ResourceItemMetadata meta = policyService.putAssignment(path, assignment, etag, author);
                applyStoredWrite(mapKey, assignment);
                return meta;
            }));
        }).onSuccess(meta -> {
            ObjectNode response = ProxyUtil.MAPPER.createObjectNode();
            response.put("name", path);
            context.putHeader(HttpHeaders.ETAG, meta.getEtag()).respond(HttpStatus.OK, response);
        }).onFailure(this::handleError);
        return Future.succeededFuture();
    }

    /**
     * Applies a record already stored to the merged config of this pod. The in-memory config can lag behind
     * storage, for example when the policy of an assignment was written by another pod whose replica event has
     * not arrived yet, and then the write is rejected. The stored record wins: the request still succeeds and
     * the pod catches up with a full rebuild, since it ignores the replica event of its own write.
     */
    private void applyStoredWrite(String mapKey, Object record) {
        try {
            mergedConfigStore.applyEntityWrite(type, mapKey, record);
        } catch (InvalidEntityException e) {
            log.warn("Stored {} '{}', but the merged config of this pod rejected it, requesting a full rebuild: {}",
                    type.urlSegment(), mapKey, e.getMessage());
            mergedConfigStore.requestRebuild();
        }
    }

    private Future<?> handleDelete() {
        EtagHeader etag = ProxyUtil.etag(context.getRequest());
        taskExecutor.submit(() -> lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, () -> {
            boolean deleted = type == ResourceTypes.POLICY
                    ? policyService.deletePolicy(path, etag)
                    : policyService.deleteAssignment(path, etag);
            if (!deleted) {
                throw new HttpException(HttpStatus.NOT_FOUND, "Resource not found: " + path);
            }
            mergedConfigStore.applyEntityDelete(type, MergedConfigStore.resolveMapKeyFor(PolicyService.describe(type, path)));
            return true;
        })).onSuccess(deleted -> context.respond(HttpStatus.NO_CONTENT)).onFailure(this::handleError);
        return Future.succeededFuture();
    }

    private static JsonNode parseBody(Buffer body) {
        String text = body == null ? "" : body.toString(StandardCharsets.UTF_8);
        if (text.isBlank()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Request body must not be empty");
        }
        try {
            return PolicyRecordCodec.readTree(text);
        } catch (JsonProcessingException e) {
            String location = e.getLocation() == null
                    ? "unknown location"
                    : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
            throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid JSON at " + location);
        }
    }

    private void handleError(Throwable error) {
        if (error instanceof HttpException exception) {
            context.respond(exception);
        } else if (error instanceof IllegalArgumentException exception) {
            context.respond(HttpStatus.BAD_REQUEST, exception.getMessage());
        } else {
            log.warn("Can't process policy record: {}", path, error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        }
    }
}
