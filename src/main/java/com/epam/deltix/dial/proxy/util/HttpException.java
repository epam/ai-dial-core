package com.epam.deltix.dial.proxy.util;

import lombok.Getter;

@Getter
public class HttpException extends Exception {
    private final HttpStatus status;

    public HttpException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}