package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidator;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidatorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

@UtilityClass
public class ExternalServiceValidation {

    // Ids flow into URI-parsed credential scopes and storage paths, so restrict them like toolset names.
    private static final Pattern SERVICE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-_]+$");

    private static final AuthSettingsValidatorFactory VALIDATOR_FACTORY = new AuthSettingsValidatorFactory();

    // Static OAuth clients only (no PKCE/DCR). A first-time OAUTH create requires client_secret
    // (CREATE_STATIC_CLIENT); updates use NO_CLIENT_CHANGES so an omitted secret is preserved from storage.
    // Re-validate on every access: descriptor() builds storage paths from the id, keeping get/delete self-enforcing.
    public static void validateServiceId(String serviceId) {
        if (serviceId == null || !SERVICE_ID_PATTERN.matcher(serviceId).matches()) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External service id '%s' must contain only letters, digits, '-' or '_'".formatted(serviceId));
        }
    }

    public static void validate(String serviceId, ExternalService service, boolean isCreate) {
        validateServiceId(serviceId);
        ResourceAuthSettings authSettings = service == null ? null : service.getAuthSettings();
        if (authSettings == null || authSettings.getAuthenticationType() == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External service '%s': auth_settings.authentication_type is required".formatted(serviceId));
        }
        AuthSettingsValidator validator = VALIDATOR_FACTORY.getValidator(authSettings.getAuthenticationType());
        if (validator == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External service '%s': unknown authentication_type %s".formatted(serviceId, authSettings.getAuthenticationType()));
        }
        ResourceAuthSettingsChangeMode mode = isCreate && authSettings.getAuthenticationType() == AuthenticationType.OAUTH
                ? ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT
                : ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES;
        try {
            validator.validate(authSettings, mode);
        } catch (RuntimeException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External service '%s': invalid auth_settings: %s".formatted(serviceId, e.getMessage()));
        }
    }
}
