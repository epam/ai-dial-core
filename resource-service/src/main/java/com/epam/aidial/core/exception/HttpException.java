package com.epam.aidial.core.exception;

import com.epam.aidial.core.util.HttpStatus;
import lombok.Getter;

@Getter
public class HttpException extends RuntimeException {
    private final HttpStatus status;

    public HttpException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}