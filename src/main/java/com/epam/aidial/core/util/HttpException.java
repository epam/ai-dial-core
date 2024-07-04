package com.epam.aidial.core.util;

import lombok.Getter;

@Getter
public class HttpException extends RuntimeException {
    private final HttpStatus status;

    public HttpException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static void validateETag(String expected, String actual) {
        if (expected != null && !expected.equals(actual)) {
            throw new HttpException(HttpStatus.PRECONDITION_FAILED, "ETag %s is outdated".formatted(expected));
        }
    }
}