package com.epam.aidial.core.credentials.service.registration;

/**
 * What a client registration may take from the resource itself rather than from the operator.
 *
 * @param endpointAsResource      a dynamic registration uses the endpoint as the RFC 8707 resource indicator when the
 *                                server publishes no protected resource metadata
 * @param anyResourceRedirectUri  a dynamic registration also registers the resource's own {@code redirect_uri} when the
 *                                operator's allow-list does not hold it; otherwise such a request is rejected
 */
public record RegistrationPolicy(boolean endpointAsResource, boolean anyResourceRedirectUri) {

    public static final RegistrationPolicy TOOLSET = new RegistrationPolicy(true, true);

    /**
     * An external service's endpoint may be the authorization server itself, and anyone who may write the service
     * would otherwise get a redirect of their choosing registered under Core's client.
     */
    public static final RegistrationPolicy EXTERNAL_SERVICE = new RegistrationPolicy(false, false);
}
