package com.epam.aidial.core.server.service.credentials.factory;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.server.service.credentials.ToolSetTokenService;

import java.util.HashMap;
import java.util.Map;

public class ToolSetCredentialsFactoryProvider {

    private static final Map<AuthenticationType, ToolSetCredentialsFactory> factoryMap = new HashMap<>();

    public ToolSetCredentialsFactoryProvider(ToolSetTokenService tokenService) {
        factoryMap.put(AuthenticationType.API_KEY, new ApiKeyToolSetCredentialsFactory());
        factoryMap.put(AuthenticationType.OAUTH, new OauthToolSetCredentialsFactory(tokenService));
        factoryMap.put(AuthenticationType.NONE, new NoneAuthToolSetCredentialsFactory());
    }

    public ToolSetCredentialsFactory getFactory(AuthenticationType authenticationType) {
        ToolSetCredentialsFactory factory = factoryMap.get(authenticationType);
        if (factory == null) {
            throw new IllegalArgumentException(String.format("Invalid ToolsetAuthenticationType: %s", authenticationType));
        }
        return factory;
    }
}
