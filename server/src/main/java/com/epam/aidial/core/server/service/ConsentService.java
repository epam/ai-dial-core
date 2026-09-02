package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.consent.ReviewConsentResponse;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
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

import static com.epam.aidial.core.storage.http.HttpStatus.BAD_REQUEST;

@Slf4j
public class ConsentService {

    private static final ReviewConsentResponse ACCEPTED_CONSENT_RESPONSE = new ReviewConsentResponse(null, true);

    private final DeploymentService deploymentService;

    private final ResourceService resourceService;

    public ConsentService(DeploymentService deploymentService, ResourceService resourceService) {
        this.deploymentService = deploymentService;
        this.resourceService = resourceService;
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
     * The v1 gate: an administrator approves the application's declared resource dependencies.
     * The stored record is the approved snapshot — only the consent endpoint reaches this type
     * (ADMIN_CONSENT is unmapped in ResourceTypes.of()), and any declaration change re-requires
     * the grant because {@link #isAdminConsented} compares snapshots.
     */
    public Consent grantAdminConsent(ProxyContext context, String deploymentId) {
        Application application = requireDeclaringApplication(context, deploymentId);
        Consent approval = adminConsentOf(application.getResourceDependencies());
        resourceService.putResource(getAdminConsentDescription(deploymentId),
                ProxyUtil.convertToString(approval), EtagHeader.ANY);
        return approval;
    }

    /**
     * Withdraws the approval — the application stops resolving dependencies for every user
     * immediately. Returns the withdrawn record (null when absent) so the audit event can carry
     * exactly what was withdrawn.
     */
    public Consent withdrawAdminConsent(String deploymentId) {
        ResourceDescriptor descriptor = getAdminConsentDescription(deploymentId);
        Consent withdrawn = readAdminConsent(descriptor);
        resourceService.deleteResource(descriptor, EtagHeader.ANY);
        return withdrawn;
    }

    /** Content-bound check: the stored snapshot must deep-equal the declaration's current snapshot. */
    public boolean isAdminConsented(String deploymentId, List<ResourceDependency> declaration) {
        Consent stored = readAdminConsent(getAdminConsentDescription(deploymentId));
        return stored != null && Objects.equals(stored.getResources(), resourceEntriesOf(declaration));
    }

    private Application requireDeclaringApplication(ProxyContext context, String deploymentId) {
        Deployment deployment = deploymentService.findDeployment(context, deploymentId);
        if (deployment instanceof Application application
                && application.getResourceDependencies() != null
                && !application.getResourceDependencies().isEmpty()) {
            return application;
        }
        throw new HttpException(BAD_REQUEST, "Application declares no resource dependencies: " + deploymentId);
    }

    private static Consent adminConsentOf(List<ResourceDependency> declaration) {
        Consent consent = new Consent();
        consent.setResources(resourceEntriesOf(declaration));
        return consent;
    }

    private static List<Consent.ResourceEntry> resourceEntriesOf(Deployment deployment) {
        return deployment instanceof Application application ? resourceEntriesOf(application.getResourceDependencies()) : List.of();
    }

    /** Declaration order is part of the content binding: a reordered section re-requires consent. */
    private static List<Consent.ResourceEntry> resourceEntriesOf(List<ResourceDependency> declaration) {
        if (declaration == null || declaration.isEmpty()) {
            return List.of();
        }
        List<Consent.ResourceEntry> entries = new ArrayList<>(declaration.size());
        for (ResourceDependency dependency : declaration) {
            Consent.ResourceEntry entry = new Consent.ResourceEntry();
            entry.setUrl(dependency.getTarget() == null ? null : dependency.getTarget().getPath());
            entry.setAccess(dependency.getAccess() == null ? Set.of() : dependency.getAccess());
            entries.add(entry);
        }
        return entries;
    }

    /**
     * One record per application, always in the public bucket, keyed by deployment id — the
     * admin's yes (the user's yes lives in USER_CONSENT, per user). A moved app is a new key,
     * hence effectively unconsented: fail-closed.
     */
    private static ResourceDescriptor getAdminConsentDescription(String deploymentId) {
        return ResourceDescriptorFactory.fromEntityPath(ResourceTypes.ADMIN_CONSENT,
                ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION, deploymentId);
    }

    private Consent readAdminConsent(ResourceDescriptor descriptor) {
        String consent = resourceService.getResource(descriptor);
        return ProxyUtil.convertToObject(consent, Consent.class);
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
