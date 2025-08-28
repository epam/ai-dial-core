package com.epam.aidial.core.credentials.service.factory;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.credentials.service.TokenService;

import java.util.HashMap;
import java.util.Map;

public class ResourceCredentialsFactoryProvider {

    private static final Map<AuthenticationType, ResourceCredentialsFactory> factoryMap = new HashMap<>();

    public ResourceCredentialsFactoryProvider(TokenService tokenService) {
        factoryMap.put(AuthenticationType.API_KEY, new ApiKeyResourceCredentialsFactory());
        factoryMap.put(AuthenticationType.OAUTH, new OauthResourceCredentialsFactory(tokenService));
        factoryMap.put(AuthenticationType.NONE, new NoneAuthResourceCredentialsFactory());
    }

    public ResourceCredentialsFactory getFactory(AuthenticationType authenticationType) {
        ResourceCredentialsFactory factory = factoryMap.get(authenticationType);
        if (factory == null) {
            throw new IllegalArgumentException("Invalid AuthenticationType: %s".formatted(authenticationType));
        }
        return factory;
    }
}
