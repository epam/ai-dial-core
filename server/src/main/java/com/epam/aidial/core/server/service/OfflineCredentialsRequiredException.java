package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;

/**
 * The owner has no usable offline credentials, so nothing can act on their behalf. Distinct from a transient
 * failure to reach the identity provider: this is permanent until the user connects again.
 */
public class OfflineCredentialsRequiredException extends HttpException {

    public OfflineCredentialsRequiredException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
