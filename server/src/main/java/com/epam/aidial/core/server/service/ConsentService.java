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
import java.util.Objects;
import java.util.Set;

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
