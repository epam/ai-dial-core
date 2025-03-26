package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.consent.Consent;
import com.epam.aidial.core.server.data.consent.ConsentResponse;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConsentService {
    private final DeploymentService deploymentService;

    private final ResourceService resourceService;

    public ConsentService(DeploymentService deploymentService, ResourceService resourceService) {
        this.deploymentService = deploymentService;
        this.resourceService = resourceService;
    }

    public ConsentResponse buildConsent(ProxyContext context, String deploymentId) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.offer(deploymentId);
        seen.add(deploymentId);
        Set<String> securedApps = new HashSet<>();
        Set<String> regularApps = new HashSet<>();
        ConsentResponse response = new ConsentResponse();
        while (!queue.isEmpty()) {
            deploymentId = queue.poll();
            Deployment deployment = deploymentService.findDeployment(context, deploymentId);
            boolean consentRequired = isConsentRequired(deployment);
            response.getConsent().getDeployments().get(deploymentId).setConsentRequired(consentRequired);
            if (consentRequired) {
                securedApps.add(deploymentId);
            } else {
                regularApps.add(deploymentId);
            }
            Consent.Deployment parentDeployment = new Consent.Deployment();
            for (String dependency : deployment.getDependencies()) {
                if (seen.add(dependency)) {
                    Consent.Deployment dependentDeployment = new Consent.Deployment();
                    dependentDeployment.setName(dependency);
                    parentDeployment.getDependencies().add(dependency);
                    response.getConsent().getDeployments().put(dependency, dependentDeployment);
                    queue.offer(dependency);
                }
            }
        }

        Consent consent = readConsent(context, deploymentId);
        response.setAccepted(hasAccepted(consent, securedApps, regularApps));
        return response;
    }

    public void acceptConsent(ProxyContext context, String deploymentId, Consent consent) {
        ResourceDescriptor descriptor = getResourceDescription(context, deploymentId);
        String consentBody = ProxyUtil.convertToString(consent);
        resourceService.putResource(descriptor, consentBody, EtagHeader.ANY);
    }

    public void verifyUserConsent(ProxyContext context, Deployment deployment) {
        List<String> executionPath = context.getApiKeyData().getExecutionPath();
        if (!isConsentRequired(deployment) ||  executionPath == null || executionPath.isEmpty()) {
            return;
        }
        String deploymentId = deployment.getName();
        Consent consent = readConsent(context, deploymentId);
        for (String dep : executionPath) {
            if (!consent.getDeployments().containsKey(dep)) {
                fail(deploymentId);
            }
        }
        Consent.Deployment consentDeployment = consent.getDeployments().get(deploymentId);
        if (consentDeployment == null || !consentDeployment.isConsentRequired()) {
            fail(deploymentId);
        }
    }

    private static void fail(String deploymentId) {
        throw new PermissionDeniedException("User didn't accept consent to call a such deployment: " + deploymentId);
    }

    private Consent readConsent(ProxyContext context, String deploymentId) {
        ResourceDescriptor descriptor = getResourceDescription(context, deploymentId);
        String consent = resourceService.getResource(descriptor);
        return ProxyUtil.convertToObject(consent, Consent.class);
    }

    private boolean hasAccepted(Consent consent, Set<String> securedApps, Set<String> regularApps) {
        if (consent == null) {
            return false;
        }
        for (Consent.Deployment deployment : consent.getDeployments().values()) {
            if (deployment.isConsentRequired()) {
                if (!securedApps.contains(deployment.getName())) {
                    return false;
                }
            } else {
                if (!regularApps.contains(deployment.getName())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isConsentRequired(Deployment deployment) {
        return deployment.getFeatures() != null
                && Boolean.TRUE.equals(deployment.getFeatures().getConsentRequired());
    }

    private ResourceDescriptor getResourceDescription(ProxyContext context, String deploymentId) {
        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        return ResourceDescriptorFactory.fromEncoded(ResourceTypes.USER_CONSENT, bucketLocation, bucketLocation, deploymentId);
    }
}
