package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.Getter;

import java.util.Map;

/**
 * The rejection of a call that no budget of the caller's cost object covers. It answers like any other limit
 * rejection; {@link #getReason()} is its message as text, for a route that answers it without the DIAL error body.
 */
@Getter
public class NoBudgetException extends HttpException {

    private final String reason;

    /**
     * @param reason  the message as text
     * @param message the answer's body, the DIAL error body that carries the same text
     */
    public NoBudgetException(String reason, HttpStatus status, String message, Map<String, String> headers) {
        super(status, message, headers);
        this.reason = reason;
    }
}
