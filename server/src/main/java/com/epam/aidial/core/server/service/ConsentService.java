package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.consent.ReviewConsentResponse;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class ConsentService {
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
            deploymentId = queue.poll();
            Deployment deployment = deploymentService.findDeployment(context, deploymentId);
            boolean consentRequired = isConsentRequired(deployment);
            if (consentRequired) {
                noneConsentRequired = false;
            }
            Consent.Deployment current = newConsent.getDeployments().computeIfAbsent(deploymentId, key -> new Consent.Deployment());
            current.setConsentRequired(consentRequired);
            for (String dependency : deployment.getDependencies()) {
                if (seen.add(dependency)) {
                    queue.offer(dependency);
                }
            }
        }
        if (noneConsentRequired) {
            // no deployments required user consent
            return new ReviewConsentResponse(null, true);
        }
        Consent prevConsent = readConsent(context, deploymentId);
        boolean accepted = Objects.equals(prevConsent, newConsent);
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
        String deploymentId = deployment.getName();
        Consent consent = readConsent(context, deploymentId);
        if (consent == null) {
            // missing consent
            fail(deploymentId);
        }
        List<String> executionPath = context.getApiKeyData().getExecutionPath();
        if (executionPath != null) {
            for (String dep : executionPath) {
                if (!consent.getDeployments().containsKey(dep)) {
                    fail(deploymentId);
                }
            }
        }
        Consent.Deployment consentDeployment = consent.getDeployments().get(deploymentId);
        if (consentDeployment == null || !consentDeployment.isConsentRequired()) {
            fail(deploymentId);
        }
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
