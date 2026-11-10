package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.data.consent.AdminConsentStatus;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.consent.ConsentGrant;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

import static com.epam.aidial.core.storage.http.HttpStatus.BAD_REQUEST;
import static com.epam.aidial.core.storage.http.HttpStatus.CONFLICT;
import static com.epam.aidial.core.storage.http.HttpStatus.FORBIDDEN;

/**
 * Manages the admin-consent store: grant, withdraw, describe, and the per-request gate check.
 * Record handling during application lifecycle (delete, move) also lives here.
 */
@Slf4j
@RequiredArgsConstructor
public class AdminConsentService {

    private final DeploymentService deploymentService;

    private final ResourceService resourceService;

    private final LongSupplier clock;

    /**
     * Approves the reviewed declaration for every user; 409 when it no longer equals the current one. The record is keyed
     * by the resolved application's name, as the resolver reads it, never by the raw request id.
     */
    public ConsentGrant grantAdminConsent(ProxyContext context, String deploymentId, List<Consent.ResourceEntry> reviewed) {
        Application application = requireDeclaringApplication(context, deploymentId);
        List<Consent.ResourceEntry> declared = Consent.toResourceEntries(application.getResourceDependencies());
        if (!declared.equals(reviewed)) {
            throw new HttpException(CONFLICT, "The declaration changed since it was reviewed: " + deploymentId);
        }
        ConsentGrant grant = new ConsentGrant()
                .setConsent(adminConsentOf(declared))
                .setGrantedBy(context.getInitiatorId())
                .setGrantedAt(clock.getAsLong());
        resourceService.putResource(adminConsentDescriptor(application.getName()),
                ProxyUtil.convertToString(grant), EtagHeader.ANY);
        return grant;
    }

    /** Deletes the record; returns the withdrawn grant (null when absent) for the audit event. */
    public ConsentGrant withdrawAdminConsent(ProxyContext context, String deploymentId) {
        Application application = requireApplication(context, deploymentId);
        ResourceDescriptor descriptor = adminConsentDescriptor(application.getName());
        ConsentGrant withdrawn = readAdminConsent(descriptor);
        resourceService.deleteResource(descriptor, EtagHeader.ANY);
        return withdrawn;
    }

    /** {@code consented} is exactly what the request-time gate enforces. */
    public AdminConsentStatus describeAdminConsent(ProxyContext context, String deploymentId) {
        Application application = requireApplication(context, deploymentId);
        ConsentGrant stored = readAdminConsent(adminConsentDescriptor(application.getName()));
        if (stored == null) {
            return new AdminConsentStatus().setConsented(false);
        }
        boolean matches = matches(stored, application.getResourceDependencies());
        return new AdminConsentStatus()
                .setConsented(matches)
                .setStale(!matches)
                .setGrantedBy(stored.getGrantedBy())
                .setGrantedAt(stored.getGrantedAt())
                .setGrantedResources(stored.getConsent() == null ? List.of() : stored.getConsent().getResources());
    }

    public boolean isAdminConsented(String applicationId, List<ResourceDependency> declaration) {
        ResourceDescriptor descriptor = findAdminConsentDescriptor(applicationId);
        return descriptor != null && matches(readAdminConsent(descriptor), declaration);
    }

    /** Deletes the application's admin-consent record, if any. */
    public void deleteAdminConsent(ResourceDescriptor application) {
        ResourceDescriptor record = adminConsentOf(application);
        if (record != null) {
            resourceService.deleteResource(record, EtagHeader.ANY);
        }
    }

    /**
     * The approval follows a moved application: it binds the declared resources, which a move does not change. An
     * application the move overwrites loses its own approval.
     */
    public void moveAdminConsent(ResourceDescriptor source, ResourceDescriptor destination) {
        ResourceDescriptor destinationRecord = adminConsentOf(destination);
        if (destinationRecord == null) {
            return;
        }
        ResourceDescriptor sourceRecord = adminConsentOf(source);
        String grant = sourceRecord == null ? null : resourceService.getResource(sourceRecord);
        if (grant != null) {
            resourceService.putResource(destinationRecord, grant, EtagHeader.ANY);
        } else {
            resourceService.deleteResource(destinationRecord, EtagHeader.ANY);
        }
    }

    /**
     * The record is keyed by the name requests resolve to: a platform application's config name, any other's url. Null
     * when no record can exist for the application.
     */
    @Nullable
    public static ResourceDescriptor adminConsentOf(ResourceDescriptor application) {
        return findAdminConsentDescriptor(ResourceDescriptor.PLATFORM_BUCKET.equals(application.getBucketName())
                ? MergedConfigStore.resolveMapKeyFor(application) : application.getUrl());
    }

    private static Consent adminConsentOf(List<Consent.ResourceEntry> declared) {
        Consent consent = new Consent();
        consent.setResources(declared);
        return consent;
    }

    /** One record per application in the public bucket. A config name is decoded already; an application url is not. */
    public static ResourceDescriptor adminConsentDescriptor(String applicationName) {
        String path = applicationName.contains(ResourceDescriptor.PATH_SEPARATOR)
                ? applicationName : UrlUtil.encodePathSegment(applicationName);
        return ResourceDescriptorFactory.fromEntityPath(ResourceTypes.ADMIN_CONSENT,
                ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, path);
    }

    /** Null when the name builds no valid record path (too long, say): such an application can never be approved. */
    @Nullable
    public static ResourceDescriptor findAdminConsentDescriptor(String applicationName) {
        try {
            return adminConsentDescriptor(applicationName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Exact snapshot equality, shared by the gate and the status read; provenance never participates. */
    private static boolean matches(ConsentGrant stored, List<ResourceDependency> declaration) {
        return stored != null && stored.getConsent() != null
                && Objects.equals(stored.getConsent().getResources(), Consent.toResourceEntries(declaration));
    }

    private Application requireDeclaringApplication(ProxyContext context, String deploymentId) {
        Application application = requireApplication(context, deploymentId);
        if (!deploymentService.isApprovableLocation(context, deploymentId)) {
            throw new HttpException(FORBIDDEN, "Only an application in the public, a review or your own bucket can be approved: " + deploymentId);
        }
        if (application.getResourceDependencies() == null || application.getResourceDependencies().isEmpty()) {
            throw new HttpException(BAD_REQUEST, "Application declares no resource dependencies: " + deploymentId);
        }
        return application;
    }

    private Application requireApplication(ProxyContext context, String deploymentId) {
        // userRoles say who may run an application, not who manages its consent
        Deployment deployment = context.getConfig().selectDeployment(deploymentId);
        if (deployment == null) {
            try {
                deployment = deploymentService.findDeployment(context, deploymentId);
            } catch (IllegalArgumentException e) {
                throw new ResourceNotFoundException("Deployment is not an application: " + deploymentId);
            }
        }
        if (deployment instanceof Application application) {
            return application;
        }
        throw new ResourceNotFoundException("Deployment is not an application: " + deploymentId);
    }

    private ConsentGrant readAdminConsent(ResourceDescriptor descriptor) {
        String grant = resourceService.getResource(descriptor);
        try {
            return ProxyUtil.convertToObject(grant, ConsentGrant.class);
        } catch (IllegalArgumentException e) {
            log.error("Unreadable admin consent record {}, treated as absent", descriptor.getUrl(), e);
            return null;
        }
    }
}
