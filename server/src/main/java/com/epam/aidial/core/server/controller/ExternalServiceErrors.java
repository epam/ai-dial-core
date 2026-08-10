package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.credentials.exception.EncryptionException;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import jakarta.validation.ConstraintViolationException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * One error mapping for the external-service and offline-credentials endpoints, which each used to carry their own
 * copy. The copies drifted: the same rejected request body answered 400 on one endpoint and 500 on another.
 */
@Slf4j
@UtilityClass
public class ExternalServiceErrors {

    public static void respond(ProxyContext context, String message, Throwable error) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String body = message;

        switch (error) {
            case HttpException e -> {
                status = e.getStatus();
                body = e.getMessage();
            }
            case ResourceNotFoundException e -> {
                status = HttpStatus.NOT_FOUND;
                body = e.getMessage();
            }
            case PermissionDeniedException e -> {
                status = HttpStatus.FORBIDDEN;
                body = e.getMessage();
            }
            case IllegalArgumentException e -> {
                status = HttpStatus.BAD_REQUEST;
                body = e.getMessage();
            }
            case ConstraintViolationException e -> {
                status = HttpStatus.BAD_REQUEST;
                body = e.getMessage();
            }
            case EncryptionException ignored -> {
                // Never surface crypto internals to the caller.
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            case null, default -> {
                // Unrecognised: keep the generic message and report it as a server error.
            }
        }

        // Log server-side failures with the trace id; keep the client body generic.
        if (status.is5xx()) {
            log.warn("{} (trace_id={})", message, context.getTraceId(), error);
        }

        context.respond(status, body);
    }
}
