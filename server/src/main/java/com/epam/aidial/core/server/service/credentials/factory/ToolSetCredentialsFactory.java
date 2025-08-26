package com.epam.aidial.core.server.service.credentials.factory;

import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolSetSignInRequest;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;

public interface ToolSetCredentialsFactory {

    ToolSetCredentials createCredentials(String toolSetName,
                                         ToolSetAuthSettings authSettings,
                                         ToolSetSignInRequest signInRequest,
                                         ProxyContext context);
}
