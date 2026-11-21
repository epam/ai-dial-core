package com.epam.aidial.core.server.service.policy;

import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;

import java.util.HashMap;
import java.util.Map;

/**
 * Records of a batch that passed validation but are not stored yet. Keyed by the path under the type's folder.
 */
class StagedPolicyRecords {

    final Map<String, Policy> policies = new HashMap<>();
    final Map<String, PolicyAssignment> assignments = new HashMap<>();

    void stage(String path, Policy policy) {
        policies.put(path, policy);
    }

    void stage(String path, PolicyAssignment assignment) {
        assignments.put(path, assignment);
    }
}
