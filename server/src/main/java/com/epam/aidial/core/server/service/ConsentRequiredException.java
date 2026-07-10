package com.epam.aidial.core.server.service;

/**
 * A denial caused by the owner not having granted offline-usage consent (still a 403), distinct from an
 * identity/authorization mismatch. Lets the OBO audit stream separate routine "not consented yet" denials
 * from security-relevant identity-mismatch denials.
 */
public class ConsentRequiredException extends PermissionDeniedException {

    public ConsentRequiredException(String message) {
        super(message);
    }
}
