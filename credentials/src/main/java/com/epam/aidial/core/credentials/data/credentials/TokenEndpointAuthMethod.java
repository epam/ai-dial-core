package com.epam.aidial.core.credentials.data.credentials;

/**
 * Client authentication methods at the OAuth 2.0 token endpoint
 * (RFC 6749 §2.3 and RFC 8414 §2 — {@code token_endpoint_auth_methods_supported}).
 *
 * <p>For backward compatibility with toolsets registered before this field existed,
 * a null/blank value resolves to {@link #CLIENT_SECRET_POST} (the method DIAL has
 * always used). Per RFC 6749 §2.3.1 the spec-recommended default is
 * {@code client_secret_basic}, but mapping null → basic would break existing
 * working toolsets whose authorization servers only accept credentials in the body.</p>
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
            return CLIENT_SECRET_POST;
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported token_endpoint_auth_method: " + value);
        }
    }
}
