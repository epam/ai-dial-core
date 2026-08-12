package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;

/** The owner has no usable offline credentials — permanent until they connect again, unlike an IdP outage. */
public class OfflineCredentialsRequiredException extends HttpException {

    public OfflineCredentialsRequiredException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
