package com.epam.aidial.core.storage.http;

import lombok.Getter;

import java.util.Map;
import java.util.TreeMap;

@Getter
public class HttpException extends RuntimeException {
    private final HttpStatus status;
    private final Map<String, String> headers;

    public HttpException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public HttpException(HttpStatus status, String message, Map<String, String> headers) {
        super(message);
        this.status = status;
        if (headers == null) {
            this.headers = null;
        } else {
            this.headers = new TreeMap<>(String::compareToIgnoreCase);
            this.headers.putAll(headers);
        }
    }

    public String getHeader(String name) {
        return headers == null ? null : headers.get(name);
    }
}