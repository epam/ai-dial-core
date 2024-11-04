package com.epam.aidial.core.storage.http;

import lombok.Getter;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Getter
public class HttpException extends RuntimeException {
    private final HttpStatus status;
    private final Map<String, String> headers;

    public HttpException(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    public HttpException(HttpStatus status, String message, Map<String, String> headers) {
        super(message);
        this.status = status;
        this.headers = new TreeMap<>(String::compareToIgnoreCase);
        this.headers.putAll(Objects.requireNonNull(headers, "HTTP headers must not be null"));
    }

}