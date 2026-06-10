package com.epam.aidial.core.credentials.data.credentials;

/**
 * Client authentication methods at the OAuth 2.0 token endpoint
 * (RFC 6749 §2.3 and RFC 8414 §2 — {@code token_endpoint_auth_methods_supported}).
 *
 * <p>When the method is unspecified it is inferred from the client type (see
 * {@link #resolve(String, String)}): a client with no secret is public and uses {@link #NONE};
 * a client with a secret falls back to {@link #CLIENT_SECRET_BASIC} (RFC 7591 §2 default).
 */
public enum TokenEndpointAuthMethod {

    CLIENT_SECRET_POST,
    CLIENT_SECRET_BASIC,
    NONE;

    public String value() {
        return name().toLowerCase();
    }

    /**
     * Resolves the token-endpoint auth method for a client. An explicit {@code value} always wins.
     * When it is null/blank the method is inferred from the client type: a client with <b>no</b>
     * secret is public and uses {@link #NONE} (it cannot perform a {@code client_secret_*} method);
     * a client <b>with</b> a secret falls back to {@link #CLIENT_SECRET_BASIC} (RFC 7591 §2 default).
     *
     * <p>Defaulting a secretless client to {@code client_secret_basic} would put {@code client_id}
     * in the Authorization header instead of the request body, which public/PKCE DCR servers
     * (e.g. FastMCP's OAuth Proxy) reject — they read {@code client_id} from the body. For a
     * secretless client {@code none} and {@code client_secret_post} are wire-identical, so
     * {@code none} (the RFC-correct public-client value) is used.
     */
    public static TokenEndpointAuthMethod resolve(String value, String clientSecret) {
        if (value != null && !value.isBlank()) {
            return parse(value);
        }
        return (clientSecret == null || clientSecret.isBlank()) ? NONE : CLIENT_SECRET_BASIC;
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
