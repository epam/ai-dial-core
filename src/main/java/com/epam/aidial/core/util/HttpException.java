package com.epam.aidial.core.util;

import lombok.Getter;

@Getter
public class HttpException extends Exception {
    private final HttpStatus status;

    public HttpException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}