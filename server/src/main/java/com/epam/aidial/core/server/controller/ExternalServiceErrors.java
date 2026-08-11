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

/** One error mapping for the external-service and offline-credentials endpoints, which each had their own. */
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
            case null, default -> { }
        }

        // Log server-side failures with the trace id; keep the client body generic.
        if (status.is5xx()) {
            log.warn("{} (trace_id={})", message, context.getTraceId(), error);
        }

        context.respond(status, body);
    }

    /** Adapts a future's cause to what the audit log accepts, which classifies by exception type. */
    public static RuntimeException asRuntime(Throwable error) {
        if (error == null) {
            return null;
        }
        return error instanceof RuntimeException e ? e : new IllegalStateException(error);
    }
}
