package com.epam.aidial.core.credentials.validation;

import com.epam.aidial.core.config.AuthenticationType;

import java.util.EnumMap;
import java.util.Map;

public class AuthSettingsValidatorFactory {

    private final Map<AuthenticationType, AuthSettingsValidator> validators = new EnumMap<>(AuthenticationType.class);

    public AuthSettingsValidatorFactory() {
        validators.put(AuthenticationType.OAUTH, new OauthAuthSettingsValidator());
        validators.put(AuthenticationType.API_KEY, new ApiKeyAuthSettingsValidator());
        validators.put(AuthenticationType.NONE, new NoneAuthSettingsValidator());
        validators.put(AuthenticationType.DIAL_NATIVE, new DialNativeAuthSettingsValidator());
    }

    public AuthSettingsValidator getValidator(AuthenticationType authenticationType) {
        return validators.get(authenticationType);
    }
}
