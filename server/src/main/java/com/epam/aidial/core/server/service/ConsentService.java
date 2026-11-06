package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.consent.AdminConsentStatus;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.consent.ConsentGrant;
import com.epam.aidial.core.server.data.consent.ReviewConsentResponse;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import static com.epam.aidial.core.storage.http.HttpStatus.BAD_REQUEST;
import static com.epam.aidial.core.storage.http.HttpStatus.CONFLICT;

@Slf4j
public class ConsentService {

    private static final ReviewConsentResponse ACCEPTED_CONSENT_RESPONSE = new ReviewConsentResponse(null, true);

    private final DeploymentService deploymentService;

    private final ResourceService resourceService;

    private final LongSupplier clock;

    public ConsentService(DeploymentService deploymentService, ResourceService resourceService, LongSupplier clock) {
        this.deploymentService = deploymentService;
        this.resourceService = resourceService;
        this.clock = clock;
    }

    public ReviewConsentResponse buildConsent(ProxyContext context, String deploymentId) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.offer(deploymentId);
        seen.add(deploymentId);
        Consent newConsent = new Consent();
        boolean noneConsentRequired = true;
        Deployment rootDeployment = null;
        while (!queue.isEmpty()) {
            String currentDeploymentId = queue.poll();
            Deployment deployment = deploymentService.findDeployment(context, currentDeploymentId);
            if (currentDeploymentId.equals(deploymentId)) {
                rootDeployment = deployment;
            }
            boolean consentRequired = isConsentRequired(deployment);
            if (consentRequired) {
                noneConsentRequired = false;
            }
            Consent.Deployment current = newConsent.getDeployments().computeIfAbsent(currentDeploymentId, key -> new Consent.Deployment());
            current.setConsentRequired(consentRequired);
            for (String dependency : deployment.getDependencies()) {
                if (seen.add(dependency)) {
                    queue.offer(dependency);
                }
            }
        }
        // Consent is required for every declared dependency regardless of the author-controlled
        // features.consentRequired flag (§6.1), so a declaring app is never auto-accepted.
        List<Consent.ResourceEntry> resources = resourceEntriesOf(rootDeployment);
        if (!resources.isEmpty()) {
            newConsent.setResources(resources);
        }
        if (noneConsentRequired && resources.isEmpty()) {
            // no deployments required user consent and nothing is declared
            return ACCEPTED_CONSENT_RESPONSE;
        }
        Consent prevConsent = readConsent(context, deploymentId);
        boolean accepted = Objects.equals(prevConsent, newConsent);
        if (accepted) {
            return ACCEPTED_CONSENT_RESPONSE;
        }
        return new ReviewConsentResponse(newConsent, accepted);
    }

    public void acceptConsent(ProxyContext context, String deploymentId, Consent consent) {
        ResourceDescriptor descriptor = getResourceDescription(context, deploymentId);
        String consentBody = ProxyUtil.convertToString(consent);
        resourceService.putResource(descriptor, consentBody, EtagHeader.ANY);
    }

    public void verifyUserConsent(ProxyContext context, Deployment deployment) {
        if (!isConsentRequired(deployment)) {
            return;
        }
        String currentDeploymentId = deployment.getName();
        List<String> executionPath = context.getApiKeyData().getExecutionPath();
        String rootDeploymentId = getRootDeploymentId(context, deployment);
        Consent consent = readConsent(context, rootDeploymentId);
        if (consent == null) {
            // missing consent
            fail(rootDeploymentId);
        }
        if (executionPath != null) {
            for (String dep : executionPath) {
                if (!consent.getDeployments().containsKey(dep)) {
                    fail(currentDeploymentId);
                }
            }
        }
        Consent.Deployment consentDeployment = consent.getDeployments().get(currentDeploymentId);
        if (consentDeployment == null || !consentDeployment.isConsentRequired()) {
            fail(currentDeploymentId);
        }
    }

    /**
     * Approves the reviewed declaration for every user; 409 when it no longer equals the current one. The record is keyed
     * by the resolved application's name, the identity the resolver reads, so a percent-encoded name cannot land on another app's record.
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
        return matches(readAdminConsent(adminConsentDescriptor(applicationId)), declaration);
    }

    /** Exact snapshot equality, shared by the gate and the status read; provenance never participates. */
    private static boolean matches(ConsentGrant stored, List<ResourceDependency> declaration) {
        return stored != null && stored.getConsent() != null
                && Objects.equals(stored.getConsent().getResources(), Consent.toResourceEntries(declaration));
    }

    private Application requireDeclaringApplication(ProxyContext context, String deploymentId) {
        Application application = requireApplication(context, deploymentId);
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

    private static Consent adminConsentOf(List<Consent.ResourceEntry> declared) {
        Consent consent = new Consent();
        consent.setResources(declared);
        return consent;
    }

    private static List<Consent.ResourceEntry> resourceEntriesOf(Deployment deployment) {
        return deployment instanceof Application application ? Consent.toResourceEntries(application.getResourceDependencies()) : List.of();
    }

    /** One record per application in the public bucket; a moved app gets a new key and is unconsented. */
    public static ResourceDescriptor adminConsentDescriptor(String applicationName) {
        return ResourceDescriptorFactory.fromEntityPath(ResourceTypes.ADMIN_CONSENT,
                ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, applicationName);
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

    private String getRootDeploymentId(ProxyContext context, Deployment current) {
        if (context.getApiKeyData().getPerRequestKey() == null) {
            return current.getName();
        }
        List<String> executionPath = context.getApiKeyData().getExecutionPath();
        if (executionPath == null || executionPath.isEmpty()) {
            throw new IllegalStateException("Execution path is empty for per-request API key");
        }
        return executionPath.getFirst();
    }

    private static void fail(String deploymentId) {
        throw new PermissionDeniedException("User didn't accept consent to call the deployment: " + deploymentId);
    }

    private Consent readConsent(ProxyContext context, String deploymentId) {
        ResourceDescriptor descriptor = getResourceDescription(context, deploymentId);
        String consent = resourceService.getResource(descriptor);
        return ProxyUtil.convertToObject(consent, Consent.class);
    }

    private static boolean isConsentRequired(Deployment deployment) {
        return deployment.getFeatures() != null
                && Boolean.TRUE.equals(deployment.getFeatures().getConsentRequired());
    }

    /**
     * The id is already decoded - a deployment name from the config, or a path parameter the route decoded -
     * so it is not a url and cannot be validated as one.
     */
    private static ResourceDescriptor getResourceDescription(ProxyContext context, String deploymentId) {
        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        return ResourceDescriptorFactory.fromEntityPath(ResourceTypes.USER_CONSENT, bucketLocation, bucketLocation, deploymentId);
    }
}
