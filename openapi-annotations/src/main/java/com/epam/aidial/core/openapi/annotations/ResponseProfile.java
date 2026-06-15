package com.epam.aidial.core.openapi.annotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Named response bundles for {@link ApiOperation#responseProfile()}.
 * Each profile declares its HTTP response codes inline for visibility and maintainability.
 * <p>
 * Profiles are self-describing: all response codes are visible in the enum definition,
 * eliminating the need for external switch statements and improving code locality.
 */
public enum ResponseProfile {

    /**
     * No standard error responses (only success responses defined via @ApiResponse).
     */
    NONE(),

    /**
     * Extended authenticated read profile: 400 Bad Request, 401 Unauthorized, 404 Not Found, 500 Internal Server Error.
     * Use for read operations with input validation that may encounter missing resources or server errors.
     */
    AUTHENTICATED_READ_EXTENDED("400", "401", "404", "500"),

    /**
     * Authenticated operation profile: 400 Bad Request, 401 Unauthorized, 500 Internal Server Error.
     * Use for operations requiring authentication with input validation.
     */
    AUTHENTICATED_OPERATION("400", "401", "500"),

    /**
     * Authorized operation profile: 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 500 Internal Server Error.
     * Use for operations requiring both authentication and authorization that may encounter missing resources.
     */
    AUTHORIZED_OPERATION("400", "401", "403", "404", "500"),

    /**
     * Conditional write profile: 401 Unauthorized, 412 Precondition Failed.
     * Use for write operations with ETag/If-Match/If-None-Match conditional headers.
     */
    CONDITIONAL_WRITE("401", "412"),

    /**
     * Extended conditional write profile: 400 Bad Request, 401 Unauthorized, 404 Not Found, 412 Precondition Failed, 413 Payload Too Large, 500 Internal Server Error.
     * Use for resource write/delete operations with validation, size limits, and error handling.
     */
    CONDITIONAL_WRITE_EXTENDED("400", "401", "404", "412", "413", "500"),

    /**
     * Operations with bad request profile: 400 Bad Request, 401 Unauthorized, 404 Not Found, 422 Unprocessable Entity, 429 Too Many Requests, 500 Internal Server Error, 502 Bad Gateway.
     * Use for MCP proxy operations with validation, rate limiting, and upstream error handling.
     */
    OPS_WITH_BAD_REQUEST("400", "401", "404", "422", "429", "500", "502"),

    /**
     * Application operations profile: 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 409 Conflict, 500 Internal Server Error.
     * Use for application lifecycle operations (create, update, delete).
     */
    APPLICATION_OPS("400", "401", "403", "404", "409", "500"),

    /**
     * Code interpreter profile: 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 500 Internal Server Error.
     * Use for code interpreter session operations.
     */
    CODE_INTERPRETER("400", "401", "403", "404", "500"),

    /**
     * Limit with not found profile: 401 Unauthorized, 404 Not Found.
     * Use for limit retrieval operations.
     */
    LIMIT_WITH_NOT_FOUND("401", "404"),

    /**
     * Toolset tools profile: 401 Unauthorized, 403 Forbidden, 404 Not Found, 500 Internal Server Error, 502 Bad Gateway.
     * Use for toolset tool operations.
     */
    TOOLSET_TOOLS("401", "403", "404", "500", "502"),

    /**
     * Config resource full profile: 304 Not Modified, 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 405 Method Not Allowed, 412 Precondition Failed, 422 Unprocessable Entity, 500 Internal Server Error.
     * Use for configuration resource CRUD operations with ETag support and validation.
     */
    CONFIG_RESOURCE_FULL("304", "400", "401", "403", "404", "405", "412", "422", "500"),

    /**
     * Admin batch operations profile: 400 Bad Request, 401 Unauthorized, 403 Forbidden, 422 Unprocessable Entity, 500 Internal Server Error.
     * Use for admin validate/apply batch operations with semantic validation failures.
     */
    ADMIN_BATCH("400", "401", "403", "422", "500"),

    /**
     * Responses API profile: 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 415 Unsupported Media Type, 500 Internal Server Error, 502 Bad Gateway, 503 Service Unavailable.
     * Use for Responses API endpoints with Content-Type validation.
     */
    RESPONSES_API("400", "401", "403", "404", "415", "500", "502", "503"),

    /**
     * Admin read-only profile: 403 Forbidden, 404 Not Found, 405 Method Not Allowed, 500 Internal Server Error.
     * Use for admin read-only endpoints (no 401 - admin context is implicit).
     */
    ADMIN_READ_ONLY("403", "404", "405", "500"),

    /**
     * Metadata listing profile: 400 Bad Request, 403 Forbidden, 404 Not Found, 405 Method Not Allowed, 500 Internal Server Error.
     * Use for metadata listing endpoints with query parameter validation.
     */
    METADATA_LISTING("400", "403", "404", "405", "500"),

    /**
     * Response item proxy profile: 403 Forbidden, 404 Not Found, 500 Internal Server Error, 503 Service Unavailable.
     * Use for response item operations (upstream handles authentication).
     */
    RESPONSE_ITEM_PROXY("403", "404", "500", "503");

    private final Set<String> responseCodes;

    ResponseProfile(String... codes) {
        LinkedHashSet<String> set = Arrays.stream(codes)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        this.responseCodes = Collections.unmodifiableSet(set);
    }

    /**
     * Returns the set of HTTP response codes for this profile.
     *
     * @return unmodifiable set of HTTP status codes (e.g., "401", "404", "500")
     */
    public Set<String> getResponseCodes() {
        return responseCodes;
    }

    /**
     * Checks if this profile includes the specified HTTP response code.
     *
     * @param code the HTTP status code to check (e.g., "404")
     * @return true if this profile includes the code, false otherwise
     */
    public boolean hasCode(String code) {
        return responseCodes.contains(code);
    }
}