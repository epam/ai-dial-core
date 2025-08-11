package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ToolSetSignInRequest;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.service.toolset.credentials.ToolSetCredentialsService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;

import static com.epam.aidial.core.storage.http.HttpStatus.BAD_REQUEST;

@Slf4j
public class ToolSetCredentialsController extends AccessControlBaseController {

    private final Vertx vertx;
    private final ToolSetCredentialsService toolsetCredentialsService;

    public ToolSetCredentialsController(Proxy proxy, ProxyContext context) {
        // TODO: which permissions do we need?
        super(proxy, context, !HttpMethod.GET.equals(context.getRequest().method()));
        this.vertx = context.getProxy().getVertx();
        this.toolsetCredentialsService = context.getProxy().getToolsetCredentialsService();
    }

    @Override
    protected Future<?> handle(ResourceDescriptor descriptor, boolean hasWriteAccess) {
        if (descriptor.getType() != ResourceTypes.TOOL_SET) {
            return context.respond(BAD_REQUEST, "Resource type not allowed: " + descriptor.getType());
        }

        if (descriptor.isFolder()) {
            return context.respond(BAD_REQUEST, "Folder not allowed: " + descriptor.getUrl());
        }

        if (!ResourceDescriptorFactory.isValidResourcePath(descriptor)) {
            return context.respond(BAD_REQUEST, "Resource name and/or parent folders must not end with .(dot)");
        }

        //TODO: add size validation?

        if (context.getRequest().method() == HttpMethod.GET) {
            return getToolSetCredentials(descriptor);
        }

        if (context.getRequest().method() == HttpMethod.POST) {
            return createToolSetCredentials(descriptor);
        }

        return null;
    }

    // TODO: should not return full object with secrets
    public Future<?> createToolSetCredentials(ResourceDescriptor descriptor) {
        context.getRequest()
            .body()
            .compose(body -> {
                ToolSetSignInRequest toolSetSignInRequest = ProxyUtil.convertToObject(body, ToolSetSignInRequest.class);
                return vertx.executeBlocking(() -> toolsetCredentialsService.createToolsetCredentials(descriptor, toolSetSignInRequest));
            })
            .onSuccess(toolsetCredentials -> context.respond(HttpStatus.OK, toolsetCredentials))
            .onFailure(error -> respondError("Can't create publication", error));

        return Future.succeededFuture();
    }

    private Future<ToolSetCredentials> getToolSetCredentials(ResourceDescriptor descriptor) {
        return vertx.executeBlocking(() -> {
            ToolSetCredentials toolSetCredentials = toolsetCredentialsService.getToolSetCredentials(descriptor);

            if (toolSetCredentials == null) {
                throw new ResourceNotFoundException();
            }

            return toolSetCredentials;
        }, false);
    }

    private void respondError(String message, Throwable error) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String body = null;

        if (error instanceof HttpException e) {
            status = e.getStatus();
            body = e.getMessage();
        } else if (error instanceof ResourceNotFoundException) {
            status = HttpStatus.NOT_FOUND;
            body = error.getMessage();
        } else if (error instanceof IllegalArgumentException e) {
            status = HttpStatus.BAD_REQUEST;
            body = e.getMessage();
        } else if (error instanceof PermissionDeniedException e) {
            status = HttpStatus.FORBIDDEN;
            body = e.getMessage();
        } else {
            log.warn(message, error);
        }

        context.respond(status, body);
    }
}
