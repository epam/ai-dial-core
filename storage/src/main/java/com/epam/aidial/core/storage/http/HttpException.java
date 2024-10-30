package com.epam.aidial.core.storage.http;

import lombok.Getter;

@Getter
public class HttpException extends RuntimeException {
    private final HttpStatus status;

    public HttpException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}