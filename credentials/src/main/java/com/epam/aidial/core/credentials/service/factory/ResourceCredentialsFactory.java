package com.epam.aidial.core.credentials.service.factory;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;

public interface ResourceCredentialsFactory {

    ResourceCredentials createCredentials(String resourceId,
                                          ResourceAuthSettings authSettings,
                                          ResourceSignInRequest signInRequest);
}
