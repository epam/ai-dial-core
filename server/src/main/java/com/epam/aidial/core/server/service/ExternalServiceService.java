package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsChangeMode;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidator;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Manages an application's inline {@code external_services}: validation, encryption-at-rest of
 * {@code client_secret}, and per-service create/update/delete.
 */
@RequiredArgsConstructor
public class ExternalServiceService {

    // Ids flow into URI-parsed credential scopes and storage paths, so restrict them like toolset names.
    private static final Pattern SERVICE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-_]+$");

    private final ResourceService resourceService;
    private final ResourceAuthSettingsEncryptionService encryptionService;
    private final AuthSettingsValidatorFactory validatorFactory = new AuthSettingsValidatorFactory();

    /**
     * On an application write: validate against existing state, drop computed statuses, preserve omitted
     * client_secrets, and encrypt secrets at rest.
     */
    public void processOnWrite(ResourceDescriptor resource, Application application, Application existing) {
        validate(application, existing);
        clearAuthStatuses(application);
        if (existing != null) {
            decryptSecrets(resource, existing);
        }
        preserveOmittedSecrets(application, existing);
        encryptSecrets(resource, application);
    }

    public ExternalService putExternalService(ResourceDescriptor resource, String serviceId, ExternalService service, String author) {
        verifyApplication(resource);
        resourceService.computeResource(resource, EtagHeader.ANY, author, json -> {
            Application app = ProxyUtil.convertToObject(json, Application.class);
            if (app == null) {
                throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
            }
            decryptSecrets(resource, app);
            if (app.getExternalServices() == null) {
                app.setExternalServices(new LinkedHashMap<>());
            }
            ExternalService existing = app.getExternalServices().get(serviceId);
            validateOne(serviceId, service, existing == null);
            clearAuthStatuses(service);
            if (existing != null && existing.getAuthSettings() != null
                    && service.getAuthSettings() != null && service.getAuthSettings().getClientSecret() == null
                    && existing.getAuthSettings().getClientSecret() != null) {
                service.getAuthSettings().setClientSecret(existing.getAuthSettings().getClientSecret());
            }
            app.getExternalServices().put(serviceId, service);
            encryptSecrets(resource, app);
            return ProxyUtil.convertToString(app);
        });
        return service;
    }

    public void deleteExternalService(ResourceDescriptor resource, String serviceId, String author) {
        verifyApplication(resource);
        resourceService.computeResource(resource, EtagHeader.ANY, author, json -> {
            Application app = ProxyUtil.convertToObject(json, Application.class);
            if (app == null) {
                throw new ResourceNotFoundException("Application is not found: " + resource.getUrl());
            }
            if (app.getExternalServices() == null || app.getExternalServices().remove(serviceId) == null) {
                throw new ResourceNotFoundException("External service '%s' not found".formatted(serviceId));
            }
            return ProxyUtil.convertToString(app);
        });
    }

    public void encryptSecrets(ResourceDescriptor resource, Application application) {
        processSecrets(resource, application, true);
    }

    public void decryptSecrets(ResourceDescriptor resource, Application application) {
        processSecrets(resource, application, false);
    }

    private void validate(Application application, Application existing) {
        Map<String, ExternalService> services = application.getExternalServices();
        if (services == null || services.isEmpty()) {
            return;
        }
        Map<String, ExternalService> existingServices = existing == null || existing.getExternalServices() == null
                ? Map.of() : existing.getExternalServices();
        for (Map.Entry<String, ExternalService> entry : services.entrySet()) {
            validateOne(entry.getKey(), entry.getValue(), !existingServices.containsKey(entry.getKey()));
        }
    }

    // Static OAuth clients only (no PKCE/DCR). A first-time OAUTH create requires client_secret
    // (CREATE_STATIC_CLIENT); updates use NO_CLIENT_CHANGES so an omitted secret is preserved from storage.
    private void validateOne(String serviceId, ExternalService service, boolean isCreate) {
        if (serviceId == null || !SERVICE_ID_PATTERN.matcher(serviceId).matches()) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External service id '%s' must contain only letters, digits, '-' or '_'".formatted(serviceId));
        }
        ResourceAuthSettings authSettings = service == null ? null : service.getAuthSettings();
        if (authSettings == null || authSettings.getAuthenticationType() == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "External service '%s': auth_settings.authentication_type is required".formatted(serviceId));
        }
        AuthSettingsValidator validator = validatorFactory.getValidator(authSettings.getAuthenticationType());
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

    private void processSecrets(ResourceDescriptor resource, Application application, boolean encrypt) {
        Map<String, ExternalService> services = application.getExternalServices();
        if (services == null || services.isEmpty()) {
            return;
        }
        BucketInfo bucketInfo = new BucketInfo(resource.getBucketName(), resource.getBucketLocation());
        for (Map.Entry<String, ExternalService> entry : services.entrySet()) {
            ExternalService service = entry.getValue();
            if (service == null || service.getAuthSettings() == null || service.getAuthSettings().getClientSecret() == null) {
                continue;
            }
            String aad = secretAad(resource, entry.getKey());
            if (encrypt) {
                encryptionService.encrypt(aad, bucketInfo, service.getAuthSettings());
            } else {
                encryptionService.decrypt(aad, bucketInfo, service.getAuthSettings());
            }
        }
    }

    // AAD binds each secret to its owning application resource and external-service id.
    private static String secretAad(ResourceDescriptor resource, String serviceId) {
        return resource.getUrl() + "/external_services/" + serviceId;
    }

    private static void preserveOmittedSecrets(Application application, Application existing) {
        if (existing == null || existing.getExternalServices() == null || application.getExternalServices() == null) {
            return;
        }
        for (Map.Entry<String, ExternalService> entry : application.getExternalServices().entrySet()) {
            ExternalService service = entry.getValue();
            if (service == null || service.getAuthSettings() == null || service.getAuthSettings().getClientSecret() != null) {
                continue;
            }
            ExternalService existingService = existing.getExternalServices().get(entry.getKey());
            if (existingService != null && existingService.getAuthSettings() != null
                    && existingService.getAuthSettings().getClientSecret() != null) {
                service.getAuthSettings().setClientSecret(existingService.getAuthSettings().getClientSecret());
            }
        }
    }

    private static void clearAuthStatuses(Application application) {
        Map<String, ExternalService> services = application.getExternalServices();
        if (services == null) {
            return;
        }
        services.values().forEach(ExternalServiceService::clearAuthStatuses);
    }

    private static void clearAuthStatuses(ExternalService service) {
        ResourceAuthSettings authSettings = service == null ? null : service.getAuthSettings();
        if (authSettings != null) {
            authSettings.setUserLevelAuthStatus(null);
            authSettings.setAppLevelAuthStatus(null);
            authSettings.setGlobalAuthStatus(null);
        }
    }

    private static void verifyApplication(ResourceDescriptor resource) {
        if (resource.isFolder() || resource.getType() != ResourceTypes.APPLICATION) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }
    }
}
