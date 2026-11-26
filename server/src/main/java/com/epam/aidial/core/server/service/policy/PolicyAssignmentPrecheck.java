package com.epam.aidial.core.server.service.policy;

import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.config.policy.PolicyPackage;
import com.epam.aidial.core.storage.service.ResourceService;

/**
 * Validates the policies and assignments of one batch without writing them. A record that passes is remembered, so
 * a later record of the batch is validated as if the earlier ones were stored: an assignment finds the
 * policy of the same batch, and two policies of the batch can tie.
 */
public class PolicyAssignmentPrecheck {

    private final StagedPolicyRecords staged = new StagedPolicyRecords();
    private final PolicyService service;

    public PolicyAssignmentPrecheck(ResourceService resourceService) {
        this.service = new PolicyService(resourceService, staged);
    }

    public void checkPolicy(String path, Policy policy) {
        service.validatePolicy(path, policy);
        staged.stage(path, policy);
    }

    /**
     * Remembers a package of the batch, already checked on its own, so that a later policy of the batch can name it.
     */
    public void stagePackage(String name, PolicyPackage policyPackage) {
        staged.stage(name, policyPackage);
    }

    public void checkAssignment(String path, PolicyAssignment assignment) {
        service.validateAssignment(path, assignment);
        staged.stage(path, assignment);
    }
}
