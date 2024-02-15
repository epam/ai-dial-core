package com.epam.aidial.core.storage;

import org.jclouds.domain.Credentials;

import java.util.Objects;

public class DefaultCredentialProvider implements CredentialProvider {

    private final String identity;
    private final String credential;

    public DefaultCredentialProvider(String identity, String credential) {
        this.identity = Objects.requireNonNull(identity);
        this.credential = Objects.requireNonNull(credential);
    }

    @Override
    public Credentials getCredentials() {
        return new Credentials(identity, credential);
    }
}
