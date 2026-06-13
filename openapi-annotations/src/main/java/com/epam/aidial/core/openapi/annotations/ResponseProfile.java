package com.epam.aidial.core.openapi.annotations;

/**
 * Named response bundles for {@link ApiOperation#responseProfile()} ()}.
 * Expanded at OpenAPI generation time via {@code StandardResponses} helpers.
 */
public enum ResponseProfile {

    NONE,

    AUTHENTICATED_OPERATION,

    AUTHORIZED_OPERATION,

    AUTHENTICATED_READ,

    AUTHENTICATED_READ_WITH_NOT_FOUND,

    AUTHENTICATED_READ_WITH_SERVER_ERROR,

    CONDITIONAL_WRITE,

    CONDITIONAL_DELETE,

    OPS_WITH_BAD_REQUEST,

    APPLICATION_OPS,

    LLM_PROXY,

    LLM_EMBEDDING,

    CODE_INTERPRETER,

    LIMIT_WITH_NOT_FOUND,
    TOOLSET_TOOLS
}