package com.epam.aidial.core.config;

public enum AuthenticationType {

    OAUTH,
    API_KEY,
    NONE,
    /**
     * The target is DIAL itself. There is no credential on the declaration: the caller acts as the user via
     * that user's offline credentials, and an administrator approves the application separately.
     */
    DIAL_NATIVE

}
