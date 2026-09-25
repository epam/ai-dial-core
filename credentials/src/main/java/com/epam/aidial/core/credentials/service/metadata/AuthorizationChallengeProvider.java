package com.epam.aidial.core.credentials.service.metadata;

import java.util.Optional;

/**
 * Obtains the authorization challenge an MCP server answers an unauthenticated client with.
 *
 * <p>Discovery reads the {@code resource_metadata} pointer out of that challenge. The challenge has
 * to come from a genuine MCP request - the same one a client connecting to the server would send -
 * because a server only issues it to a request it recognises: anything else is rejected as
 * malformed, and a rejection carries no pointer.
 */
@FunctionalInterface
public interface AuthorizationChallengeProvider {

    /**
     * @param resourceEndpoint the MCP endpoint to challenge
     * @return the {@code WWW-Authenticate} value of the 401 the endpoint answers with, or empty when it
     *         does not challenge - it let the request in, refused it for another reason, or did not answer
     */
    Optional<String> challenge(String resourceEndpoint);
}
