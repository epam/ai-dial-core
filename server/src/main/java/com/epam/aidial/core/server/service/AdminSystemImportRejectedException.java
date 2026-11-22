package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.Getter;

/**
 * The stage phase of a system import refused the archive: a bad manifest, a disallowed or malformed entry name, a
 * missing or mismatched sidecar, a corrupt or truncated ZIP, or a stalled upload. Nothing in {@code public/} or
 * {@code platform/} was changed, and the staged entries have already been (or are about to be) removed.
 */
@Getter
public class AdminSystemImportRejectedException extends RuntimeException {

    private final HttpStatus status;

    public AdminSystemImportRejectedException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
