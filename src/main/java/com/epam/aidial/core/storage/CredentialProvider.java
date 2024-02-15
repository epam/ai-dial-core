package com.epam.aidial.core.storage;

import org.jclouds.domain.Credentials;

public interface CredentialProvider {

    Credentials getCredentials();
}
