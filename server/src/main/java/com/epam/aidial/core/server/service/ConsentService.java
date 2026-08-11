package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.consent.ReviewConsentResponse;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.epam.aidial.core.server.util.PlatformCanonicalIdUtil.lastSegment;

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
        while (!queue.isEmpty()) {
            String currentDeploymentId = queue.poll();
            Deployment deployment = deploymentService.findDeployment(context, currentDeploymentId);
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
        if (noneConsentRequired) {
            // no deployments required user consent
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
                if (findConsentDeployment(consent, dep) == null) {
                    fail(currentDeploymentId);
                }
            }
        }
        Consent.Deployment consentDeployment = findConsentDeployment(consent, currentDeploymentId);
        if (consentDeployment == null || !consentDeployment.isConsentRequired()) {
            fail(currentDeploymentId);
        }
    }

    /**
     * Looks up a deployment's consent record by id, falling back to a last-segment match when the
     * exact key misses. Compat for consent records persisted before deployment.getName() reverted
     * from canonical id (e.g. "models/platform/gpt-4") to short name for API-managed deployments —
     * without this, every such record would silently fail re-consent after upgrade.
     */
    private static Consent.Deployment findConsentDeployment(Consent consent, String id) {
        Consent.Deployment exact = consent.getDeployments().get(id);
        if (exact != null) {
            return exact;
        }
        String shortId = lastSegment(id);
        for (Map.Entry<String, Consent.Deployment> entry : consent.getDeployments().entrySet()) {
            if (lastSegment(entry.getKey()).equals(shortId)) {
                return entry.getValue();
            }
        }
        return null;
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

    private static ResourceDescriptor getResourceDescription(ProxyContext context, String deploymentId) {
        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        return ResourceDescriptorFactory.fromEncoded(ResourceTypes.USER_CONSENT, bucketLocation, bucketLocation, deploymentId);
    }
}
