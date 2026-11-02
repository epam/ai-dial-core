package com.epam.aidial.core.translator;

import lombok.Getter;

/**
 * The upstream answered {@code 200} but reported a failure, or a response that never finished: the client gets
 * this Anthropic error instead of a message. Ported from the Python translator's {@code errors.AnthropicHTTPError}.
 */
@Getter
public class UpstreamFailureException extends RuntimeException {

    private final int status;

    /**
     * The Anthropic error type, such as {@code api_error}.
     */
    private final String type;

    public UpstreamFailureException(int status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }
}
