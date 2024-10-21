package com.epam.aidial.core.server.storage.credential;

import org.jclouds.domain.Credentials;

public interface CredentialProvider {

    Credentials getCredentials();
}
