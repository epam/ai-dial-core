package com.epam.aidial.core.server.security;

public class IdpNotFoundException extends RuntimeException {
    public IdpNotFoundException(String message) {
        super(message);
    }
}
