package com.epam.aidial.core.server.security;

public enum Operation {

    READ,
    WRITE;

    public boolean isRead() {
        return this == READ;
    }
}
