package com.epam.aidial.core.credentials.service.registration;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** What a client registration may take from the resource itself rather than from the operator. */
@Getter
@RequiredArgsConstructor
public enum RegistrationPolicy {

    TOOLSET(true, true),

    /**
     * An external service's endpoint may be the authorization server itself, and anyone who may write the service
     * would otherwise get a redirect of their choosing registered under Core's client.
     */
    EXTERNAL_SERVICE(false, false);

    /**
     * A dynamic registration uses the endpoint as the RFC 8707 resource indicator when the server publishes no
     * protected resource metadata.
     */
    private final boolean endpointAsResource;

    /**
     * A dynamic registration also registers the resource's own {@code redirect_uri} when the operator's allow-list
     * does not hold it; otherwise such a request is rejected.
     */
    private final boolean anyResourceRedirectUri;
}
