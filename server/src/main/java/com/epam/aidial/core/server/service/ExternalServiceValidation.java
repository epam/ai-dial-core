package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

@UtilityClass
public class ExternalServiceValidation {

    // Ids flow into URI-parsed credential scopes and storage paths, so restrict them like toolset names.
    private static final Pattern SERVICE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-_]+$");

    // Re-validate on every access: descriptor() builds storage paths from the id, keeping get/delete self-enforcing.
    public static void validateServiceId(String serviceId) {
        if (serviceId == null || !SERVICE_ID_PATTERN.matcher(serviceId).matches()) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External service id '%s' must contain only letters, digits, '-' or '_'".formatted(serviceId));
        }
    }

    /**
     * The shape of a definition. The rules that depend on the stored definition (which fields a create or an update
     * needs, the client secret) run with the client setup, in {@link ExternalServiceAuthSettings#setUp}.
     */
    public static void validate(String serviceId, ExternalService service) {
        validateServiceId(serviceId);
        ResourceAuthSettings authSettings = service == null ? null : service.getAuthSettings();
        if (authSettings == null || authSettings.getAuthenticationType() == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External service '%s': auth_settings.authentication_type is required".formatted(serviceId));
        }
        validateDiscoveryUrl(serviceId, authSettings.getDiscoveryUrl());
    }

    private static void validateDiscoveryUrl(String serviceId, String discoveryUrl) {
        if (StringUtils.isBlank(discoveryUrl)) {
            return;
        }
        URI uri;
        try {
            uri = new URI(discoveryUrl);
        } catch (URISyntaxException e) {
            throw invalidDiscoveryUrl(serviceId);
        }
        if (!uri.isAbsolute() || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw invalidDiscoveryUrl(serviceId);
        }
    }

    private static HttpException invalidDiscoveryUrl(String serviceId) {
        return new HttpException(HttpStatus.BAD_REQUEST,
                "External service '%s': discovery_url must be an absolute http or https URL".formatted(serviceId));
    }
}
