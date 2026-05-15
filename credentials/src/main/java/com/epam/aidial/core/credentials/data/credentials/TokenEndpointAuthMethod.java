package com.epam.aidial.core.credentials.data.credentials;

/**
 * Client authentication methods at the OAuth 2.0 token endpoint
 * (RFC 6749 §2.3 and RFC 8414 §2 — {@code token_endpoint_auth_methods_supported}).
 *
 * <p>A null/blank value resolves to {@link #CLIENT_SECRET_BASIC} per RFC 6749 §2.3.1
 * (BASIC is {@code MUST} for compliant authorization servers; POST is {@code MAY} and
 * {@code NOT RECOMMENDED}). OAuth 2.1, which MCP references, deprecates POST entirely.
 *
 * <p>DIAL historically sent {@code client_secret_post} unconditionally. Operators whose AS
 * only accepts POST must set this field explicitly — those servers are non-compliant with
 * RFC 6749 §2.3.1 and would be unable to interop with most OAuth 2.1 clients anyway.
 *
 * <p>TODO: require the field at creation time so the default applies only to pre-PR
 * persisted data and can eventually be removed.
 */
public enum TokenEndpointAuthMethod {

    CLIENT_SECRET_POST,
    CLIENT_SECRET_BASIC,
    NONE;

    public String value() {
        return name().toLowerCase();
    }

    public static TokenEndpointAuthMethod resolveOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return CLIENT_SECRET_BASIC;
        }
        return parse(value);
    }

    /**
     * Parses a value or throws if unsupported. Callers must null-check first.
     */
    public static TokenEndpointAuthMethod parse(String value) {
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported token_endpoint_auth_method: " + value);
        }
    }
}
