package com.epam.aidial.core.server.service.policy;

import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
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

    public void checkAssignment(String path, PolicyAssignment assignment) {
        service.validateAssignment(path, assignment);
        staged.stage(path, assignment);
    }
}
