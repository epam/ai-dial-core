package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiExtension;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.dependency.AdminAccessPolicy;
import com.epam.aidial.core.server.service.dependency.ConfigSnapshotReader;
import com.epam.aidial.core.server.service.dependency.DependencyAccessPolicy;
import com.epam.aidial.core.server.service.dependency.DependencyListResponse;
import com.epam.aidial.core.server.service.dependency.DependencyRequestParser;
import com.epam.aidial.core.server.service.dependency.DependencyRequestParser.DependencyRequest;
import com.epam.aidial.core.server.service.dependency.DependencyWalker;
import com.epam.aidial.core.server.service.dependency.UserAccessPolicy;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code POST /v1/ops/dependency/list}: for each Application that the body names, the flat list of the resources that it reaches.
 *
 * <p>Every authenticated caller is served and the policy decides what the answer holds. A caller that holds a per-request key (an
 * application, not a person) is refused before the body is read. Every status is decided before the body of the answer.
 */
@Slf4j
public class DependencyListController implements Controller {

    private final ProxyContext context;
    private final ConfigAuthorizationService authorizationService;
    private final AsyncTaskExecutor taskExecutor;
    private final EncryptionService encryptionService;
    private final AccessService accessService;
    private final Supplier<DependencyWalker> walkerSource;

    public DependencyListController(Proxy proxy, ProxyContext context) {
        this(context, proxy.getConfigAuthService(), proxy.getTaskExecutor(), proxy.getEncryptionService(), proxy.getAccessService(),
                () -> newWalker(proxy));
    }

    DependencyListController(ProxyContext context, ConfigAuthorizationService authorizationService, AsyncTaskExecutor taskExecutor,
                             EncryptionService encryptionService, AccessService accessService, Supplier<DependencyWalker> walkerSource) {
        this.context = context;
        this.authorizationService = authorizationService;
        this.taskExecutor = taskExecutor;
        this.encryptionService = encryptionService;
        this.accessService = accessService;
        this.walkerSource = walkerSource;
    }

    private static DependencyWalker newWalker(Proxy proxy) {
        ConfigSnapshotReader snapshots = new ConfigSnapshotReader(proxy.getApiKeyStore(), (MergedConfigStore) proxy.getConfigStore());
        return new DependencyWalker(snapshots, proxy.getResourceService(), proxy.getApplicationSchemaService(),
                proxy.getCatalogSchemaService(), proxy.getEncryptionService(), proxy.getComplexResourceService(), proxy.getPlatformSchemaScan());
    }

    @Override
    @ApiOperation(
            method = "POST",
            path = "/v1/ops/dependency/list",
            operationId = "listDependencies",
            tags = {"Dependencies"},
            requestBody = @ApiSchema(implementation = DependencyRequestParser.DependencyRequestBody.class),
            responses = {
                    @ApiResponse(code = 200, description = "For each selected Application, the flat list of the resources it reaches",
                            body = @ApiSchema(implementation = DependencyListResponse.class), contentTypes = {"application/json"}),
                    @ApiResponse(code = 400, description = "The body is missing, holds a field other than roots, or is not a valid selection of Applications"),
                    @ApiResponse(code = 403, description = "The caller may not use a selected root, or authenticates with a per-request key"),
                    @ApiResponse(code = 404, description = "A selected Application does not exist"),
                    @ApiResponse(code = 500)
            },
            extensions = {
                    @ApiExtension(name = "x-preview", value = "true")
            }
    )
    public Future<?> handle() {
        if (context.getApiKeyData().getPerRequestKey() != null) {
            context.respond(HttpStatus.FORBIDDEN, "Forbidden");
            return Future.succeededFuture();
        }
        context.getRequest().body()
                .recover(error -> Future.failedFuture(new HttpException(HttpStatus.BAD_REQUEST, "Failed to read the request body")))
                .compose(this::list)
                .onSuccess(response -> context.respond(HttpStatus.OK, response))
                .onFailure(error -> context.respond(error, "Failed to build the dependency list"));
        return Future.succeededFuture();
    }

    private Future<DependencyListResponse> list(Buffer body) {
        DependencyRequest request = DependencyRequestParser.parse(body.toString(StandardCharsets.UTF_8), encryptionService);
        boolean isAdmin = authorizationService.isAdmin(context);
        DependencyWalker walker = walkerSource.get();
        return taskExecutor.submit(() -> buildList(walker, request, isAdmin));
    }

    /** Checks the roots, then walks; a root that does not exist ends the walk with 404. It blocks, so it runs on a task thread. */
    private DependencyListResponse buildList(DependencyWalker walker, DependencyRequest request, boolean isAdmin) {
        DependencyAccessPolicy policy = isAdmin ? new AdminAccessPolicy() : newUserPolicy();
        List<ResourceDescriptor> roots = request.roots();
        Set<ResourceDescriptor> allowed = policy.selectAllowedRoots(new LinkedHashSet<>(roots));
        for (int i = 0; i < roots.size(); i++) {
            if (!allowed.contains(roots.get(i))) {
                // an admin is told what is wrong with the root; any other caller is told nothing that depends on whether it exists
                throw isAdmin ? new HttpException(HttpStatus.BAD_REQUEST, "roots[" + i + "] must be an Application")
                        : new HttpException(HttpStatus.FORBIDDEN, "Forbidden");
            }
        }
        return walker.walk(roots, policy);
    }

    private UserAccessPolicy newUserPolicy() {
        return new UserAccessPolicy(targets -> accessService.lookupInitiatorPermissions(targets, context), context.getUserRoles());
    }
}
