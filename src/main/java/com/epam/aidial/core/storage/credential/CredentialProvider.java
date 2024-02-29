package com.epam.aidial.core.storage.credential;

import org.jclouds.domain.Credentials;

public interface CredentialProvider {

    Credentials getCredentials();
}
