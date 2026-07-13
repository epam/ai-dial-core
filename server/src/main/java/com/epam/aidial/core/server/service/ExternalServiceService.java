package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages an application's inline {@code external_services}: validation, encryption-at-rest of
 * {@code client_secret}, and per-service create/update/delete.
 */
@Slf4j
@RequiredArgsConstructor
public class ExternalServiceService {

    private final ResourceService resourceService;
    private final ResourceAuthSettingsEncryptionService encryptionService;
    private final ResourceCredentialsService resourceCredentialsService;

    /**
     * On an application write: validate, drop computed statuses, preserve omitted client_secrets, encrypt
     * at rest. Returns ids removed by this write so the caller can purge their APP-level credentials.
     *
     * <p>With {@link ExternalServicesWriteMode#PRESERVE_IF_OMITTED} (the request omitted {@code external_services},
     * e.g. a partial update saving other properties) the stored services are carried forward untouched.
     * {@link ExternalServicesWriteMode#OVERRIDE} treats the map as the desired state and removes + purges any
     * service it drops.
     */
    public List<String> processOnWrite(ResourceDescriptor resource, Application application, Application existing,
                                       ExternalServicesWriteMode mode) {
        if (mode == ExternalServicesWriteMode.PRESERVE_IF_OMITTED) {
            application.setExternalServices(existing == null ? new LinkedHashMap<>() : existing.getExternalServices());
            return List.of();
        }
        validate(application, existing);
        clearAuthStatuses(application);
        if (existing != null) {
            decryptSecrets(resource, existing);
        }
        preserveOmittedSecrets(application, existing);
        encryptSecrets(resource, application);
        return removedServiceIds(application, existing);
    }

    // Drop APP-level credentials for removed services, else a same-id re-create inherits the old token/secret.
    // USER-level credentials live in per-user buckets and are left untouched (same limitation as toolsets).
    public void purgeApplicationCredentials(ResourceDescriptor resource, Collection<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return;
        }
        for (String serviceId : serviceIds) {
            try {
                CredentialsLocator locator = applicationCredentialsLocator(resource, serviceId);
                resourceCredentialsService.deleteResourceCredentialsAtLevel(locator, CredentialsLevel.APPLICATION);
            } catch (RuntimeException e) {
                log.warn("Failed to purge APP-level credentials for external service '{}' on '{}'",
                        serviceId, resource.getUrl(), e);
            }
        }
    }

    private static List<String> removedServiceIds(Application application, Application existing) {
        if (existing == null || existing.getExternalServices() == null || existing.getExternalServices().isEmpty()) {
            return List.of();
        }
        Map<String, ExternalService> newServices = application.getExternalServices() == null
                ? Map.of() : application.getExternalServices();
        List<String> removed = new ArrayList<>();
        for (String id : existing.getExternalServices().keySet()) {
            if (!newServices.containsKey(id)) {
                removed.add(id);
            }
        }
        return removed;
    }

    // Same resource id/bucket as CredentialsLocatorFactory.fromExternalServiceScope for a dynamic app, so the
    // purge hits the path sign-in wrote to. No-op for config apps (their secrets live in the public bucket).
    private static CredentialsLocator applicationCredentialsLocator(ResourceDescriptor resource, String serviceId) {
        String resourceId = resource.getUrl() + CredentialsLocatorFactory.EXTERNAL_SERVICES_SEPARATOR + UrlUtil.encodePath(serviceId);
        Map<CredentialsLevel, BucketInfo> buckets = new EnumMap<>(CredentialsLevel.class);
        buckets.put(CredentialsLevel.APPLICATION, new BucketInfo(resource.getBucketName(), resource.getBucketLocation()));
        return new CredentialsLocator(resourceId, buckets);
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

    private void validateOne(String serviceId, ExternalService service, boolean isCreate) {
        ExternalServiceValidation.validate(serviceId, service, isCreate);
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
