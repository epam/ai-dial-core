package com.epam.aidial.core.storage.credential;

import org.jclouds.domain.Credentials;

import java.util.Objects;

public class DefaultCredentialProvider implements CredentialProvider {

    private final Credentials credentials;

    public DefaultCredentialProvider(String identity, String credential) {
        this.credentials = new Credentials(Objects.requireNonNull(identity), Objects.requireNonNull(credential));
    }

    @Override
    public Credentials getCredentials() {
        return credentials;
    }
}
