package com.epam.aidial.core.storage.blobstore.credential;

import org.jclouds.domain.Credentials;

public interface CredentialProvider {

    Credentials getCredentials();
}
