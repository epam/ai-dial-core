package com.epam.aidial.core.credentials.factory;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;

public interface ResourceCredentialsFactory {

    ResourceCredentials createCredentials(String resourceId,
                                          ResourceAuthSettings authSettings,
                                          ResourceSignInRequest signInRequest);
}
